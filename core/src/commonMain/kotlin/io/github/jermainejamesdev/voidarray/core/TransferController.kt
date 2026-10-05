package io.github.jermainejamesdev.voidarray.core

import io.github.jermainejamesdev.voidarray.protocol.DeviceInfo
import io.github.jermainejamesdev.voidarray.protocol.DeviceType
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** What the UI can observe and do. Implemented by the JVM engine shared by Android and desktop. */
interface TransferController {
    val local: StateFlow<LocalStatus>
    val settings: StateFlow<UserSettings>
    val peers: StateFlow<List<Peer>>
    val staged: StateFlow<List<FileSummary>>
    val stagedText: StateFlow<String>
    val incomingOffer: StateFlow<IncomingOffer?>
    val transfers: StateFlow<List<TransferState>>
    val history: StateFlow<List<HistoryEntry>>
    val trustedDevices: StateFlow<List<TrustedDevice>>

    /** The pairing QR code this device is showing, or null when none is open. */
    val pairingInvite: StateFlow<PairingInvite?>

    /** Progress of pairing with a QR code scanned on this device, or null when none is under way. */
    val qrPairing: StateFlow<QrPairingState?>

    /** Re-announces on every interface and sweeps the local /24 subnets for peers. */
    fun rescan()
    fun addManualPeer(host: String, port: Int)

    /** Opens a new single-use pairing QR code, replacing any open one. */
    fun showPairingQr()
    fun closePairingQr()

    /** Answers [PairingInvite.request]; accepting makes the two devices trust each other. */
    fun respondToPairingRequest(accept: Boolean)

    /** Pairs with the device whose QR code produced [text]. Progress is reported through [qrPairing]. */
    fun pairWithQr(text: String)
    fun dismissQrPairing()

    fun removeStaged(index: Int)
    fun clearStaged()
    fun setStagedText(text: String)
    fun sendStaged(peer: Peer)

    /** [trust] remembers the sender's key so later offers from it can be auto-accepted. */
    fun respondToOffer(accept: Boolean, trust: Boolean = false)
    fun cancelTransfer(id: String)
    fun clearFinishedTransfers()
    fun dismissNotice()

    fun setAlias(alias: String)
    fun setAutoAcceptTrusted(enabled: Boolean)
    fun setTheme(mode: ThemeMode)
    fun setMinimizeToTray(enabled: Boolean)

    /** Whether this device announces itself and answers other devices' announcements. */
    fun setDiscoverable(enabled: Boolean)
    fun forgetDevice(deviceId: String)
    fun clearHistory()

    /**
     * Replaces this device's key with a new one. Devices that trusted the old key see it as changed and
     * stop sending to it until they pair again. Refused while a transfer is active.
     */
    fun resetIdentity()
}

/** Platform-specific actions that need native pickers, system services, or permission prompts. */
interface PlatformActions {
    val appVersion: String
    val supportsTray: Boolean
    val supportsDragAndDrop: Boolean

    /** The device has a camera to scan another device's pairing QR code with. */
    val supportsQrScanning: Boolean

    /** The user turned animations off system-wide; decorative motion should stay still. */
    val reduceMotion: Boolean

    fun pickFilesToSend()

    /** Opens the camera to scan a pairing QR code, then hands the result to [TransferController.pairWithQr]. */
    fun scanPairingQr()
    fun pickDestinationFolder()
    fun openReceivedFolder()
    fun copyToClipboard(text: String)

    /** Requests local network access if needed, then starts the server and discovery. */
    fun startNetworking()
}

data class LocalStatus(
    val alias: String,
    val deviceId: String,
    val deviceType: DeviceType,
    /** This device's certificate fingerprint, grouped for reading aloud or comparing. */
    val fingerprint: String = "",
    val port: Int? = null,
    val addresses: List<String> = emptyList(),
    val serverRunning: Boolean = false,
    val scanning: Boolean = false,
    val destinationLabel: String = "",
    /** Persistent condition that blocks transfers, such as a denied permission or a port bind failure. */
    val problem: String? = null,
    /** Environment issue that degrades discovery without blocking it, such as a Windows "Public" network. */
    val networkWarning: String? = null,
    /** One-off message the user can dismiss. */
    val notice: String? = null,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserSettings(
    val alias: String,
    val autoAcceptTrusted: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val minimizeToTray: Boolean = true,
    val discoverable: Boolean = true,
)

/**
 * A square QR code, row by row, without the quiet zone. [isDark] reads one module.
 * Equality is by reference; a new code is a new object.
 */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    init {
        require(modules.size == size * size) { "Expected ${size * size} modules, got ${modules.size}" }
    }

    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]
}

/** A device that scanned this device's pairing code and is waiting for the user to confirm it. */
data class PairingRequest(
    val alias: String,
    val deviceType: DeviceType,
    val fingerprint: String,
)

data class PairingInvite(
    /** The text encoded in [qr]: where this device listens, its key, and a single-use token. */
    val uri: String,
    val qr: QrMatrix,
    val expiresAtMillis: Long,
    /** Set once a device has scanned the code and presented the token. */
    val request: PairingRequest? = null,
)

sealed interface QrPairingState {
    data object Connecting : QrPairingState
    /** The other device was reached and verified; its user has to confirm the pairing. */
    data class WaitingForConfirmation(val alias: String) : QrPairingState
    data class Paired(val alias: String) : QrPairingState
    data class Failed(val message: String) : QrPairingState
}

@Serializable
data class TrustedDevice(
    val deviceId: String,
    val alias: String,
    val fingerprint: String,
    val trustedAtMillis: Long,
)

enum class PeerSource { DISCOVERED, MANUAL }

data class Peer(
    val info: DeviceInfo,
    /** Most recently seen first. The sender races all of them because any one may be unreachable. */
    val addresses: List<String>,
    val source: PeerSource,
    val lastSeenMillis: Long,
    val trusted: Boolean = false,
    /** A trusted device id now presents a different key: either a reinstall or an impersonation attempt. */
    val identityChanged: Boolean = false,
)

@Serializable
data class FileSummary(
    val name: String,
    val size: Long,
)

data class IncomingOffer(
    val id: String,
    val sender: DeviceInfo,
    val senderAddress: String,
    val files: List<FileSummary>,
    val text: String?,
    val trusted: Boolean,
    val identityChanged: Boolean,
    /** Short code both devices display so the users can confirm they are talking to each other. */
    val pairingCode: String,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val isTextOnly: Boolean get() = files.isEmpty() && !text.isNullOrEmpty()
}

@Serializable
enum class TransferDirection { SEND, RECEIVE }

@Serializable
enum class TransferStatus {
    CONNECTING,
    WAITING_FOR_ACCEPT,
    IN_PROGRESS,
    COMPLETED,
    DECLINED,
    CANCELLED,
    FAILED,
}

data class TransferState(
    val id: String,
    val direction: TransferDirection,
    val peerAlias: String,
    val files: List<FileSummary>,
    val totalBytes: Long,
    val text: String? = null,
    val bytesTransferred: Long = 0,
    val status: TransferStatus,
    val currentFile: String? = null,
    val message: String? = null,
    /** Shown while waiting for an untrusted receiver to accept, so both users can compare it. */
    val pairingCode: String? = null,
    /** Bytes per second, smoothed; 0 when not moving data. */
    val bytesPerSecond: Long = 0,
) {
    val isActive: Boolean
        get() = status == TransferStatus.CONNECTING ||
            status == TransferStatus.WAITING_FOR_ACCEPT ||
            status == TransferStatus.IN_PROGRESS

    val progress: Float
        get() = if (totalBytes <= 0) (if (status == TransferStatus.COMPLETED) 1f else 0f)
        else (bytesTransferred.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

@Serializable
data class HistoryEntry(
    val id: String,
    val direction: TransferDirection,
    val peerAlias: String,
    /** Capped to the first few files so huge folders do not bloat the history file; see [fileCount]. */
    val files: List<FileSummary>,
    val fileCount: Int,
    val totalBytes: Long,
    val text: String? = null,
    val status: TransferStatus,
    val finishedAtMillis: Long,
    /** Locale-formatted timestamp, rendered once on the JVM side where date formatting is available. */
    val finishedAtLabel: String,
    /** Folder the files were saved to, for received transfers. */
    val location: String? = null,
    val message: String? = null,
)
