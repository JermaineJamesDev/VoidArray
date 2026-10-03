package com.yunjam.eztransfer.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val dir = Files.createTempDirectory("eztransfer-identity").toFile()
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
    fun pairingCodeIsSymmetricAndSixDigits() {
        val a = "A".repeat(64)
        val b = "B".repeat(64)
        assertEquals(pairingCode(a, b), pairingCode(b, a))
        assertTrue(pairingCode(a, b).matches(Regex("""\d{3} \d{3}""")))
        assertNotEquals(pairingCode(a, b), pairingCode(a, "C".repeat(64)))
    }
}
