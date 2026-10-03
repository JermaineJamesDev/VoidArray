package io.github.jermainejamesdev.voidarray.engine

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal data class LocalAddress(
    val networkInterface: NetworkInterface,
    val address: Inet4Address,
    val broadcast: InetAddress?,
    val prefixLength: Int,
    val likelyVirtual: Boolean,
)

private val virtualNameHints = listOf(
    "tun", "tap", "wg", "ppp", "ipsec", "utun", "vpn", "virtual", "vmware", "vbox", "virtualbox",
    "hyper-v", "vethernet", "docker", "zerotier", "tailscale", "wsl", "loopback", "pseudo",
)

/**
 * Every usable IPv4 address on an up, non-loopback interface. Nothing is dropped for looking like a VPN
 * because guessing wrong breaks discovery entirely; virtual adapters are only sorted last.
 */
internal fun localIpv4Addresses(): List<LocalAddress> {
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
    return interfaces
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { ni ->
            val virtual = runCatching { ni.isVirtual }.getOrDefault(false) || looksVirtual(ni)
            ni.interfaceAddresses.mapNotNull { ia ->
                val address = ia.address as? Inet4Address ?: return@mapNotNull null
                // 169.254/16 means DHCP failed; nothing useful is reachable there.
                if (address.isLinkLocalAddress) return@mapNotNull null
                LocalAddress(ni, address, ia.broadcast, ia.networkPrefixLength.toInt(), virtual)
            }
        }
        .sortedBy { it.likelyVirtual }
}

private fun looksVirtual(ni: NetworkInterface): Boolean {
    val text = (ni.name + " " + (ni.displayName ?: "")).lowercase()
    return virtualNameHints.any { it in text }
}

/**
 * Whether traffic from [address] can only have come from a network this device is attached to. Private,
 * loopback, link-local, carrier-grade NAT (also used by Tailscale) and IPv6 unique-local ranges are never
 * routed from the internet. Anything else, such as a LAN numbered from public space or a global IPv6
 * address, counts only when it shares a subnet with one of this device's own addresses. Without this, a
 * phone or PC with a public IPv6 address and no inbound filtering would accept offers from the internet.
 */
internal fun isLocalNetworkPeer(address: InetAddress): Boolean {
    val bytes = address.address
    val ipv4 = when {
        bytes.size == 4 -> bytes
        isIpv4Mapped(bytes) -> bytes.copyOfRange(12, 16)
        else -> null
    }
    if (ipv4 != null) {
        val a = ipv4[0].toInt() and 0xff
        val b = ipv4[1].toInt() and 0xff
        val privateRange = a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
        if (privateRange) return true
    } else {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return true
        if ((bytes[0].toInt() and 0xfe) == 0xfc) return true
    }
    return sharesSubnetWithThisDevice(ipv4 ?: bytes)
}

private fun isIpv4Mapped(bytes: ByteArray): Boolean =
    bytes.size == 16 && (0 until 10).all { bytes[it] == 0.toByte() } &&
        bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()

private fun sharesSubnetWithThisDevice(remote: ByteArray): Boolean {
    // Very short prefixes come from VPN default routes and would make "on-link" mean "anywhere".
    val minimumPrefix = if (remote.size == 4) 8 else 48
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
    return interfaces.any { ni ->
        ni.interfaceAddresses.any { ia ->
            val local = ia.address?.address ?: return@any false
            val prefix = ia.networkPrefixLength.toInt()
            local.size == remote.size && prefix >= minimumPrefix && prefix <= local.size * 8 &&
                samePrefix(local, remote, prefix)
        }
    }
}

private fun samePrefix(a: ByteArray, b: ByteArray, bits: Int): Boolean {
    val fullBytes = bits / 8
    for (i in 0 until fullBytes) if (a[i] != b[i]) return false
    val remainder = bits % 8
    if (remainder == 0) return true
    val mask = (0xff shl (8 - remainder)) and 0xff
    return (a[fullBytes].toInt() and mask) == (b[fullBytes].toInt() and mask)
}

internal fun NetworkInterface.supportsMulticastSafely(): Boolean =
    runCatching { supportsMulticast() }.getOrDefault(false)
