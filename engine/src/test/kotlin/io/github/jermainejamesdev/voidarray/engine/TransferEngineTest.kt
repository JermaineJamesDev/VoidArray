package io.github.jermainejamesdev.voidarray.engine

import io.github.jermainejamesdev.voidarray.core.TransferStatus
import io.github.jermainejamesdev.voidarray.protocol.DeviceType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferEngineTest {
    private lateinit var root: File
    private lateinit var sourceDir: File
    private lateinit var destDir: File
    private lateinit var sender: TransferEngine
    private lateinit var receiver: TransferEngine
    private lateinit var senderSettings: AppSettings
    private lateinit var receiverSettings: AppSettings

    private class MemoryStore : KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @BeforeTest
    fun setUp() = runBlocking {
        root = Files.createTempDirectory("voidarray-test").toFile()
        sourceDir = File(root, "source").apply { mkdirs() }
        destDir = File(root, "dest").apply { mkdirs() }
        senderSettings = AppSettings(MemoryStore(), "Sender")
        receiverSettings = AppSettings(MemoryStore(), "Receiver")
        sender = TransferEngine(
            DeviceType.DESKTOP, senderSettings, DeviceIdentity.loadOrCreate(File(root, "sender.p12"), senderSettings.store),
            LocalDestinationFolder(File(root, "unused")), log = { println("sender: $it") },
        )
        receiver = TransferEngine(
            DeviceType.MOBILE, receiverSettings, DeviceIdentity.loadOrCreate(File(root, "receiver.p12"), receiverSettings.store),
            LocalDestinationFolder(destDir), historyFile = File(root, "history.json"), log = {},
        )
        sender.start { true }
        receiver.start { true }
    }

    @AfterTest
    fun tearDown() {
        sender.close()
        receiver.close()
        root.deleteRecursively()
    }

    private fun sourceFile(name: String, size: Int): File =
        File(sourceDir, name).apply { writeBytes(Random(name.hashCode()).nextBytes(size)) }

    private suspend fun connectAndSend(
        files: List<File>,
        accept: Boolean,
        text: String? = null,
        trust: Boolean = false,
    ): TransferStatus {
        sender.addManualPeer("127.0.0.1", receiver.local.value.port!!)
        val peer = withTimeout(10_000) { sender.peers.first { it.isNotEmpty() }.single() }
        sender.stage(files.map(::LocalFileHandle))
        text?.let(sender::setStagedText)
        withTimeout(5_000) { sender.staged.first { it.size == files.size } }

        val sentBefore = sender.transfers.value.size
        return coroutineScope {
            val responder = launch {
                receiver.incomingOffer.filterNotNull().first()
                receiver.respondToOffer(accept, trust)
            }
            sender.sendStaged(sender.peers.value.single())
            val finished = withTimeout(30_000) {
                sender.transfers.first { list -> list.size > sentBefore && list.none { it.isActive } }.first()
            }
            responder.cancel()
            assertTrue(finished.status != TransferStatus.FAILED, "Transfer failed: ${finished.message}")
            finished.status
        }
    }

    @Test
    fun sendsFilesThatArriveIntact() = runBlocking {
        val files = listOf(sourceFile("photo.jpg", 3 * 1024 * 1024 + 17), sourceFile("empty.txt", 0))

        assertEquals(TransferStatus.COMPLETED, connectAndSend(files, accept = true))

        val received = withTimeout(5_000) {
            receiver.transfers.first { list -> list.any { it.status == TransferStatus.COMPLETED } }
        }
        assertEquals(1, received.size)
        for (file in files) assertContentEquals(file.readBytes(), File(destDir, file.name).readBytes())
        assertTrue(destDir.listFiles()!!.none { it.name.endsWith(".part") }, "partial files left behind")
        assertTrue(sender.staged.value.isEmpty(), "staged files should clear after a successful send")
    }

    @Test
    fun declinedOfferWritesNothing() = runBlocking {
        val status = connectAndSend(listOf(sourceFile("secret.bin", 1024)), accept = false)

        assertEquals(TransferStatus.DECLINED, status)
        assertTrue(destDir.listFiles()!!.isEmpty())
    }

    @Test
    fun resumesFromExistingPartialFile() = runBlocking {
        val file = sourceFile("video.mp4", 2 * 1024 * 1024)
        val alreadyReceived = 700_001
        val partial = File(destDir, partialFileName(senderSettings.deviceId, file.name, file.length()))
        partial.writeBytes(file.readBytes().copyOf(alreadyReceived))

        assertEquals(TransferStatus.COMPLETED, connectAndSend(listOf(file), accept = true))

        assertContentEquals(file.readBytes(), File(destDir, file.name).readBytes())
        assertFalse(partial.exists())
    }

    @Test
    fun deliversTextOnlyMessage() = runBlocking {
        assertEquals(TransferStatus.COMPLETED, connectAndSend(emptyList(), accept = true, text = "https://example.com"))

        val received = withTimeout(5_000) { receiver.transfers.first { it.isNotEmpty() } }.single()
        assertEquals("https://example.com", received.text)
        assertEquals("https://example.com", receiver.history.value.single().text)
        assertTrue(File(root, "history.json").let { f -> withTimeout(5_000) { while (!f.isFile) kotlinx.coroutines.delay(20) }; f.readText().contains("example.com") })
    }

    @Test
    fun trustedSenderIsAutoAcceptedWithoutPrompt() = runBlocking {
        val first = sourceFile("one.txt", 100)
        assertEquals(TransferStatus.COMPLETED, connectAndSend(listOf(first), accept = true, trust = true))
        assertEquals(senderSettings.deviceId, receiver.trustedDevices.value.single().deviceId)

        receiver.setAutoAcceptTrusted(true)
        sender.stage(listOf(LocalFileHandle(sourceFile("two.txt", 200))))
        withTimeout(5_000) { sender.staged.first { it.size == 1 } }
        val before = sender.transfers.value.size
        sender.sendStaged(sender.peers.value.single())
        // No responder: completing proves no prompt was needed.
        val finished = withTimeout(15_000) {
            sender.transfers.first { list -> list.size > before && list.none { it.isActive } }.first()
        }
        assertEquals(TransferStatus.COMPLETED, finished.status)
        assertTrue(File(destDir, "two.txt").isFile)
    }

    @Test
    fun refusesPeerPresentingADifferentKey() = runBlocking {
        sender.addManualPeer("127.0.0.1", receiver.local.value.port!!)
        val real = withTimeout(10_000) { sender.peers.first { it.isNotEmpty() }.single() }
        // Same device id and address, but a fingerprint the receiver cannot prove it holds.
        val impostor = real.copy(info = real.info.copy(fingerprint = "0".repeat(64)), addresses = listOf("127.0.0.1"))
        sender.stage(listOf(LocalFileHandle(sourceFile("secret.txt", 64))))
        withTimeout(5_000) { sender.staged.first { it.size == 1 } }

        sender.sendStaged(impostor)
        val finished = withTimeout(15_000) {
            sender.transfers.first { list -> list.isNotEmpty() && list.none { it.isActive } }.first()
        }

        assertEquals(TransferStatus.FAILED, finished.status)
        assertTrue(receiver.incomingOffer.value == null)
        assertTrue(destDir.listFiles()!!.isEmpty())
    }

    @Test
    fun existingFileIsNotOverwritten() = runBlocking {
        val file = sourceFile("notes.txt", 4096)
        File(destDir, "notes.txt").writeText("keep me")

        assertEquals(TransferStatus.COMPLETED, connectAndSend(listOf(file), accept = true))

        assertEquals("keep me", File(destDir, "notes.txt").readText())
        assertContentEquals(file.readBytes(), File(destDir, "notes (1).txt").readBytes())
    }
}
