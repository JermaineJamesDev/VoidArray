package com.yunjam.eztransfer.core

import com.yunjam.eztransfer.protocol.DeviceInfo
import kotlinx.coroutines.flow.StateFlow

/** What the UI can observe and do. Implemented by the JVM engine shared by Android and desktop. */
interface TransferController {
    val local: StateFlow<LocalStatus>
    val peers: StateFlow<List<Peer>>
    val staged: StateFlow<List<FileSummary>>
    val incomingOffer: StateFlow<IncomingOffer?>
    val transfers: StateFlow<List<TransferState>>

    /** Re-announces on every interface and sweeps the local /24 subnets for peers. */
    fun rescan()
    fun addManualPeer(host: String, port: Int)
    fun removeStaged(index: Int)
    fun clearStaged()
    fun sendStaged(peer: Peer)
    fun respondToOffer(accept: Boolean)
    fun cancelTransfer(id: String)
    fun clearFinishedTransfers()
    fun dismissNotice()
}

/** Platform-specific UI actions that need native pickers or permission prompts. */
interface PlatformActions {
    fun pickFilesToSend()
    fun pickDestinationFolder()

    /** Requests local network access if needed, then starts the server and discovery. */
    fun startNetworking()
}

data class LocalStatus(
    val alias: String,
    val deviceId: String,
    val port: Int? = null,
    val addresses: List<String> = emptyList(),
    val serverRunning: Boolean = false,
    val scanning: Boolean = false,
    val destinationLabel: String = "",
    /** Persistent condition that blocks transfers, such as a denied permission or a port bind failure. */
    val problem: String? = null,
    /** One-off message the user can dismiss. */
    val notice: String? = null,
)

enum class PeerSource { DISCOVERED, MANUAL }

data class Peer(
    val info: DeviceInfo,
    /** Most recently seen first. The sender races all of them because any one may be unreachable. */
    val addresses: List<String>,
    val source: PeerSource,
    val lastSeenMillis: Long,
)

data class FileSummary(
    val name: String,
    val size: Long,
)

data class IncomingOffer(
    val id: String,
    val sender: DeviceInfo,
    val senderAddress: String,
    val files: List<FileSummary>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

enum class TransferDirection { SEND, RECEIVE }

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
    val bytesTransferred: Long = 0,
    val status: TransferStatus,
    val currentFile: String? = null,
    val message: String? = null,
) {
    val isActive: Boolean
        get() = status == TransferStatus.CONNECTING ||
            status == TransferStatus.WAITING_FOR_ACCEPT ||
            status == TransferStatus.IN_PROGRESS

    val progress: Float
        get() = if (totalBytes <= 0) (if (status == TransferStatus.COMPLETED) 1f else 0f)
        else (bytesTransferred.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}
