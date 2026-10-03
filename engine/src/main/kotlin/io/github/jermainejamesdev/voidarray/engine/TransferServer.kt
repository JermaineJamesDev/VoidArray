package io.github.jermainejamesdev.voidarray.engine

import io.github.jermainejamesdev.voidarray.protocol.DeviceInfo
import io.github.jermainejamesdev.voidarray.protocol.ErrorResponse
import io.github.jermainejamesdev.voidarray.protocol.PairRequest
import io.github.jermainejamesdev.voidarray.protocol.PairResponse
import io.github.jermainejamesdev.voidarray.protocol.Params
import io.github.jermainejamesdev.voidarray.protocol.PrepareRequest
import io.github.jermainejamesdev.voidarray.protocol.PrepareResponse
import io.github.jermainejamesdev.voidarray.protocol.ProtocolJson
import io.github.jermainejamesdev.voidarray.protocol.Routes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import java.io.InputStream
import javax.net.ssl.SSLContext

/**
 * Who sent a request: the address it came from and the key it proved it holds in the TLS handshake.
 * Handlers authenticate by [fingerprint], never by address or by anything claimed in a request body.
 */
internal data class Caller(val address: String, val fingerprint: String)

/** Callbacks the HTTP layer delegates to; keeps HTTP details out of the engine's transfer logic. */
internal interface ServerHandler {
    fun info(): DeviceInfo
    fun onRegister(peer: DeviceInfo, caller: Caller)
    fun onPair(request: PairRequest, caller: Caller): PairOutcome
    suspend fun onPrepare(request: PrepareRequest, caller: Caller): PrepareOutcome
    fun onUpload(
        sessionId: String,
        fileId: String,
        token: String,
        offset: Long,
        caller: Caller,
        body: InputStream,
    ): UploadOutcome
    fun onCancel(sessionId: String, caller: Caller)
}

internal sealed interface PairOutcome {
    data class Started(val response: PairResponse) : PairOutcome
    data object RateLimited : PairOutcome
    data class Invalid(val message: String) : PairOutcome
}

internal sealed interface PrepareOutcome {
    data class Accepted(val response: PrepareResponse) : PrepareOutcome
    data object Declined : PrepareOutcome
    data object Busy : PrepareOutcome
    data class Invalid(val message: String) : PrepareOutcome
    /** The request was not made by the device it claims to come from. */
    data class Forbidden(val message: String) : PrepareOutcome
}

internal sealed interface UploadOutcome {
    data object Ok : UploadOutcome
    data class Rejected(val status: Int, val message: String) : UploadOutcome
}

internal class TransferServer(
    sslContext: SSLContext,
    private val handler: ServerHandler,
    log: (String) -> Unit,
) {
    private val http = HttpsServer(sslContext, ::route, log)

    /**
     * Binds [preferredPort] on all interfaces, falling back to an ephemeral port if it is taken.
     * Discovered peers learn the actual port from announcements; only manual entry needs the default.
     */
    fun start(preferredPort: Int): Int =
        runCatching { http.start(preferredPort) }.getOrElse { http.start(0) }

    fun stop() = http.stop()

    private fun route(request: HttpRequest): HttpResponse {
        val route = "${request.method} ${request.path}"
        if (route == "GET ${Routes.INFO}") return json(DeviceInfo.serializer(), handler.info())
        val caller = request.peerFingerprint?.let { Caller(request.remoteAddress, it) }
            ?: return error(401, "A client certificate is required. Update VoidArray on the sending device.")
        return when (route) {
            "POST ${Routes.REGISTER}" -> withJsonBody(request, DeviceInfo.serializer()) { peer ->
                handler.onRegister(peer, caller)
                json(DeviceInfo.serializer(), handler.info())
            }
            "POST ${Routes.PAIR}" -> withJsonBody(request, PairRequest.serializer()) { body ->
                when (val outcome = handler.onPair(body, caller)) {
                    is PairOutcome.Started -> json(PairResponse.serializer(), outcome.response)
                    PairOutcome.RateLimited -> error(429, "Too many pairing attempts. Wait a minute and try again.")
                    is PairOutcome.Invalid -> error(400, outcome.message)
                }
            }
            "POST ${Routes.PREPARE}" -> withJsonBody(request, PrepareRequest.serializer()) { body ->
                // The connection thread is dedicated to this request, so blocking it while the user decides is fine.
                when (val outcome = runBlocking { handler.onPrepare(body, caller) }) {
                    is PrepareOutcome.Accepted -> json(PrepareResponse.serializer(), outcome.response)
                    PrepareOutcome.Declined -> error(403, "Declined")
                    PrepareOutcome.Busy -> error(409, "Receiver is busy")
                    is PrepareOutcome.Invalid -> error(400, outcome.message)
                    // Distinct from 403 so the sender does not report an impersonation attempt as "declined".
                    is PrepareOutcome.Forbidden -> error(401, outcome.message)
                }
            }
            "POST ${Routes.UPLOAD}" -> upload(request, caller)
            "POST ${Routes.CANCEL}" -> {
                request.query[Params.SESSION]?.let { handler.onCancel(it, caller) }
                HttpResponse(200, EMPTY_JSON)
            }
            else -> error(404, "Not found")
        }
    }

    private fun upload(request: HttpRequest, caller: Caller): HttpResponse {
        val session = request.query[Params.SESSION]
        val file = request.query[Params.FILE]
        val token = request.query[Params.TOKEN]
        val offset = request.query[Params.OFFSET]?.toLongOrNull()
        if (session == null || file == null || token == null || offset == null || offset < 0) {
            return error(400, "Missing or invalid parameters")
        }
        return when (val outcome = handler.onUpload(session, file, token, offset, caller, request.body)) {
            UploadOutcome.Ok -> HttpResponse(200, EMPTY_JSON)
            is UploadOutcome.Rejected -> error(outcome.status, outcome.message)
        }
    }

    private inline fun <T> withJsonBody(
        request: HttpRequest,
        serializer: KSerializer<T>,
        block: (T) -> HttpResponse,
    ): HttpResponse {
        if (request.contentLength > MAX_JSON_BYTES) return error(413, "Request too large")
        val text = request.body.readBytes().toString(Charsets.UTF_8)
        val value = try {
            ProtocolJson.decodeFromString(serializer, text)
        } catch (_: IllegalArgumentException) {
            // Also covers SerializationException, which extends it. The parser's detail is not echoed back.
            return error(400, "Malformed request")
        }
        return block(value)
    }

    private fun <T> json(serializer: KSerializer<T>, value: T) =
        HttpResponse(200, ProtocolJson.encodeToString(serializer, value).toByteArray())

    private fun error(status: Int, message: String) =
        HttpResponse(status, ProtocolJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(message)).toByteArray())

    private companion object {
        val EMPTY_JSON = "{}".toByteArray()

        /** A prepare request for thousands of files is a few hundred KB; anything far beyond that is hostile. */
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
    }
}
