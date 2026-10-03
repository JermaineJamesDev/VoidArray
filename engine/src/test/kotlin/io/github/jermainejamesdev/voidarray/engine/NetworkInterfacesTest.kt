package io.github.jermainejamesdev.voidarray.engine

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkInterfacesTest {
    private fun local(address: String) = isLocalNetworkPeer(InetAddress.getByName(address))

    @Test
    fun acceptsAddressesThatCannotComeFromTheInternet() {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.9", "172.31.255.1", "192.168.1.40", "169.254.3.3", "100.100.1.1",
            "::1", "fe80::1", "fd12:3456::1", "::ffff:192.168.1.40",
        ).forEach { assertTrue(local(it), it) }
    }

    @Test
    fun rejectsPublicAddressesNotOnThisDevicesSubnets() {
        // Documentation ranges (RFC 5737, RFC 3849), which no test machine's interface will be on.
        listOf("203.0.113.7", "198.51.100.1", "172.32.0.1", "2001:db8::42", "::ffff:203.0.113.7")
            .forEach { assertFalse(local(it), it) }
    }
}
