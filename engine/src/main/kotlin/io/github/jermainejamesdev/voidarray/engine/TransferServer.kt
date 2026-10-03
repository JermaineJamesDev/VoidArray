package io.github.jermainejamesdev.voidarray.engine

import io.github.jermainejamesdev.voidarray.protocol.DeviceInfo
import io.github.jermainejamesdev.voidarray.protocol.ErrorResponse
import io.github.jermainejamesdev.voidarray.protocol.Params
import io.github.jermainejamesdev.voidarray.protocol.PrepareRequest
import io.github.jermainejamesdev.voidarray.protocol.PrepareResponse
import io.github.jermainejamesdev.voidarray.protocol.ProtocolJson
import io.github.jermainejamesdev.voidarray.protocol.Routes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import java.io.InputStream
import javax.net.ssl.SSLContext

/** Callbacks the HTTP layer delegates to; keeps HTTP details out of the engine's transfer logic. */
internal interface ServerHandler {
    fun info(): DeviceInfo
    fun onRegister(peer: DeviceInfo, remoteAddress: String)
    suspend fun onPrepare(request: PrepareRequest, remoteAddress: String): PrepareOutcome
    fun onUpload(
        sessionId: String,
        fileId: String,
        token: String,
        offset: Long,
        remoteAddress: String,
        body: InputStream,
    ): UploadOutcome
    fun onCancel(sessionId: String, remoteAddress: String)
}

internal sealed interface PrepareOutcome {
    data class Accepted(val response: PrepareResponse) : PrepareOutcome
    data object Declined : PrepareOutcome
    data object Busy : PrepareOutcome
    data class Invalid(val message: String) : PrepareOutcome
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

    private fun route(request: HttpRequest): HttpResponse = when ("${request.method} ${request.path}") {
        "GET ${Routes.INFO}" -> json(DeviceInfo.serializer(), handler.info())
        "POST ${Routes.REGISTER}" -> withJsonBody(request, DeviceInfo.serializer()) { peer ->
            handler.onRegister(peer, request.remoteAddress)
            json(DeviceInfo.serializer(), handler.info())
        }
        "POST ${Routes.PREPARE}" -> withJsonBody(request, PrepareRequest.serializer()) { body ->
            // The connection thread is dedicated to this request, so blocking it while the user decides is fine.
            when (val outcome = runBlocking { handler.onPrepare(body, request.remoteAddress) }) {
                is PrepareOutcome.Accepted -> json(PrepareResponse.serializer(), outcome.response)
                PrepareOutcome.Declined -> error(403, "Declined")
                PrepareOutcome.Busy -> error(409, "Receiver is busy")
                is PrepareOutcome.Invalid -> error(400, outcome.message)
            }
        }
        "POST ${Routes.UPLOAD}" -> upload(request)
        "POST ${Routes.CANCEL}" -> {
            request.query[Params.SESSION]?.let { handler.onCancel(it, request.remoteAddress) }
            HttpResponse(200, EMPTY_JSON)
        }
        else -> error(404, "Not found")
    }

    private fun upload(request: HttpRequest): HttpResponse {
        val session = request.query[Params.SESSION]
        val file = request.query[Params.FILE]
        val token = request.query[Params.TOKEN]
        val offset = request.query[Params.OFFSET]?.toLongOrNull()
        if (session == null || file == null || token == null || offset == null || offset < 0) {
            return error(400, "Missing or invalid parameters")
        }
        return when (val outcome = handler.onUpload(session, file, token, offset, request.remoteAddress, request.body)) {
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
        } catch (e: IllegalArgumentException) {
            // Also covers SerializationException, which extends it.
            return error(400, "Malformed request: ${e.message}")
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
