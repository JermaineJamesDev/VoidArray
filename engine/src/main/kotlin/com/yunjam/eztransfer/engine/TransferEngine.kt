package com.yunjam.eztransfer.engine

import com.yunjam.eztransfer.core.FileSummary
import com.yunjam.eztransfer.core.IncomingOffer
import com.yunjam.eztransfer.core.LocalStatus
import com.yunjam.eztransfer.core.Peer
import com.yunjam.eztransfer.core.PeerSource
import com.yunjam.eztransfer.core.TransferController
import com.yunjam.eztransfer.core.TransferDirection
import com.yunjam.eztransfer.core.TransferState
import com.yunjam.eztransfer.core.TransferStatus
import com.yunjam.eztransfer.protocol.DEFAULT_PORT
import com.yunjam.eztransfer.protocol.DeviceInfo
import com.yunjam.eztransfer.protocol.DeviceType
import com.yunjam.eztransfer.protocol.FileMeta
import com.yunjam.eztransfer.protocol.PrepareRequest
import com.yunjam.eztransfer.protocol.PrepareResponse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TransferEngine(
    private val deviceType: DeviceType,
    private val settings: AppSettings,
    initialDestination: DestinationFolder,
    private val log: (String) -> Unit = { println("EzTransfer: $it") },
) : TransferController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = TransferClient()
    private val server = TransferServer(ReceiveHandler())
    private val discovery = DiscoveryService(scope, ::selfInfo, ::onPeerSeen, ::replyViaHttp, log)
    private val startMutex = Mutex()
    private val random = SecureRandom()

    @Volatile private var destination: DestinationFolder = initialDestination
    @Volatile private var port: Int = DEFAULT_PORT
    private var tickerJob: Job? = null
    private val sendJobs = ConcurrentHashMap<String, Job>()
    private val scanning = AtomicBoolean(false)

    private val _local = MutableStateFlow(
        LocalStatus(alias = settings.alias, deviceId = settings.deviceId, destinationLabel = initialDestination.label),
    )
    override val local: StateFlow<LocalStatus> = _local.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val stagedHandles = MutableStateFlow<List<FileHandle>>(emptyList())
    override val staged: StateFlow<List<FileSummary>> = stagedHandles
        .map { handles -> handles.map { FileSummary(it.name, it.size) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _incomingOffer = MutableStateFlow<IncomingOffer?>(null)
    override val incomingOffer: StateFlow<IncomingOffer?> = _incomingOffer.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferState>>(emptyList())
    override val transfers: StateFlow<List<TransferState>> = _transfers.asStateFlow()

    // Receive state. A single receive session at a time keeps the accept prompt unambiguous.
    private val receiveLock = Any()
    private var pendingOffer: PendingOffer? = null
    private var activeSession: ReceiveSession? = null

    /**
     * Checks local network access, then starts the HTTP server and discovery. Safe to call repeatedly;
     * a denied permission leaves the engine stopped with [LocalStatus.problem] set so the UI can offer a retry.
     */
    suspend fun start(access: LocalNetworkAccess) {
        if (_local.value.serverRunning) return
        if (!access.ensure()) {
            _local.update {
                it.copy(problem = "Local network access is not allowed. Grant the Nearby devices permission so EzTransfer can find and reach other devices.")
            }
            return
        }
        startMutex.withLock {
            if (_local.value.serverRunning) return
            port = try {
                server.start(DEFAULT_PORT)
            } catch (e: Exception) {
                log("Server failed to start: $e")
                _local.update { it.copy(problem = "Could not start the receiver: ${e.message}") }
                return
            }
            val discoveryProblem = runCatching { discovery.start() }.exceptionOrNull()?.let {
                log("Discovery failed to start: $it")
                "Automatic discovery is unavailable (UDP port $DEFAULT_PORT may be in use). You can still add devices by IP."
            }
            _local.update {
                it.copy(
                    serverRunning = true,
                    port = port,
                    addresses = currentAddresses(),
                    problem = discoveryProblem,
                )
            }
            tickerJob = scope.launch { ticker() }
            discovery.announce()
        }
    }

    /** Stops networking and releases all resources. The engine cannot be restarted afterwards. */
    fun close() {
        runBlocking {
            withTimeoutOrNull(3_000) {
                tickerJob?.cancel()
                discovery.stop()
                server.stop()
            }
        }
        scope.cancel()
        client.close()
    }

    fun stage(handles: List<FileHandle>) {
        if (handles.isNotEmpty()) stagedHandles.update { it + handles }
    }

    fun setDestination(folder: DestinationFolder) {
        destination = folder
        _local.update { it.copy(destinationLabel = folder.label) }
    }

    fun setAlias(alias: String) {
        settings.alias = alias
        _local.update { it.copy(alias = settings.alias) }
    }

    fun notice(message: String) {
        _local.update { it.copy(notice = message) }
    }

    /**
     * Ends every active transfer with [reason]. Used when the platform withdraws the right to keep
     * running, such as Android's dataSync foreground-service time limit. Partial files are kept so
     * sending the same files again resumes where this left off.
     */
    fun interruptActiveTransfers(reason: String) {
        sendJobs.values.forEach { it.cancel(InterruptedTransfer(reason)) }
        val session = synchronized(receiveLock) { activeSession }
        if (session != null) endSession(session, TransferStatus.FAILED, reason, discardPartials = false)
    }

    override fun rescan() {
        discovery.announce()
        if (!scanning.compareAndSet(false, true)) return
        _local.update { it.copy(scanning = true) }
        scope.launch {
            try {
                sweepSubnets()
            } finally {
                scanning.set(false)
                _local.update { it.copy(scanning = false) }
            }
        }
    }

    override fun addManualPeer(host: String, port: Int) {
        val target = host.trim()
        if (target.isEmpty()) return
        scope.launch {
            try {
                val info = client.info(target, port)
                if (info.deviceId == settings.deviceId) {
                    notice("That address is this device.")
                } else {
                    upsertPeer(info, target, PeerSource.MANUAL)
                }
            } catch (e: Exception) {
                notice("Could not reach $target:$port. ${e.friendlyMessage()}")
            }
        }
    }

    override fun removeStaged(index: Int) {
        stagedHandles.update { list -> list.filterIndexed { i, _ -> i != index } }
    }

    override fun clearStaged() {
        stagedHandles.value = emptyList()
    }

    override fun respondToOffer(accept: Boolean) {
        synchronized(receiveLock) { pendingOffer }?.decision?.complete(accept)
    }

    override fun cancelTransfer(id: String) {
        sendJobs[id]?.cancel()
        val session = synchronized(receiveLock) { activeSession?.takeIf { it.transferId == id } }
        if (session != null) endSession(session, TransferStatus.CANCELLED, "Cancelled", discardPartials = true)
    }

    override fun clearFinishedTransfers() {
        _transfers.update { list -> list.filter { it.isActive } }
    }

    override fun dismissNotice() {
        _local.update { it.copy(notice = null) }
    }

    // ---- Sending ----

    override fun sendStaged(peer: Peer) {
        val handles = stagedHandles.value
        if (handles.isEmpty()) return
        val transferId = newId()
        addTransfer(
            TransferState(
                id = transferId,
                direction = TransferDirection.SEND,
                peerAlias = peer.info.alias,
                files = handles.map { FileSummary(it.name, it.size) },
                totalBytes = handles.sumOf { it.size },
                status = TransferStatus.CONNECTING,
            ),
        )
        val job = scope.launch { runSend(transferId, peer, handles) }
        sendJobs[transferId] = job
        job.invokeOnCompletion { sendJobs.remove(transferId) }
    }

    private suspend fun runSend(transferId: String, peer: Peer, handles: List<FileHandle>) {
        var host: String? = null
        var peerPort = peer.info.port
        var sessionId: String? = null
        try {
            val (reachableHost, info) = firstReachable(peer)
                ?: throw TransferException("Could not reach ${peer.info.alias}. Check that both devices are on the same network.")
            host = reachableHost
            peerPort = info.port
            updateTransfer(transferId) { it.copy(status = TransferStatus.WAITING_FOR_ACCEPT) }

            val metas = handles.mapIndexed { i, h -> FileMeta(id = "f$i", name = h.name, size = h.size) }
            val response = when (val result = client.prepare(reachableHost, peerPort, PrepareRequest(selfInfo(), metas))) {
                is TransferClient.PrepareResult.Accepted -> result.response
                TransferClient.PrepareResult.Declined -> {
                    updateTransfer(transferId) { it.copy(status = TransferStatus.DECLINED, message = "Declined by ${info.alias}") }
                    return
                }
                TransferClient.PrepareResult.Busy ->
                    throw TransferException("${info.alias} is busy with another transfer.")
                is TransferClient.PrepareResult.Failed -> throw TransferException(result.message)
            }
            sessionId = response.sessionId

            val alreadyThere = metas.sumOf { response.offsets[it.id] ?: 0L }
            val progress = ProgressTracker(transferId, alreadyThere)
            updateTransfer(transferId) { it.copy(status = TransferStatus.IN_PROGRESS, bytesTransferred = alreadyThere) }
            metas.forEachIndexed { i, meta ->
                val token = response.tokens[meta.id] ?: throw TransferException("Receiver did not accept ${meta.name}")
                val offset = response.offsets[meta.id] ?: 0L
                updateTransfer(transferId) { it.copy(currentFile = meta.name) }
                client.upload(reachableHost, peerPort, response.sessionId, meta.id, token, handles[i], offset, progress::add)
            }
            progress.flush()
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.COMPLETED, bytesTransferred = it.totalBytes, currentFile = null)
            }
            stagedHandles.update { current -> current.filterNot { it in handles } }
        } catch (e: InterruptedTransfer) {
            // Not a user cancel: the receiver is left alone so it keeps its partial files for resume.
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.FAILED, currentFile = null, message = e.message)
            }
            throw e
        } catch (e: CancellationException) {
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.CANCELLED, currentFile = null, message = "Cancelled")
            }
            val h = host
            val s = sessionId
            if (h != null && s != null) {
                withContext(NonCancellable) { runCatching { client.cancel(h, peerPort, s) } }
            }
            throw e
        } catch (e: Exception) {
            log("Send failed: $e")
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.FAILED, currentFile = null, message = e.friendlyMessage())
            }
        }
    }

    /**
     * Tries every known address for [peer] at once and returns the first that answers as the same device.
     * Racing avoids guessing which interface is reachable when VPNs or multiple adapters are involved.
     */
    private suspend fun firstReachable(peer: Peer): Pair<String, DeviceInfo>? = coroutineScope {
        val result = CompletableDeferred<Pair<String, DeviceInfo>?>()
        val attempts = peer.addresses.map { address ->
            launch {
                val info = runCatching { client.info(address, peer.info.port) }.getOrNull()
                if (info != null && info.deviceId == peer.info.deviceId) result.complete(address to info)
            }
        }
        launch {
            attempts.joinAll()
            result.complete(null)
        }
        result.await().also { coroutineContext.cancelChildren() }
    }

    // ---- Discovery ----

    private fun selfInfo() = DeviceInfo(
        deviceId = settings.deviceId,
        alias = settings.alias,
        deviceType = deviceType,
        port = port,
    )

    private fun onPeerSeen(info: DeviceInfo, address: String) {
        upsertPeer(info, address, PeerSource.DISCOVERED)
    }

    private fun upsertPeer(info: DeviceInfo, address: String, source: PeerSource) {
        if (info.deviceId == settings.deviceId) return
        val now = System.currentTimeMillis()
        _peers.update { peers ->
            val existing = peers.find { it.info.deviceId == info.deviceId }
            val updated = Peer(
                info = info,
                addresses = (listOf(address) + existing?.addresses.orEmpty()).distinct().take(MAX_PEER_ADDRESSES),
                source = if (existing?.source == PeerSource.MANUAL) PeerSource.MANUAL else source,
                lastSeenMillis = now,
            )
            (peers.filterNot { it.info.deviceId == info.deviceId } + updated).sortedBy { it.info.alias.lowercase() }
        }
    }

    private suspend fun replyViaHttp(info: DeviceInfo, address: String): Boolean =
        runCatching {
            val theirs = client.register(address, info.port, selfInfo())
            onPeerSeen(theirs, address)
        }.isSuccess

    /**
     * Last-resort discovery for networks that drop multicast and broadcast: probes every host in each
     * private /24 this device is on. Virtual adapters are skipped since their subnets rarely hold peers.
     */
    private suspend fun sweepSubnets() {
        val ownAddresses = localIpv4Addresses()
            .filter { !it.likelyVirtual && it.address.isSiteLocalAddress }
            .map { it.address.address }
            .distinctBy { it.take(3) }
            .take(MAX_SWEEP_SUBNETS)
        val permits = Semaphore(SWEEP_CONCURRENCY)
        coroutineScope {
            for (own in ownAddresses) {
                val prefix = own.take(3).joinToString(".") { (it.toInt() and 0xff).toString() }
                val ownLast = own[3].toInt() and 0xff
                for (last in 1..254) {
                    if (last == ownLast) continue
                    val host = "$prefix.$last"
                    launch {
                        permits.withPermit {
                            runCatching { client.info(host, DEFAULT_PORT, quick = true) }
                                .getOrNull()
                                ?.let { onPeerSeen(it, host) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun ticker() {
        var ticks = 0
        while (scope.isActive) {
            delay(TICK_MILLIS)
            ticks++
            if (discovery.refreshMemberships()) {
                _local.update { it.copy(addresses = currentAddresses()) }
                discovery.announce()
            } else if (ticks % ANNOUNCE_EVERY_TICKS == 0) {
                discovery.announce()
            }
            val cutoff = System.currentTimeMillis() - PEER_EXPIRY_MILLIS
            _peers.update { peers -> peers.filter { it.source == PeerSource.MANUAL || it.lastSeenMillis >= cutoff } }

            val session = synchronized(receiveLock) { activeSession }
            if (session != null && !session.uploading &&
                System.currentTimeMillis() - session.lastActivity > RECEIVE_IDLE_TIMEOUT_MILLIS
            ) {
                endSession(session, TransferStatus.FAILED, "The sender stopped responding. Send again to resume.", discardPartials = false)
            }
        }
    }

    private fun currentAddresses(): List<String> =
        localIpv4Addresses().mapNotNull { it.address.hostAddress }.distinct()

    // ---- Receiving ----

    private class PendingOffer(val decision: CompletableDeferred<Boolean>)

    private class ReceiveFile(
        val meta: FileMeta,
        val safeName: String,
        val partialName: String,
        val token: String,
    ) {
        @Volatile var done = false
    }

    private inner class ReceiveSession(
        val id: String,
        val transferId: String,
        val senderAddress: String,
        val files: Map<String, ReceiveFile>,
        val destination: DestinationFolder,
        val progress: ProgressTracker,
    ) {
        @Volatile var ended = false
        @Volatile var uploading = false
        @Volatile var lastActivity = System.currentTimeMillis()
        @Volatile var discardRequested = false
    }

    private inner class ReceiveHandler : ServerHandler {
        override fun info(): DeviceInfo = selfInfo()

        override fun onRegister(peer: DeviceInfo, remoteAddress: String) = onPeerSeen(peer, remoteAddress)

        override suspend fun onPrepare(request: PrepareRequest, remoteAddress: String): PrepareOutcome {
            if (request.files.isEmpty()) return PrepareOutcome.Invalid("No files offered")
            if (request.files.any { it.size < 0 }) return PrepareOutcome.Invalid("Negative file size")
            if (request.files.distinctBy { it.id }.size != request.files.size) return PrepareOutcome.Invalid("Duplicate file ids")
            onPeerSeen(request.sender, remoteAddress)

            val pending = PendingOffer(CompletableDeferred())
            synchronized(receiveLock) {
                if (pendingOffer != null || activeSession != null) return PrepareOutcome.Busy
                pendingOffer = pending
            }
            val files = request.files.map { meta ->
                val safe = sanitizeFileName(meta.name)
                ReceiveFile(meta, safe, partialFileName(request.sender.deviceId, safe, meta.size), newToken())
            }
            var session: ReceiveSession? = null
            try {
                _incomingOffer.value = IncomingOffer(
                    id = newId(),
                    sender = request.sender,
                    senderAddress = remoteAddress,
                    files = files.map { FileSummary(it.safeName, it.meta.size) },
                )
                val accepted = withTimeoutOrNull(PROMPT_TIMEOUT_MILLIS) { pending.decision.await() } ?: false
                _incomingOffer.value = null
                if (!accepted) return PrepareOutcome.Declined

                // Nothing touches the destination until the user has accepted.
                val folder = destination
                val existing = runCatching { folder.existingSizes(files.map { it.partialName }) }
                    .onFailure { log("Could not inspect destination: $it") }
                    .getOrDefault(emptyMap())
                val offsets = files.associate { f ->
                    f.meta.id to (existing[f.partialName]?.takeIf { it <= f.meta.size } ?: 0L)
                }
                val transferId = newId()
                val alreadyThere = offsets.values.sum()
                addTransfer(
                    TransferState(
                        id = transferId,
                        direction = TransferDirection.RECEIVE,
                        peerAlias = request.sender.alias,
                        files = files.map { FileSummary(it.safeName, it.meta.size) },
                        totalBytes = files.sumOf { it.meta.size },
                        bytesTransferred = alreadyThere,
                        status = TransferStatus.IN_PROGRESS,
                    ),
                )
                session = ReceiveSession(
                    id = newId(),
                    transferId = transferId,
                    senderAddress = remoteAddress,
                    files = files.associateBy { it.meta.id },
                    destination = folder,
                    progress = ProgressTracker(transferId, alreadyThere),
                )
                synchronized(receiveLock) { activeSession = session }
                return PrepareOutcome.Accepted(
                    PrepareResponse(
                        sessionId = session.id,
                        tokens = files.associate { it.meta.id to it.token },
                        offsets = offsets,
                    ),
                )
            } finally {
                _incomingOffer.value = null
                synchronized(receiveLock) { if (pendingOffer === pending) pendingOffer = null }
            }
        }

        override suspend fun onUpload(
            sessionId: String,
            fileId: String,
            token: String,
            offset: Long,
            remoteAddress: String,
            body: ByteReadChannel,
        ): UploadOutcome {
            val session = synchronized(receiveLock) { activeSession }?.takeIf { it.id == sessionId }
                ?: return UploadOutcome.Rejected(404, "The receiver ended this transfer")
            // Tokens are the only authentication over plain HTTP, so also pin the session to the sender's address.
            if (session.senderAddress != remoteAddress) return UploadOutcome.Rejected(403, "Wrong sender")
            val file = session.files[fileId] ?: return UploadOutcome.Rejected(404, "Unknown file")
            if (!MessageDigest.isEqual(file.token.toByteArray(), token.toByteArray())) {
                return UploadOutcome.Rejected(403, "Invalid token")
            }
            if (file.done) return UploadOutcome.Rejected(409, "${file.safeName} was already received")
            if (offset > file.meta.size) return UploadOutcome.Rejected(400, "Offset beyond end of file")

            session.uploading = true
            session.lastActivity = System.currentTimeMillis()
            updateTransfer(session.transferId) { it.copy(currentFile = file.safeName) }
            try {
                withContext(Dispatchers.IO) {
                    session.destination.openPartial(file.partialName, offset).use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var remaining = file.meta.size - offset
                        while (remaining > 0) {
                            if (session.ended) throw TransferException("Cancelled")
                            val read = body.readAvailable(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) throw TransferException("Connection closed with $remaining bytes left")
                            if (read == 0) continue
                            out.write(buffer, 0, read)
                            remaining -= read
                            session.lastActivity = System.currentTimeMillis()
                            session.progress.add(read.toLong())
                        }
                    }
                    session.destination.commit(file.partialName, file.safeName)
                }
                file.done = true
            } catch (e: CancellationException) {
                endSession(session, TransferStatus.FAILED, "Connection lost. Send again to resume.", discardPartials = false)
                throw e
            } catch (e: Exception) {
                if (session.ended) {
                    // Cancelled mid-upload: endSession skipped the cleanup because this file was still open.
                    if (session.discardRequested) discardIncomplete(session)
                    return UploadOutcome.Rejected(410, "Transfer was cancelled")
                }
                log("Receive failed: $e")
                endSession(session, TransferStatus.FAILED, "Interrupted: ${e.friendlyMessage()} Send again to resume.", discardPartials = false)
                return UploadOutcome.Rejected(500, e.friendlyMessage())
            } finally {
                session.uploading = false
            }

            if (session.files.values.all { it.done }) {
                session.progress.flush()
                endSession(session, TransferStatus.COMPLETED, "Saved to ${session.destination.label}", discardPartials = false)
            }
            return UploadOutcome.Ok
        }

        override fun onCancel(sessionId: String, remoteAddress: String) {
            val session = synchronized(receiveLock) { activeSession }
                ?.takeIf { it.id == sessionId && it.senderAddress == remoteAddress } ?: return
            endSession(session, TransferStatus.CANCELLED, "Cancelled by sender", discardPartials = true)
        }
    }

    private fun endSession(session: ReceiveSession, status: TransferStatus, message: String, discardPartials: Boolean) {
        synchronized(receiveLock) {
            if (session.ended) return
            session.ended = true
            if (activeSession === session) activeSession = null
        }
        updateTransfer(session.transferId) { state ->
            val bytes = if (status == TransferStatus.COMPLETED) state.totalBytes else state.bytesTransferred
            state.copy(status = status, message = message, currentFile = null, bytesTransferred = bytes)
        }
        if (discardPartials) {
            session.discardRequested = true
            // A running upload still holds its file open; it discards once its loop sees the session ended.
            if (!session.uploading) discardIncomplete(session)
        }
    }

    private fun discardIncomplete(session: ReceiveSession) {
        scope.launch {
            session.files.values.filterNot { it.done }.forEach { file ->
                runCatching { session.destination.discard(file.partialName) }
            }
        }
    }

    // ---- State helpers ----

    /** Coalesces per-buffer progress into at most a few StateFlow updates per second. */
    private inner class ProgressTracker(private val transferId: String, start: Long) {
        @Volatile private var bytes = start
        @Volatile private var lastEmitNanos = 0L

        fun add(count: Long) {
            bytes += count
            val now = System.nanoTime()
            if (now - lastEmitNanos > PROGRESS_INTERVAL_NANOS) {
                lastEmitNanos = now
                flush()
            }
        }

        fun flush() {
            val current = bytes
            updateTransfer(transferId) { it.copy(bytesTransferred = current) }
        }
    }

    private fun addTransfer(state: TransferState) {
        _transfers.update { listOf(state) + it }
    }

    private fun updateTransfer(id: String, transform: (TransferState) -> TransferState) {
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun newToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TICK_MILLIS = 5_000L
        const val ANNOUNCE_EVERY_TICKS = 6
        const val PEER_EXPIRY_MILLIS = 90_000L
        const val RECEIVE_IDLE_TIMEOUT_MILLIS = 60_000L
        const val PROGRESS_INTERVAL_NANOS = 150_000_000L
        const val MAX_PEER_ADDRESSES = 4
        const val MAX_SWEEP_SUBNETS = 3
        const val SWEEP_CONCURRENCY = 48
    }
}

private class InterruptedTransfer(reason: String) : CancellationException(reason)

internal fun Throwable.friendlyMessage(): String = when (this) {
    is TransferException -> message ?: "Transfer failed"
    is java.net.ConnectException -> "Connection refused or blocked by a firewall."
    is java.net.SocketTimeoutException -> "Timed out. The device may be unreachable or blocked by a firewall."
    is java.net.UnknownHostException -> "Unknown address."
    else -> message ?: this::class.simpleName ?: "Unknown error"
}
