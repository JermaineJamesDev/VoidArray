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

/**
 * UDP discovery over link-local multicast with directed broadcast as a second channel, since some
 * access points drop one or the other. Peers are identified by the packet's source address, which is
 * the address that actually routes back to them, rather than by anything they claim in the payload.
 */
internal class DiscoveryService(
    private val scope: CoroutineScope,
    private val selfInfo: () -> DeviceInfo,
    private val onPeerSeen: (DeviceInfo, String) -> Unit,
    /** Replies to an announcement over TCP; returns false so the caller can fall back to UDP. */
    private val replyViaHttp: suspend (DeviceInfo, String) -> Boolean,
    private val log: (String) -> Unit,
) {
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

            val sender = packet.address
            val senderHost = sender.hostAddress ?: continue
            onPeerSeen(announcement.info, senderHost)
            if (announcement.announce) {
                scope.launch {
                    if (!replyViaHttp(announcement.info, senderHost)) replyViaUdp(sender)
                }
            }
        }
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
}
