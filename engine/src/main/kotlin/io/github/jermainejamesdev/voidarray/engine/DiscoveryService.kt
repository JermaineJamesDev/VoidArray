package io.github.jermainejamesdev.voidarray.engine

import io.github.jermainejamesdev.voidarray.protocol.Announcement
import io.github.jermainejamesdev.voidarray.protocol.DEFAULT_PORT
import io.github.jermainejamesdev.voidarray.protocol.DeviceInfo
import io.github.jermainejamesdev.voidarray.protocol.MULTICAST_GROUP
import io.github.jermainejamesdev.voidarray.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * UDP discovery over link-local multicast with directed broadcast as a second channel, since some
 * access points drop one or the other. Peers are identified by the packet's source address, which is
 * the address that actually routes back to them, rather than by anything they claim in the payload.
 *
 * A UDP payload is only a hint: anyone can send one with any source address. Nothing here adds a peer;
 * [onAnnouncement] must confirm it over pinned TLS first. Because each announcement costs an outbound
 * connection to an address and port the packet chose, packets are filtered and rate limited before that.
 */
internal class DiscoveryService(
    private val scope: CoroutineScope,
    private val selfInfo: () -> DeviceInfo,
    /**
     * Verifies [DeviceInfo] at the address over TLS pinned to its claimed key, and replies over TCP when
     * the sender asked for a reply. Returns false when the peer could not be reached, so a requested reply
     * falls back to UDP.
     */
    private val onAnnouncement: suspend (info: DeviceInfo, address: String, replyRequested: Boolean) -> Boolean,
    /**
     * When false this device sends nothing, not even replies, but still listens so it can list others.
     * [onAnnouncement] is expected to verify without registering itself in that case.
     */
    private val visible: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private val lastHandled = ConcurrentHashMap<String, Long>()
    private val verifications = Semaphore(MAX_CONCURRENT_VERIFICATIONS)
    private val group: InetAddress = InetAddress.getByName(MULTICAST_GROUP)
    private val groupAddress = InetSocketAddress(group, 0)
    private var socket: MulticastSocket? = null
    private var receiveJob: Job? = null
    private val joined = mutableMapOf<String, NetworkInterface>()
    private var lastAddressSnapshot: Set<String> = emptySet()

    @Synchronized
    fun start() {
        if (socket != null) return
        val s = MulticastSocket(null as java.net.SocketAddress?).apply {
            // Lets a second instance (or another app using the port) coexist instead of failing to bind.
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(DEFAULT_PORT))
        }
        socket = s
        refreshMemberships()
        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop(s) }
    }

    @Synchronized
    fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        socket?.close()
        socket = null
        joined.clear()
        lastAddressSnapshot = emptySet()
    }

    /**
     * Re-joins the group on the current set of interfaces. Returns true when the set of local addresses
     * changed (Wi-Fi switched, hotspot started, VPN came up) so the caller can announce again.
     */
    @Synchronized
    fun refreshMemberships(): Boolean {
        val s = socket ?: return false
        val addresses = localIpv4Addresses()
        val snapshot = addresses.map { "${it.networkInterface.name}/${it.address.hostAddress}" }.toSet()
        if (snapshot == lastAddressSnapshot) return false
        lastAddressSnapshot = snapshot

        val current = addresses.map { it.networkInterface }
            .filter { it.supportsMulticastSafely() }
            .associateBy { it.name }
        for ((name, ni) in joined.toMap()) {
            runCatching { s.leaveGroup(groupAddress, ni) }
            joined.remove(name)
        }
        for ((name, ni) in current) {
            runCatching { s.joinGroup(groupAddress, ni) }
                .onSuccess { joined[name] = ni }
                .onFailure { log("Could not join multicast on $name: ${it.message}") }
        }
        return true
    }

    /** Sends an announcement out of every interface, by multicast and by directed broadcast. */
    fun announce() {
        if (!visible()) return
        val payload = encode(Announcement(selfInfo(), announce = true))
        synchronized(this) {
            val s = socket ?: return
            for (local in localIpv4Addresses()) {
                if (local.networkInterface.supportsMulticastSafely()) {
                    runCatching {
                        s.networkInterface = local.networkInterface
                        s.send(DatagramPacket(payload, payload.size, group, DEFAULT_PORT))
                    }.onFailure { log("Multicast send on ${local.networkInterface.name} failed: ${it.message}") }
                }
                local.broadcast?.let { broadcast ->
                    runCatching { s.send(DatagramPacket(payload, payload.size, broadcast, DEFAULT_PORT)) }
                        .onFailure { log("Broadcast to $broadcast failed: ${it.message}") }
                }
            }
        }
    }

    private suspend fun receiveLoop(s: MulticastSocket) {
        val buffer = ByteArray(8 * 1024)
        while (scope.isActive && !s.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: SocketException) {
                break
            }
            val announcement = runCatching {
                ProtocolJson.decodeFromString(Announcement.serializer(), String(packet.data, 0, packet.length))
            }.getOrNull() ?: continue
            val self = selfInfo()
            if (announcement.info.deviceId == self.deviceId) continue
            if (announcement.info.port !in 1..65535) continue

            val sender = packet.address
            if (!isLocalNetworkPeer(sender)) continue
            val senderHost = sender.hostAddress ?: continue
            if (!claimSlot(senderHost)) continue
            if (!verifications.tryAcquire()) continue
            scope.launch {
                try {
                    val reached = onAnnouncement(announcement.info, senderHost, announcement.announce)
                    if (announcement.announce && !reached && visible()) replyViaUdp(sender)
                } finally {
                    verifications.release()
                }
            }
        }
    }

    /** At most one announcement per source address per [MIN_INTERVAL_MILLIS]; repeats are dropped. */
    private fun claimSlot(address: String): Boolean {
        val now = System.currentTimeMillis()
        if (lastHandled.size > MAX_TRACKED_ADDRESSES) {
            lastHandled.entries.removeIf { now - it.value > MIN_INTERVAL_MILLIS }
        }
        var claimed = false
        lastHandled.compute(address) { _, last ->
            if (last != null && now - last < MIN_INTERVAL_MILLIS) last else now.also { claimed = true }
        }
        return claimed
    }

    private fun replyViaUdp(target: InetAddress) {
        val payload = encode(Announcement(selfInfo(), announce = false))
        synchronized(this) {
            runCatching { socket?.send(DatagramPacket(payload, payload.size, target, DEFAULT_PORT)) }
                .onFailure { log("UDP reply to $target failed: ${it.message}") }
        }
    }

    private fun encode(announcement: Announcement): ByteArray =
        ProtocolJson.encodeToString(Announcement.serializer(), announcement).toByteArray()

    private companion object {
        // Multicast and broadcast deliver each announcement twice; the second copy lands well inside this.
        const val MIN_INTERVAL_MILLIS = 3_000L
        const val MAX_TRACKED_ADDRESSES = 512
        const val MAX_CONCURRENT_VERIFICATIONS = 8
    }
}
