package com.yunjam.eztransfer.engine

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

internal fun NetworkInterface.supportsMulticastSafely(): Boolean =
    runCatching { supportsMulticast() }.getOrDefault(false)
