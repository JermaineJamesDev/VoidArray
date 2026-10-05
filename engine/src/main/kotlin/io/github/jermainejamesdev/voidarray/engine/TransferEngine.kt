package io.github.jermainejamesdev.voidarray.engine

import io.github.jermainejamesdev.voidarray.core.FileSummary
import io.github.jermainejamesdev.voidarray.core.HistoryEntry
import io.github.jermainejamesdev.voidarray.core.IncomingOffer
import io.github.jermainejamesdev.voidarray.core.LocalStatus
import io.github.jermainejamesdev.voidarray.core.PairingInvite
import io.github.jermainejamesdev.voidarray.core.PairingRequest
import io.github.jermainejamesdev.voidarray.core.Peer
import io.github.jermainejamesdev.voidarray.core.PeerSource
import io.github.jermainejamesdev.voidarray.core.QrPairingState
import io.github.jermainejamesdev.voidarray.core.ThemeMode
import io.github.jermainejamesdev.voidarray.core.TransferController
import io.github.jermainejamesdev.voidarray.core.TransferDirection
import io.github.jermainejamesdev.voidarray.core.TransferState
import io.github.jermainejamesdev.voidarray.core.TransferStatus
import io.github.jermainejamesdev.voidarray.core.TrustedDevice
import io.github.jermainejamesdev.voidarray.core.UserSettings
import io.github.jermainejamesdev.voidarray.core.looksExecutable
import io.github.jermainejamesdev.voidarray.protocol.DEFAULT_PORT
import io.github.jermainejamesdev.voidarray.protocol.DeviceInfo
import io.github.jermainejamesdev.voidarray.protocol.DeviceType
import io.github.jermainejamesdev.voidarray.protocol.FileMeta
import io.github.jermainejamesdev.voidarray.protocol.MAX_TEXT_LENGTH
import io.github.jermainejamesdev.voidarray.protocol.PROTOCOL_VERSION
import io.github.jermainejamesdev.voidarray.protocol.PairRequest
import io.github.jermainejamesdev.voidarray.protocol.PairResponse
import io.github.jermainejamesdev.voidarray.protocol.PrepareRequest
import io.github.jermainejamesdev.voidarray.protocol.PrepareResponse
import io.github.jermainejamesdev.voidarray.protocol.QrPairRequest
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
import kotlinx.coroutines.flow.updateAndGet
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
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TransferEngine(
    private val deviceType: DeviceType,
    private val appSettings: AppSettings,
    private val identityStore: IdentityStore,
    initialDestination: DestinationFolder,
    historyFile: File? = null,
    private val log: (String) -> Unit = { println("VoidArray: $it") },
) : TransferController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = ReceiveHandler()

    // Replaced together, under startMutex, when the user resets this device's key.
    @Volatile private var identity: DeviceIdentity = identityStore.load()
    @Volatile private var client = TransferClient(identity.sslContext())
    @Volatile private var server = TransferServer(identity.sslContext(), handler, log)

    private val discovery = DiscoveryService(scope, ::selfInfo, ::onAnnouncement, { appSettings.discoverable }, log)
    private val trustStore = TrustStore(appSettings.store)
    private val historyStore = HistoryStore(historyFile)
    private val historyWriter = Dispatchers.IO.limitedParallelism(1)
    private val startMutex = Mutex()
    private val random = SecureRandom()

    @Volatile private var destination: DestinationFolder = initialDestination
    @Volatile private var port: Int = DEFAULT_PORT
    private var tickerJob: Job? = null
    private val sendJobs = ConcurrentHashMap<String, Job>()
    private val scanning = AtomicBoolean(false)

    private val _local = MutableStateFlow(
        LocalStatus(
            alias = appSettings.alias,
            deviceId = appSettings.deviceId,
            deviceType = deviceType,
            fingerprint = formatFingerprint(identity.fingerprint),
            destinationLabel = initialDestination.label,
        ),
    )
    override val local: StateFlow<LocalStatus> = _local.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    override val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val stagedHandles = MutableStateFlow<List<FileHandle>>(emptyList())
    override val staged: StateFlow<List<FileSummary>> = stagedHandles
        .map { handles -> handles.map { FileSummary(it.name, it.size) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _stagedText = MutableStateFlow("")
    override val stagedText: StateFlow<String> = _stagedText.asStateFlow()

    private val _incomingOffer = MutableStateFlow<IncomingOffer?>(null)
    override val incomingOffer: StateFlow<IncomingOffer?> = _incomingOffer.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferState>>(emptyList())
    override val transfers: StateFlow<List<TransferState>> = _transfers.asStateFlow()

    private val _history = MutableStateFlow(historyStore.load())
    override val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _trustedDevices = MutableStateFlow(trustStore.devices)
    override val trustedDevices: StateFlow<List<TrustedDevice>> = _trustedDevices.asStateFlow()

    private val _pairingInvite = MutableStateFlow<PairingInvite?>(null)
    override val pairingInvite: StateFlow<PairingInvite?> = _pairingInvite.asStateFlow()

    private val _qrPairing = MutableStateFlow<QrPairingState?>(null)
    override val qrPairing: StateFlow<QrPairingState?> = _qrPairing.asStateFlow()

    // QR pairing. At most one code is open at a time, and each one is consumed by the first valid scan.
    private val inviteLock = Any()
    private var invite: Invite? = null
    private var inviteExpiryJob: Job? = null
    private var qrPairingJob: Job? = null

    // Receive state. A single receive session at a time keeps the accept prompt unambiguous.
    private val receiveLock = Any()
    private var pendingOffer: PendingOffer? = null
    private var activeSession: ReceiveSession? = null
    private val pendingPairings = ConcurrentHashMap<String, PendingPairing>()
    private val pairingAttempts = AttemptLimiter(PAIRINGS_PER_MINUTE, PAIRINGS_PER_MINUTE_PER_DEVICE, 60_000L)

    /**
     * Checks local network access, then starts the HTTPS server and discovery. Safe to call repeatedly;
     * a denied permission leaves the engine stopped with [LocalStatus.problem] set so the UI can offer a retry.
     */
    suspend fun start(access: LocalNetworkAccess) {
        if (_local.value.serverRunning) return
        if (!access.ensure()) {
            _local.update {
                it.copy(problem = "Local network access is not allowed. Grant the Nearby devices permission so VoidArray can find and reach other devices.")
            }
            return
        }
        startMutex.withLock {
            if (_local.value.serverRunning) return
            port = try {
                withContext(Dispatchers.IO) { server.start(DEFAULT_PORT) }
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
                it.copy(serverRunning = true, port = port, addresses = currentAddresses(), problem = discoveryProblem)
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

    /** The folder received files currently go to, for platform "open folder" actions. */
    val currentDestination: DestinationFolder get() = destination

    fun notice(message: String) {
        _local.update { it.copy(notice = message) }
    }

    fun setNetworkWarning(message: String?) {
        _local.update { it.copy(networkWarning = message) }
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

    // ---- Settings ----

    private fun readSettings() = UserSettings(
        alias = appSettings.alias,
        autoAcceptTrusted = appSettings.autoAcceptTrusted,
        theme = appSettings.theme,
        minimizeToTray = appSettings.minimizeToTray,
        discoverable = appSettings.discoverable,
    )

    override fun setAlias(alias: String) {
        appSettings.alias = alias
        _settings.value = readSettings()
        _local.update { it.copy(alias = appSettings.alias) }
        discovery.announce()
    }

    override fun setAutoAcceptTrusted(enabled: Boolean) {
        appSettings.autoAcceptTrusted = enabled
        _settings.value = readSettings()
    }

    override fun setTheme(mode: ThemeMode) {
        appSettings.theme = mode
        _settings.value = readSettings()
    }

    override fun setMinimizeToTray(enabled: Boolean) {
        appSettings.minimizeToTray = enabled
        _settings.value = readSettings()
    }

    override fun setDiscoverable(enabled: Boolean) {
        appSettings.discoverable = enabled
        _settings.value = readSettings()
        if (enabled) discovery.announce()
    }

    override fun resetIdentity() {
        val busy = _transfers.value.any { it.isActive } || synchronized(receiveLock) { pendingOffer != null || activeSession != null }
        if (busy) {
            notice("Finish or cancel the current transfer before resetting this device's key.")
            return
        }
        scope.launch {
            startMutex.withLock {
                val fresh = try {
                    identityStore.replace()
                } catch (e: Exception) {
                    log("Identity reset failed: $e")
                    notice("Could not create a new key: ${e.message}")
                    return@withLock
                }
                closePairingQr()
                pendingPairings.clear()
                val running = _local.value.serverRunning
                // Both TLS contexts are rebuilt rather than patched: cached sessions and session tickets
                // from the old key must not let a peer resume a connection that skips the new certificate.
                server.stop()
                client.close()
                identity = fresh
                client = TransferClient(fresh.sslContext())
                server = TransferServer(fresh.sslContext(), handler, log)
                if (running) {
                    port = try {
                        withContext(Dispatchers.IO) { server.start(port) }
                    } catch (e: Exception) {
                        log("Server failed to restart: $e")
                        _local.update { it.copy(serverRunning = false, problem = "Could not restart the receiver: ${e.message}") }
                        return@withLock
                    }
                }
                _local.update { it.copy(fingerprint = formatFingerprint(fresh.fingerprint), port = if (running) port else it.port) }
                discovery.announce()
                notice("This device has a new key. Pair again with devices that trusted the old one.")
            }
        }
    }

    override fun forgetDevice(deviceId: String) {
        trustStore.remove(deviceId)
        onTrustChanged()
    }

    override fun clearHistory() {
        _history.value = emptyList()
        scope.launch(historyWriter) { historyStore.save(emptyList()) }
    }

    private fun onTrustChanged() {
        _trustedDevices.value = trustStore.devices
        _peers.update { peers -> peers.map { withTrust(it) } }
    }

    // ---- UI actions ----

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
                when {
                    info.deviceId == appSettings.deviceId -> notice("That address is this device.")
                    info.protocolVersion != PROTOCOL_VERSION ->
                        notice("${info.alias} runs an incompatible version of VoidArray. Update both devices.")
                    else -> upsertPeer(info, target, PeerSource.MANUAL)
                }
            } catch (e: Exception) {
                notice("Could not reach $target:$port. ${e.friendlyMessage()}")
            }
        }
    }

    // ---- QR pairing ----

    override fun showPairingQr() {
        val local = _local.value
        val listenPort = local.port
        if (!local.serverRunning || listenPort == null) {
            notice("VoidArray is not receiving yet, so other devices cannot pair with it.")
            return
        }
        val addresses = currentAddresses().take(MAX_QR_ADDRESSES)
        if (addresses.isEmpty()) {
            notice("Connect to a Wi-Fi or Ethernet network first.")
            return
        }
        val token = newToken()
        val expiresAt = System.currentTimeMillis() + QR_INVITE_TTL_MILLIS
        val uri = PairingLink(identity.fingerprint, listenPort, addresses, token).toUri()
        val created = Invite(token, expiresAt)
        synchronized(inviteLock) {
            invite?.decision?.complete(false)
            invite = created
            inviteExpiryJob?.cancel()
            inviteExpiryJob = scope.launch {
                delay(QR_INVITE_TTL_MILLIS)
                // A code someone has already scanned stays open until its user answers the prompt.
                if (expireInvite(created)) notice("The pairing code expired. Show a new one to pair.")
            }
        }
        _pairingInvite.value = PairingInvite(uri, encodeQr(uri), expiresAt)
    }

    override fun closePairingQr() {
        synchronized(inviteLock) {
            invite?.decision?.complete(false)
            invite = null
            inviteExpiryJob?.cancel()
            inviteExpiryJob = null
        }
        _pairingInvite.value = null
    }

    /** Closes [expired] if it is still the open code and nobody has scanned it. */
    private fun expireInvite(expired: Invite): Boolean {
        synchronized(inviteLock) {
            if (invite !== expired || expired.claimed) return false
            invite = null
        }
        _pairingInvite.value = null
        return true
    }

    override fun respondToPairingRequest(accept: Boolean) {
        synchronized(inviteLock) { invite }?.decision?.complete(accept)
    }

    override fun pairWithQr(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) {
            _qrPairing.value = QrPairingState.Failed("That QR code is not a VoidArray pairing code.")
            return
        }
        if (link.fingerprint.equals(identity.fingerprint, ignoreCase = true)) {
            _qrPairing.value = QrPairingState.Failed("That is this device's own pairing code. Scan the code on the other device.")
            return
        }
        qrPairingJob?.cancel()
        _qrPairing.value = QrPairingState.Connecting
        qrPairingJob = scope.launch {
            try {
                val (host, info) = firstReachable(link.addresses, link.port, link.fingerprint)
                    ?: throw TransferException("Could not reach the other device. Check that both devices are on the same network.")
                if (info.protocolVersion != PROTOCOL_VERSION) {
                    throw TransferException("${sanitizeAlias(info.alias)} runs an incompatible version of VoidArray. Update both devices.")
                }
                val alias = sanitizeAlias(info.alias)
                _qrPairing.value = QrPairingState.WaitingForConfirmation(alias)
                when (val result = client.pairByQr(host, info.port, link.fingerprint, QrPairRequest(selfInfo(), link.token))) {
                    is TransferClient.QrPairResult.Paired -> {
                        // The key comes from the scanned code, never from the network, which is what makes this pairing safe.
                        if (result.info.deviceId != info.deviceId) throw TransferException("The other device changed while pairing. Try again.")
                        trustStore.add(TrustedDevice(info.deviceId, alias, link.fingerprint, System.currentTimeMillis()))
                        onTrustChanged()
                        upsertPeer(result.info, host, PeerSource.MANUAL)
                        _qrPairing.value = QrPairingState.Paired(alias)
                    }
                    TransferClient.QrPairResult.Declined -> _qrPairing.value = QrPairingState.Failed("$alias declined the pairing.")
                    is TransferClient.QrPairResult.Failed -> _qrPairing.value = QrPairingState.Failed(result.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("QR pairing failed: $e")
                _qrPairing.value = QrPairingState.Failed(e.friendlyMessage())
            }
        }
    }

    override fun dismissQrPairing() {
        qrPairingJob?.cancel()
        qrPairingJob = null
        _qrPairing.value = null
    }

    override fun removeStaged(index: Int) {
        stagedHandles.update { list -> list.filterIndexed { i, _ -> i != index } }
    }

    override fun clearStaged() {
        stagedHandles.value = emptyList()
        _stagedText.value = ""
    }

    override fun setStagedText(text: String) {
        _stagedText.value = text.take(MAX_TEXT_LENGTH)
    }

    override fun respondToOffer(accept: Boolean, trust: Boolean) {
        synchronized(receiveLock) { pendingOffer }?.decision?.complete(Decision(accept, trust))
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
        val text = _stagedText.value.trim().ifEmpty { null }
        if (handles.isEmpty() && text == null) return
        val transferId = newId()
        addTransfer(
            TransferState(
                id = transferId,
                direction = TransferDirection.SEND,
                peerAlias = peer.info.alias,
                files = handles.map { FileSummary(it.name, it.size) },
                totalBytes = handles.sumOf { it.size },
                text = text,
                status = TransferStatus.CONNECTING,
            ),
        )
        val job = scope.launch { runSend(transferId, peer, handles, text) }
        sendJobs[transferId] = job
        job.invokeOnCompletion { sendJobs.remove(transferId) }
    }

    private suspend fun runSend(transferId: String, peer: Peer, handles: List<FileHandle>, text: String?) {
        var host: String? = null
        val pin = peer.info.fingerprint
        var peerPort = peer.info.port
        var sessionId: String? = null
        try {
            if (peer.identityChanged) throw PinMismatchException()
            val (reachableHost, info) = firstReachable(peer.addresses, peer.info.port, pin, peer.info.deviceId)
                ?: throw TransferException("Could not reach ${peer.info.alias}. Check that both devices are on the same network.")
            host = reachableHost
            peerPort = info.port

            // Commit to a nonce, learn the receiver's, then reveal ours with the offer. Both sides derive the
            // same code from all four values; see pairingCode for why the order matters.
            val senderNonce = newPairingNonce(random)
            val pairing: PairResponse =
                client.pair(reachableHost, peerPort, pin, PairRequest(pairingCommitment(identity.fingerprint, senderNonce)))
            val code = pairingCode(identity.fingerprint, pin, senderNonce, pairing.nonce)
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.WAITING_FOR_ACCEPT, pairingCode = if (peer.trusted) null else code)
            }

            val metas = handles.mapIndexed { i, h -> FileMeta(id = "f$i", name = h.name, size = h.size) }
            val request = PrepareRequest(selfInfo(), metas, text, pairingId = pairing.pairingId, nonce = senderNonce)
            val response = when (val result = client.prepare(reachableHost, peerPort, pin, request)) {
                is TransferClient.PrepareResult.Accepted -> result.response
                TransferClient.PrepareResult.Declined -> {
                    updateTransfer(transferId) {
                        it.copy(status = TransferStatus.DECLINED, pairingCode = null, message = "Declined by ${info.alias}")
                    }
                    return
                }
                TransferClient.PrepareResult.Busy ->
                    throw TransferException("${info.alias} is busy with another transfer.")
                is TransferClient.PrepareResult.Failed -> throw TransferException(result.message)
            }
            sessionId = response.sessionId

            val alreadyThere = metas.sumOf { response.offsets[it.id] ?: 0L }
            val progress = ProgressTracker(transferId, alreadyThere)
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.IN_PROGRESS, bytesTransferred = alreadyThere, pairingCode = null)
            }
            metas.forEachIndexed { i, meta ->
                val token = response.tokens[meta.id] ?: throw TransferException("Receiver did not accept ${meta.name}")
                val offset = response.offsets[meta.id] ?: 0L
                updateTransfer(transferId) { it.copy(currentFile = meta.name) }
                client.upload(reachableHost, peerPort, pin, response.sessionId, meta.id, token, handles[i], offset, progress::add)
            }
            progress.flush()
            updateTransfer(transferId) {
                it.copy(
                    status = TransferStatus.COMPLETED,
                    bytesTransferred = it.totalBytes,
                    currentFile = null,
                    bytesPerSecond = 0,
                    message = if (metas.isEmpty()) "Message delivered" else null,
                )
            }
            stagedHandles.update { current -> current.filterNot { it in handles } }
            if (text != null && _stagedText.value.trim() == text) _stagedText.value = ""
        } catch (e: InterruptedTransfer) {
            // Not a user cancel: the receiver is left alone so it keeps its partial files for resume.
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.FAILED, currentFile = null, bytesPerSecond = 0, pairingCode = null, message = e.message)
            }
            throw e
        } catch (e: CancellationException) {
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.CANCELLED, currentFile = null, bytesPerSecond = 0, pairingCode = null, message = "Cancelled")
            }
            val h = host
            val s = sessionId
            if (h != null && s != null) {
                withContext(NonCancellable) { runCatching { client.cancel(h, peerPort, pin, s) } }
            }
            throw e
        } catch (e: Exception) {
            log("Send failed: $e")
            updateTransfer(transferId) {
                it.copy(status = TransferStatus.FAILED, currentFile = null, bytesPerSecond = 0, pairingCode = null, message = e.friendlyMessage())
            }
        }
    }

    /**
     * Tries every address at once and returns the first that presents the pinned key (and, when given,
     * claims [deviceId]). Racing avoids guessing which interface is reachable when VPNs or multiple
     * adapters are involved.
     */
    private suspend fun firstReachable(
        addresses: List<String>,
        port: Int,
        pin: String,
        deviceId: String? = null,
    ): Pair<String, DeviceInfo>? = coroutineScope {
        val result = CompletableDeferred<Pair<String, DeviceInfo>?>()
        val attempts = addresses.map { address ->
            launch {
                val info = runCatching { client.info(address, port, pin = pin) }
                    .onFailure { if (it !is CancellationException) log("$address:$port unreachable: $it") }
                    .getOrNull()
                if (info != null && (deviceId == null || info.deviceId == deviceId)) result.complete(address to info)
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
        deviceId = appSettings.deviceId,
        alias = appSettings.alias,
        deviceType = deviceType,
        port = port,
        fingerprint = identity.fingerprint,
    )

    private fun onPeerSeen(info: DeviceInfo, address: String) {
        // Older or newer protocol versions cannot complete a transfer, so they are not offered as targets.
        if (info.protocolVersion != PROTOCOL_VERSION) return
        upsertPeer(info, address, PeerSource.DISCOVERED)
    }

    /**
     * Every caller has already confirmed over TLS that the device at [address] holds the key in [info].
     * Device ids are only claims, though, so a second key claiming a listed id does not replace the listed
     * device while it is still active, unless the newcomer is the key the user trusted. Otherwise anyone
     * could flag a trusted device as "key changed" and block sending to it.
     */
    private fun upsertPeer(rawInfo: DeviceInfo, address: String, source: PeerSource) {
        if (rawInfo.deviceId == appSettings.deviceId) return
        val info = rawInfo.copy(alias = sanitizeAlias(rawInfo.alias))
        val now = System.currentTimeMillis()
        _peers.update { peers ->
            val existing = peers.find { it.info.deviceId == info.deviceId }
            val keyConflict = existing != null && !existing.info.fingerprint.equals(info.fingerprint, ignoreCase = true)
            if (keyConflict) {
                val newcomerIsTrusted = trustStore.find(info.deviceId)?.fingerprint.equals(info.fingerprint, ignoreCase = true)
                val existingActive = now - existing!!.lastSeenMillis < PEER_EXPIRY_MILLIS
                if (!newcomerIsTrusted && existingActive) return@update peers
            }
            val knownAddresses = if (keyConflict) emptyList() else existing?.addresses.orEmpty()
            val updated = withTrust(
                Peer(
                    info = info,
                    addresses = (listOf(address) + knownAddresses).distinct().take(MAX_PEER_ADDRESSES),
                    source = if (existing?.source == PeerSource.MANUAL) PeerSource.MANUAL else source,
                    lastSeenMillis = now,
                ),
            )
            (peers.filterNot { it.info.deviceId == info.deviceId } + updated).sortedBy { it.info.alias.lowercase() }
        }
    }

    /** Applies the trust store; a trusted id claiming a different key is flagged, never silently re-pinned. */
    private fun withTrust(peer: Peer): Peer {
        val trusted = trustStore.find(peer.info.deviceId)
        return peer.copy(
            trusted = trusted != null && trusted.fingerprint.equals(peer.info.fingerprint, ignoreCase = true),
            identityChanged = trusted != null && !trusted.fingerprint.equals(peer.info.fingerprint, ignoreCase = true),
        )
    }

    /**
     * A UDP announcement is unauthenticated, so the peer is only listed once a TLS exchange pinned to the
     * key it announced succeeds at that address: registering ourselves when it asked for a reply, or
     * fetching its info when the packet was itself a reply.
     */
    private suspend fun onAnnouncement(info: DeviceInfo, address: String, replyRequested: Boolean): Boolean {
        // Other protocol versions are never listed, so connecting to them would be wasted effort.
        if (info.protocolVersion != PROTOCOL_VERSION) return true
        return runCatching {
            // Registering tells the announcer about this device, which a hidden device must not do.
            val confirmed = if (replyRequested && appSettings.discoverable) {
                client.register(address, info.port, info.fingerprint, selfInfo())
            } else {
                client.info(address, info.port, pin = info.fingerprint, quick = true)
            }
            onPeerSeen(confirmed, address)
        }.isSuccess
    }

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

    private class Decision(val accept: Boolean, val trust: Boolean)

    private class PendingOffer(val decision: CompletableDeferred<Decision>)

    /** An open pairing QR code. [claimed] is set by the first caller with the right token; later ones are refused. */
    private class Invite(val token: String, val expiresAtMillis: Long) {
        var claimed = false
        val decision = CompletableDeferred<Boolean>()
    }

    /** A pairing exchange awaiting its offer: the caller's key, its commitment, and the nonce we answered with. */
    private class PendingPairing(
        val fingerprint: String,
        val commitment: String,
        val receiverNonce: String,
        val createdAtMillis: Long,
    )

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
        /** Uploads and cancels are only honored from connections presenting this key. */
        val senderFingerprint: String,
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

        /** The claim is only accepted when it names the key the caller actually presented. */
        override fun onRegister(peer: DeviceInfo, caller: Caller) {
            if (peer.fingerprint.equals(caller.fingerprint, ignoreCase = true)) onPeerSeen(peer, caller.address)
        }

        override fun onPair(request: PairRequest, caller: Caller): PairOutcome {
            if (!request.commitment.matches(SHA256_HEX)) return PairOutcome.Invalid("Malformed commitment")
            val now = System.currentTimeMillis()
            // Each attempt gives an attacker in the middle one blind guess at the code, so attempts are scarce.
            if (!pairingAttempts.tryAcquire(caller.fingerprint, now)) return PairOutcome.RateLimited
            pendingPairings.values.removeIf { now - it.createdAtMillis > PAIRING_TTL_MILLIS }
            if (pendingPairings.size >= MAX_PENDING_PAIRINGS) return PairOutcome.RateLimited
            val id = newId()
            val nonce = newPairingNonce(random)
            pendingPairings[id] = PendingPairing(caller.fingerprint, request.commitment.lowercase(), nonce, now)
            return PairOutcome.Started(PairResponse(id, nonce))
        }

        override suspend fun onQrPair(request: QrPairRequest, caller: Caller): QrPairOutcome {
            if (request.sender.protocolVersion != PROTOCOL_VERSION) return QrPairOutcome.Invalid("Incompatible version")
            if (!request.sender.fingerprint.equals(caller.fingerprint, ignoreCase = true)) {
                return QrPairOutcome.Forbidden("This request was not sent by the device it names.")
            }
            val now = System.currentTimeMillis()
            if (!pairingAttempts.tryAcquire(caller.fingerprint, now)) return QrPairOutcome.RateLimited
            val claimed = synchronized(inviteLock) {
                invite?.takeIf {
                    !it.claimed && now < it.expiresAtMillis &&
                        MessageDigest.isEqual(it.token.toByteArray(), request.token.toByteArray())
                }?.also { it.claimed = true }
            } ?: return QrPairOutcome.Invalid("This pairing code has expired or was already used. Show a new one and scan again.")

            val sender = request.sender.copy(alias = sanitizeAlias(request.sender.alias), fingerprint = caller.fingerprint)
            _pairingInvite.update { it?.copy(request = PairingRequest(sender.alias, sender.deviceType, sender.fingerprint)) }
            val accepted = withTimeoutOrNull(PROMPT_TIMEOUT_MILLIS) { claimed.decision.await() } ?: false
            synchronized(inviteLock) {
                if (invite === claimed) {
                    invite = null
                    inviteExpiryJob?.cancel()
                    inviteExpiryJob = null
                    _pairingInvite.value = null
                }
            }
            if (!accepted) return QrPairOutcome.Declined
            // Scanning the code proves the caller saw this screen; the key is the one it just presented.
            trustStore.add(TrustedDevice(sender.deviceId, sender.alias, caller.fingerprint, System.currentTimeMillis()))
            onTrustChanged()
            onPeerSeen(sender, caller.address)
            notice("Paired with ${sender.alias}")
            return QrPairOutcome.Paired(selfInfo())
        }

        override suspend fun onPrepare(request: PrepareRequest, caller: Caller): PrepareOutcome {
            val text = request.text?.takeIf { it.isNotBlank() }
            if (request.files.isEmpty() && text == null) return PrepareOutcome.Invalid("Nothing offered")
            if (text != null && text.length > MAX_TEXT_LENGTH) return PrepareOutcome.Invalid("Message too long")
            if (request.files.any { it.size < 0 }) return PrepareOutcome.Invalid("Negative file size")
            if (request.files.distinctBy { it.id }.size != request.files.size) return PrepareOutcome.Invalid("Duplicate file ids")
            if (request.sender.protocolVersion != PROTOCOL_VERSION) return PrepareOutcome.Invalid("Incompatible version")
            // The TLS handshake proved which key the caller holds; the claimed identity must be that key.
            if (!request.sender.fingerprint.equals(caller.fingerprint, ignoreCase = true)) {
                return PrepareOutcome.Forbidden("This offer was not sent by the device it names.")
            }
            val sender = request.sender.copy(alias = sanitizeAlias(request.sender.alias), fingerprint = caller.fingerprint)

            // Single use: the pairing is consumed whether or not the offer goes ahead.
            val pairing = pendingPairings.remove(request.pairingId)
            val pairingValid = pairing != null &&
                pairing.fingerprint == caller.fingerprint &&
                System.currentTimeMillis() - pairing.createdAtMillis <= PAIRING_TTL_MILLIS &&
                MessageDigest.isEqual(
                    pairing.commitment.toByteArray(),
                    pairingCommitment(caller.fingerprint, request.nonce).toByteArray(),
                )
            if (!pairingValid) return PrepareOutcome.Invalid("Pairing failed. Send again.")
            val code = pairingCode(caller.fingerprint, identity.fingerprint, request.nonce, pairing!!.receiverNonce)

            val pending = PendingOffer(CompletableDeferred())
            synchronized(receiveLock) {
                if (pendingOffer != null || activeSession != null) return PrepareOutcome.Busy
                pendingOffer = pending
            }
            try {
                onPeerSeen(sender, caller.address)
                val trustedRecord = trustStore.find(sender.deviceId)
                val identityChanged = trustedRecord != null && !trustedRecord.fingerprint.equals(sender.fingerprint, ignoreCase = true)
                val trusted = trustedRecord != null && !identityChanged

                val files = request.files.map { meta ->
                    val safe = sanitizeFileName(meta.name)
                    ReceiveFile(meta, safe, partialFileName(sender.fingerprint, safe, meta.size), newToken())
                }
                // A trusted device can still be compromised, so programs always need a person to accept them.
                val hasPrograms = files.any { looksExecutable(it.safeName) }
                val decision = if (trusted && _settings.value.autoAcceptTrusted && !hasPrograms) {
                    Decision(accept = true, trust = false)
                } else {
                    _incomingOffer.value = IncomingOffer(
                        id = newId(),
                        sender = sender,
                        senderAddress = caller.address,
                        files = files.map { FileSummary(it.safeName, it.meta.size) },
                        text = text,
                        trusted = trusted,
                        identityChanged = identityChanged,
                        pairingCode = code,
                    )
                    withTimeoutOrNull(PROMPT_TIMEOUT_MILLIS) { pending.decision.await() } ?: Decision(false, false)
                }
                _incomingOffer.value = null
                if (!decision.accept) return PrepareOutcome.Declined
                if (decision.trust) {
                    trustStore.add(TrustedDevice(sender.deviceId, sender.alias, sender.fingerprint, System.currentTimeMillis()))
                    onTrustChanged()
                }
                return accept(sender, caller, files, text)
            } finally {
                _incomingOffer.value = null
                synchronized(receiveLock) { if (pendingOffer === pending) pendingOffer = null }
            }
        }

        private fun accept(sender: DeviceInfo, caller: Caller, files: List<ReceiveFile>, text: String?): PrepareOutcome {
            val transferId = newId()
            val summaries = files.map { FileSummary(it.safeName, it.meta.size) }
            if (files.isEmpty()) {
                addTransfer(
                    TransferState(
                        id = transferId,
                        direction = TransferDirection.RECEIVE,
                        peerAlias = sender.alias,
                        files = emptyList(),
                        totalBytes = 0,
                        text = text,
                        status = TransferStatus.COMPLETED,
                        message = "Message received",
                    ),
                )
                return PrepareOutcome.Accepted(PrepareResponse(newId(), emptyMap(), emptyMap()))
            }

            // Nothing touches the destination until the user has accepted.
            val folder = destination
            val existing = runCatching { folder.existingSizes(files.map { it.partialName }) }
                .onFailure { log("Could not inspect destination: $it") }
                .getOrDefault(emptyMap())
            val offsets = files.associate { f ->
                f.meta.id to (existing[f.partialName]?.takeIf { it <= f.meta.size } ?: 0L)
            }
            val alreadyThere = offsets.values.sum()
            addTransfer(
                TransferState(
                    id = transferId,
                    direction = TransferDirection.RECEIVE,
                    peerAlias = sender.alias,
                    files = summaries,
                    totalBytes = files.sumOf { it.meta.size },
                    text = text,
                    bytesTransferred = alreadyThere,
                    status = TransferStatus.IN_PROGRESS,
                ),
            )
            val session = ReceiveSession(
                id = newId(),
                transferId = transferId,
                senderFingerprint = caller.fingerprint,
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
        }

        override fun onUpload(
            sessionId: String,
            fileId: String,
            token: String,
            offset: Long,
            caller: Caller,
            body: InputStream,
        ): UploadOutcome {
            val session = synchronized(receiveLock) { activeSession }?.takeIf { it.id == sessionId }
                ?: return UploadOutcome.Rejected(404, "The receiver ended this transfer")
            // The session belongs to the key that was offered and accepted; tokens are a second check on top.
            if (session.senderFingerprint != caller.fingerprint) return UploadOutcome.Rejected(403, "Wrong sender")
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
                session.destination.openPartial(file.partialName, offset).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var remaining = file.meta.size - offset
                    while (remaining > 0) {
                        if (session.ended) throw TransferException("Cancelled")
                        val read = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw TransferException("Connection closed with $remaining bytes left")
                        if (read == 0) continue
                        out.write(buffer, 0, read)
                        remaining -= read
                        session.lastActivity = System.currentTimeMillis()
                        session.progress.add(read.toLong())
                    }
                }
                session.destination.commit(file.partialName, file.safeName)
                file.done = true
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

        override fun onCancel(sessionId: String, caller: Caller) {
            val session = synchronized(receiveLock) { activeSession }
                ?.takeIf { it.id == sessionId && it.senderFingerprint == caller.fingerprint } ?: return
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
            state.copy(status = status, message = message, currentFile = null, bytesTransferred = bytes, bytesPerSecond = 0)
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

    /** Coalesces per-buffer progress into a few StateFlow updates per second, with a smoothed speed. */
    private inner class ProgressTracker(private val transferId: String, start: Long) {
        @Volatile private var bytes = start
        private var lastEmitNanos = System.nanoTime()
        private var lastEmitBytes = start
        private var speed = 0.0

        fun add(count: Long) {
            bytes += count
            val now = System.nanoTime()
            val elapsed = now - lastEmitNanos
            if (elapsed > PROGRESS_INTERVAL_NANOS) {
                val instant = (bytes - lastEmitBytes) * 1_000_000_000.0 / elapsed
                speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                lastEmitNanos = now
                lastEmitBytes = bytes
                flush()
            }
        }

        fun flush() {
            val current = bytes
            val rate = speed.toLong()
            updateTransfer(transferId) { it.copy(bytesTransferred = current, bytesPerSecond = rate) }
        }
    }

    private fun addTransfer(state: TransferState) {
        _transfers.update { listOf(state) + it }
        if (!state.isActive) recordHistory(state)
    }

    private fun updateTransfer(id: String, transform: (TransferState) -> TransferState) {
        var finished: TransferState? = null
        _transfers.update { list ->
            finished = null
            list.map { old ->
                if (old.id != id) return@map old
                transform(old).also { new -> if (old.isActive && !new.isActive) finished = new }
            }
        }
        finished?.let(::recordHistory)
    }

    private fun recordHistory(state: TransferState) {
        val now = System.currentTimeMillis()
        val entry = HistoryEntry(
            id = state.id,
            direction = state.direction,
            peerAlias = state.peerAlias,
            files = state.files.take(HistoryStore.MAX_FILES_PER_ENTRY),
            fileCount = state.files.size,
            totalBytes = state.totalBytes,
            text = state.text,
            status = state.status,
            finishedAtMillis = now,
            finishedAtLabel = timestampFormatter.format(Instant.ofEpochMilli(now)),
            location = if (state.direction == TransferDirection.RECEIVE && state.files.isNotEmpty()) destination.label else null,
            message = state.message,
        )
        val updated = _history.updateAndGet { (listOf(entry) + it).take(HistoryStore.MAX_ENTRIES) }
        scope.launch(historyWriter) { historyStore.save(updated) }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun newToken(): String = ByteArray(16).also(random::nextBytes).toHex()

    private companion object {
        const val TICK_MILLIS = 5_000L
        const val ANNOUNCE_EVERY_TICKS = 6
        const val PEER_EXPIRY_MILLIS = 90_000L
        const val RECEIVE_IDLE_TIMEOUT_MILLIS = 60_000L
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        const val MAX_PEER_ADDRESSES = 4
        const val MAX_SWEEP_SUBNETS = 3
        const val SWEEP_CONCURRENCY = 48

        // An attacker in the middle must match a code the sender is showing, which lasts one prompt (two
        // minutes), so these caps leave it a few dozen one-in-a-million guesses at most.
        const val PAIRINGS_PER_MINUTE = 20
        const val PAIRINGS_PER_MINUTE_PER_DEVICE = 6
        // The sender sends its offer immediately after pairing, so a pairing older than this was abandoned.
        const val PAIRING_TTL_MILLIS = 30_000L
        const val MAX_PENDING_PAIRINGS = 16
        val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

        // Short enough that a photo of the code is soon useless, long enough to walk over with a phone.
        const val QR_INVITE_TTL_MILLIS = 5 * 60_000L
        // Each address lengthens the code; beyond a few it gets too dense to scan off a laptop screen.
        const val MAX_QR_ADDRESSES = 4

        val timestampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    }
}

private class InterruptedTransfer(reason: String) : CancellationException(reason)

/**
 * Sliding-window limit on attempts, overall and per key. The overall limit is checked first, so the
 * per-key table never holds more entries than [total] however many keys an attacker generates.
 */
internal class AttemptLimiter(private val total: Int, private val perKey: Int, private val windowMillis: Long) {
    private val all = ArrayDeque<Long>()
    private val byKey = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(key: String, nowMillis: Long): Boolean {
        val cutoff = nowMillis - windowMillis
        while (all.isNotEmpty() && all.first() <= cutoff) all.removeFirst()
        byKey.values.forEach { times -> while (times.isNotEmpty() && times.first() <= cutoff) times.removeFirst() }
        byKey.values.removeAll { it.isEmpty() }
        if (all.size >= total) return false
        val mine = byKey.getOrPut(key) { ArrayDeque() }
        if (mine.size >= perKey) return false
        all.addLast(nowMillis)
        mine.addLast(nowMillis)
        return true
    }
}

internal fun Throwable.friendlyMessage(): String = when (this) {
    is TransferException -> message ?: "Transfer failed"
    is PinMismatchException -> message!!
    is java.net.ConnectException -> "Connection refused or blocked by a firewall."
    is java.net.SocketTimeoutException -> "Timed out. The device may be unreachable or blocked by a firewall."
    is java.net.UnknownHostException -> "Unknown address."
    is javax.net.ssl.SSLException -> "Secure connection failed: ${message ?: "handshake error"}"
    else -> message ?: this::class.simpleName ?: "Unknown error"
}
