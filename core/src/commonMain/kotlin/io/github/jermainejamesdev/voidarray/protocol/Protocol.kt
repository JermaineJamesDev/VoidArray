package io.github.jermainejamesdev.voidarray.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version 2 moved the API to HTTPS with SPKI-pinned certificates; version 1 peers cannot interoperate. */
const val PROTOCOL_VERSION = 2

/** TCP port for the HTTP API and UDP port for discovery. Deliberately not LocalSend's 53317. */
const val DEFAULT_PORT = 53318

/**
 * Kept inside 224.0.0.0/24 because some Android devices refuse to join any other multicast group.
 * Packets in this range are link-local and never routed, which is exactly the scope we want.
 */
const val MULTICAST_GROUP = "224.0.0.168"

object Routes {
    const val INFO = "/api/v1/info"
    const val REGISTER = "/api/v1/register"
    const val PREPARE = "/api/v1/prepare"
    const val UPLOAD = "/api/v1/upload"
    const val CANCEL = "/api/v1/cancel"
}

object Params {
    const val SESSION = "session"
    const val FILE = "file"
    const val TOKEN = "token"
    const val OFFSET = "offset"
}

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
enum class DeviceType {
    @SerialName("mobile") MOBILE,
    @SerialName("desktop") DESKTOP,
}

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val alias: String,
    val deviceType: DeviceType,
    val port: Int,
    val protocolVersion: Int = PROTOCOL_VERSION,
    /** Uppercase hex SHA-256 of the certificate's SubjectPublicKeyInfo; the identity peers pin to. */
    val fingerprint: String,
)

/** UDP discovery payload. [announce] asks receivers to reply so both sides learn each other in one round trip. */
@Serializable
data class Announcement(
    val info: DeviceInfo,
    val announce: Boolean,
)

@Serializable
data class FileMeta(
    val id: String,
    val name: String,
    val size: Long,
)

@Serializable
data class PrepareRequest(
    val sender: DeviceInfo,
    val files: List<FileMeta>,
    /** Optional message or link sent along with (or instead of) files. */
    val text: String? = null,
)

/**
 * [offsets] holds the number of bytes the receiver already has for each file from an earlier,
 * interrupted attempt, so the sender resumes from there without needing HTTP Range semantics.
 */
@Serializable
data class PrepareResponse(
    val sessionId: String,
    val tokens: Map<String, String>,
    val offsets: Map<String, Long>,
)

/** Longest text message accepted, in characters. */
const val MAX_TEXT_LENGTH = 64 * 1024

@Serializable
data class ErrorResponse(
    val message: String,
)
