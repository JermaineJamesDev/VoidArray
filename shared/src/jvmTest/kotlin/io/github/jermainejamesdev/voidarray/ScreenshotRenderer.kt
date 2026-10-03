package io.github.jermainejamesdev.voidarray

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.jermainejamesdev.voidarray.core.FileSummary
import io.github.jermainejamesdev.voidarray.core.HistoryEntry
import io.github.jermainejamesdev.voidarray.core.IncomingOffer
import io.github.jermainejamesdev.voidarray.core.LocalStatus
import io.github.jermainejamesdev.voidarray.core.Peer
import io.github.jermainejamesdev.voidarray.core.PeerSource
import io.github.jermainejamesdev.voidarray.core.PlatformActions
import io.github.jermainejamesdev.voidarray.core.ThemeMode
import io.github.jermainejamesdev.voidarray.core.TransferController
import io.github.jermainejamesdev.voidarray.core.TransferDirection
import io.github.jermainejamesdev.voidarray.core.TransferState
import io.github.jermainejamesdev.voidarray.core.TransferStatus
import io.github.jermainejamesdev.voidarray.core.TrustedDevice
import io.github.jermainejamesdev.voidarray.core.UserSettings
import io.github.jermainejamesdev.voidarray.protocol.DeviceInfo
import io.github.jermainejamesdev.voidarray.protocol.DeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Renders the real UI with sample data to PNGs in shared/build/screenshots, for reviewing the theme and
 * for README images. Opt-in because it needs the native Skia runtime and produces files rather than
 * assertions: ./gradlew :shared:jvmTest --tests '*ScreenshotRenderer*' -Pscreenshots=true
 */
class ScreenshotRenderer {
    @Test
    fun render() {
        assumeTrue("Pass -Pscreenshots=true to render", System.getProperty("screenshots") == "true")
        val out = File("build/screenshots").apply { mkdirs() }
        val shots = listOf(
            Triple("send-dark", Destination.SEND, ThemeMode.DARK),
            Triple("receive-dark", Destination.RECEIVE, ThemeMode.DARK),
            Triple("history-dark", Destination.HISTORY, ThemeMode.DARK),
            Triple("settings-dark", Destination.SETTINGS, ThemeMode.DARK),
            Triple("send-light", Destination.SEND, ThemeMode.LIGHT),
            Triple("receive-light", Destination.RECEIVE, ThemeMode.LIGHT),
        )
        for ((name, destination, theme) in shots) {
            val controller = SampleController(theme)
            renderTo(File(out, "$name.png"), 1280, 860) {
                AppRoot(controller, SampleActions, isDropTarget = false, startDestination = destination)
            }
            renderTo(File(out, "$name-phone.png"), 412, 900) {
                AppRoot(controller, SampleActions, isDropTarget = false, startDestination = destination)
            }
        }
        val offer = SampleController(ThemeMode.DARK, withOffer = true)
        renderTo(File(out, "offer-dark-phone.png"), 412, 900) {
            AppRoot(offer, SampleActions, isDropTarget = false, startDestination = Destination.RECEIVE)
        }
    }

    private fun renderTo(file: File, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val density = 2f
        val scene = ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density), content = content)
        try {
            // Several frames so resources (the display font) load and animations settle on a fixed pose.
            var image = scene.render(0)
            repeat(6) { frame ->
                Thread.sleep(150)
                image = scene.render(frame * 1_000_000_000L)
            }
            file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }

    private object SampleActions : PlatformActions {
        override val appVersion = "1.0.0"
        override val supportsTray = true
        override val supportsDragAndDrop = true
        override val reduceMotion = false
        override fun pickFilesToSend() {}
        override fun pickDestinationFolder() {}
        override fun openReceivedFolder() {}
        override fun copyToClipboard(text: String) {}
        override fun startNetworking() {}
    }

    private class SampleController(theme: ThemeMode, withOffer: Boolean = false) : TransferController {
        private val phone = DeviceInfo("p1", "Lin's Pixel", DeviceType.MOBILE, 53318, fingerprint = "A".repeat(64))
        private val laptop = DeviceInfo("l1", "Study Laptop", DeviceType.DESKTOP, 53318, fingerprint = "B".repeat(64))

        override val local = MutableStateFlow(
            LocalStatus(
                alias = "Jade Pavilion PC",
                deviceId = "me",
                deviceType = DeviceType.DESKTOP,
                fingerprint = "3F2A 9C01 77D4 B2E8 0A6C 5D19 E4F3 1287 6B0D 9A55 C3E1 2F8B 4D70 A9C6 18E2 5B3F",
                port = 53318,
                addresses = listOf("192.168.1.24"),
                serverRunning = true,
                destinationLabel = "C:\\Users\\lin\\Downloads\\VoidArray",
            ),
        )
        override val settings = MutableStateFlow(UserSettings(alias = "Jade Pavilion PC", autoAcceptTrusted = true, theme = theme))
        override val peers = MutableStateFlow(
            listOf(
                Peer(phone, listOf("192.168.1.31"), PeerSource.DISCOVERED, 0, trusted = true),
                Peer(laptop, listOf("192.168.1.40"), PeerSource.DISCOVERED, 0),
            ),
        )
        override val staged = MutableStateFlow(
            listOf(FileSummary("Sword Manual (Vol. 3).pdf", 18_400_000), FileSummary("mountain-sect.jpg", 4_200_000)),
        )
        override val stagedText = MutableStateFlow("")
        override val incomingOffer = MutableStateFlow(
            if (withOffer) {
                IncomingOffer(
                    id = "o1",
                    sender = laptop,
                    senderAddress = "192.168.1.40",
                    files = listOf(FileSummary("cultivation-notes.md", 12_000), FileSummary("array-diagram.png", 880_000)),
                    text = null,
                    verified = true,
                    trusted = false,
                    identityChanged = false,
                    pairingCode = "482 913",
                )
            } else {
                null
            },
        )
        override val transfers = MutableStateFlow(
            listOf(
                TransferState(
                    id = "t0",
                    direction = TransferDirection.SEND,
                    peerAlias = "Study Laptop",
                    files = listOf(FileSummary("array-diagram.png", 880_000)),
                    totalBytes = 880_000,
                    status = TransferStatus.WAITING_FOR_ACCEPT,
                    pairingCode = "482 913",
                ),
                TransferState(
                    id = "t1",
                    direction = TransferDirection.SEND,
                    peerAlias = "Lin's Pixel",
                    files = staged.value,
                    totalBytes = 22_600_000,
                    bytesTransferred = 14_100_000,
                    status = TransferStatus.IN_PROGRESS,
                    currentFile = "Sword Manual (Vol. 3).pdf",
                    bytesPerSecond = 9_800_000,
                ),
                TransferState(
                    id = "t2",
                    direction = TransferDirection.RECEIVE,
                    peerAlias = "Study Laptop",
                    files = listOf(FileSummary("elder-letter.txt", 2_300)),
                    totalBytes = 2_300,
                    bytesTransferred = 2_300,
                    status = TransferStatus.COMPLETED,
                    message = "Saved to Downloads\\VoidArray",
                ),
            ),
        )
        override val history = MutableStateFlow(
            listOf(
                HistoryEntry("h1", TransferDirection.RECEIVE, "Lin's Pixel", listOf(FileSummary("IMG_2041.jpg", 3_100_000)), 1, 3_100_000,
                    status = TransferStatus.COMPLETED, finishedAtMillis = 0, finishedAtLabel = "Oct 3, 2026, 9:41 AM", location = "Downloads\\VoidArray"),
                HistoryEntry("h2", TransferDirection.SEND, "Study Laptop", emptyList(), 0, 0, text = "https://example.com/the-void-array",
                    status = TransferStatus.COMPLETED, finishedAtMillis = 0, finishedAtLabel = "Oct 2, 2026, 8:15 PM"),
                HistoryEntry("h3", TransferDirection.SEND, "Study Laptop", listOf(FileSummary("a.zip", 1), FileSummary("b.zip", 1)), 12, 840_000_000,
                    status = TransferStatus.FAILED, finishedAtMillis = 0, finishedAtLabel = "Oct 2, 2026, 7:02 PM", message = "Connection lost. Send again to resume."),
            ),
        )
        override val trustedDevices = MutableStateFlow(listOf(TrustedDevice("p1", "Lin's Pixel", "A".repeat(64), 0)))

        override fun rescan() {}
        override fun addManualPeer(host: String, port: Int) {}
        override fun removeStaged(index: Int) {}
        override fun clearStaged() {}
        override fun setStagedText(text: String) {}
        override fun sendStaged(peer: Peer) {}
        override fun respondToOffer(accept: Boolean, trust: Boolean) {}
        override fun cancelTransfer(id: String) {}
        override fun clearFinishedTransfers() {}
        override fun dismissNotice() {}
        override fun setAlias(alias: String) {}
        override fun setAutoAcceptTrusted(enabled: Boolean) {}
        override fun setTheme(mode: ThemeMode) {}
        override fun setMinimizeToTray(enabled: Boolean) {}
        override fun forgetDevice(deviceId: String) {}
        override fun clearHistory() {}
    }
}
