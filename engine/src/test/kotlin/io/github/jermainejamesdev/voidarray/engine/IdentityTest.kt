package io.github.jermainejamesdev.voidarray.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
            val first = DeviceIdentity.loadOrCreate(file, store)
            val second = DeviceIdentity.loadOrCreate(file, store)
            assertEquals(first.fingerprint, second.fingerprint)
            assertTrue(first.fingerprint.matches(Regex("[0-9A-F]{64}")))

            val other = DeviceIdentity.loadOrCreate(File(dir, "other.p12"), MemoryStore())
            assertNotEquals(first.fingerprint, other.fingerprint)
        } finally {
            dir.deleteRecursively()
        }
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
