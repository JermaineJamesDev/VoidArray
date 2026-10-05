package io.github.jermainejamesdev.voidarray.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityTest {
    private class MemoryStore : KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test
    fun identityPersistsAcrossLoads() {
        val dir = Files.createTempDirectory("voidarray-identity").toFile()
        try {
            val store = MemoryStore()
            val file = File(dir, "identity.p12")
            val first = FileIdentityStore(file, store).load()
            val second = FileIdentityStore(file, store).load()
            assertEquals(first.fingerprint, second.fingerprint)
            assertTrue(first.fingerprint.matches(Regex("[0-9A-F]{64}")))

            val other = FileIdentityStore(File(dir, "other.p12"), MemoryStore()).load()
            assertNotEquals(first.fingerprint, other.fingerprint)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun replaceSwapsTheStoredKeyAndDeleteRemovesIt() {
        val dir = Files.createTempDirectory("voidarray-identity").toFile()
        try {
            val store = MemoryStore()
            val identities = FileIdentityStore(File(dir, "identity.p12"), store)
            val original = identities.load()
            val replaced = identities.replace()
            assertNotEquals(original.fingerprint, replaced.fingerprint)
            assertEquals(replaced.fingerprint, FileIdentityStore(File(dir, "identity.p12"), store).load().fingerprint)

            identities.delete()
            assertNull(identities.readExisting())
            assertNull(store.get("identityPassword"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pairingLinkRoundTripsAndRejectsUnsafeTargets() {
        val fingerprint = "AB".repeat(32)
        val token = "0f".repeat(16)
        val link = PairingLink(fingerprint, 53318, listOf("192.168.1.20", "10.0.0.5"), token)
        assertEquals(link, PairingLink.parse(link.toUri()))
        assertEquals(link, PairingLink.parse("  ${link.toUri().replace(fingerprint, fingerprint.lowercase())}\n"))

        fun withAddresses(addresses: String) = "voidarray://pair?v=1&k=$fingerprint&p=53318&a=$addresses&t=$token"
        // Host names would be resolved over DNS, and public addresses are outside the local network.
        assertNull(PairingLink.parse(withAddresses("example.com")))
        assertNull(PairingLink.parse(withAddresses("8.8.8.8")))
        assertEquals(listOf("192.168.1.20"), PairingLink.parse(withAddresses("8.8.8.8,192.168.1.20"))?.addresses)

        assertNull(PairingLink.parse("https://example.com/?k=$fingerprint"))
        assertNull(PairingLink.parse(withAddresses("192.168.1.20").replace("v=1", "v=2")))
        assertNull(PairingLink.parse(withAddresses("192.168.1.20").replace("p=53318", "p=70000")))
        assertNull(PairingLink.parse(withAddresses("192.168.1.20").replace(token, "short")))
        assertNull(PairingLink.parse(withAddresses("192.168.1.20").replace(fingerprint, "AB")))
    }

    @Test
    fun qrMatrixIsSquareWithFinderPatterns() {
        val qr = encodeQr(PairingLink("AB".repeat(32), 53318, listOf("192.168.1.20"), "0f".repeat(16)).toUri())
        // Finder patterns: the corners are dark and the module just inside the outer ring is light.
        for ((x, y) in listOf(0 to 0, qr.size - 1 to 0, 0 to qr.size - 1)) assertTrue(qr.isDark(x, y))
        assertFalse(qr.isDark(1, 1))
        assertTrue(qr.size >= 21 && (qr.size - 17) % 4 == 0, "not a valid QR size: ${qr.size}")
    }

    @Test
    fun pairingCodeCoversBothKeysAndBothNonces() {
        val sender = "A".repeat(64)
        val receiver = "B".repeat(64)
        val code = pairingCode(sender, receiver, "11", "22")
        assertTrue(code.matches(Regex("""\d{3} \d{3}""")))
        assertEquals(code, pairingCode(sender.lowercase(), receiver, "11", "22"))
        // Roles are explicit, so swapping sender and receiver is a different exchange.
        val variants = listOf(
            pairingCode(receiver, sender, "11", "22"),
            pairingCode(sender, "C".repeat(64), "11", "22"),
            pairingCode(sender, receiver, "12", "22"),
            pairingCode(sender, receiver, "11", "23"),
        )
        assertTrue(variants.all { it != code }, "$code vs $variants")
    }

    @Test
    fun commitmentBindsFingerprintAndNonce() {
        val fp = "A".repeat(64)
        val commitment = pairingCommitment(fp, "nonce")
        assertTrue(commitment.matches(Regex("[0-9a-f]{64}")))
        assertEquals(commitment, pairingCommitment(fp.lowercase(), "nonce"))
        assertNotEquals(commitment, pairingCommitment(fp, "other"))
        assertNotEquals(commitment, pairingCommitment("B".repeat(64), "nonce"))
    }

    @Test
    fun attemptLimiterCapsPerKeyAndOverall() {
        val limiter = AttemptLimiter(total = 3, perKey = 2, windowMillis = 1_000)
        assertTrue(limiter.tryAcquire("a", 0))
        assertTrue(limiter.tryAcquire("a", 1))
        assertFalse(limiter.tryAcquire("a", 2), "per-key cap")
        assertTrue(limiter.tryAcquire("b", 3))
        assertFalse(limiter.tryAcquire("c", 4), "overall cap")
        assertTrue(limiter.tryAcquire("a", 1_001), "window slides")
    }
}
