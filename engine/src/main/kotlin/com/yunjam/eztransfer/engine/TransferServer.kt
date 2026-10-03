package com.yunjam.eztransfer.engine

import com.yunjam.eztransfer.protocol.DeviceInfo
import com.yunjam.eztransfer.protocol.ErrorResponse
import com.yunjam.eztransfer.protocol.Params
import com.yunjam.eztransfer.protocol.PrepareRequest
import com.yunjam.eztransfer.protocol.PrepareResponse
import com.yunjam.eztransfer.protocol.ProtocolJson
import com.yunjam.eztransfer.protocol.Routes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.KSerializer

/** Callbacks the HTTP layer delegates to; keeps Ktor types out of the engine's transfer logic. */
internal interface ServerHandler {
    fun info(): DeviceInfo
    fun onRegister(peer: DeviceInfo, remoteAddress: String)
    suspend fun onPrepare(request: PrepareRequest, remoteAddress: String): PrepareOutcome
    suspend fun onUpload(
        sessionId: String,
        fileId: String,
        token: String,
        offset: Long,
        remoteAddress: String,
        body: ByteReadChannel,
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

internal class TransferServer(private val handler: ServerHandler) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * Binds [preferredPort] on all interfaces, falling back to an ephemeral port if it is taken.
     * Discovered peers learn the actual port from announcements; only manual entry needs the default.
     */
    suspend fun start(preferredPort: Int): Int {
        return runCatching { bind(preferredPort) }.getOrElse { bind(0) }
    }

    private suspend fun bind(port: Int): Int {
        val instance = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get(Routes.INFO) {
                    call.respondJson(DeviceInfo.serializer(), handler.info())
                }
                post(Routes.REGISTER) {
                    val peer = call.receiveJson(DeviceInfo.serializer()) ?: return@post
                    handler.onRegister(peer, call.request.origin.remoteAddress)
                    call.respondJson(DeviceInfo.serializer(), handler.info())
                }
                post(Routes.PREPARE) {
                    val request = call.receiveJson(PrepareRequest.serializer()) ?: return@post
                    when (val outcome = handler.onPrepare(request, call.request.origin.remoteAddress)) {
                        is PrepareOutcome.Accepted -> call.respondJson(PrepareResponse.serializer(), outcome.response)
                        PrepareOutcome.Declined -> call.respondError(HttpStatusCode.Forbidden, "Declined")
                        PrepareOutcome.Busy -> call.respondError(HttpStatusCode.Conflict, "Receiver is busy")
                        is PrepareOutcome.Invalid -> call.respondError(HttpStatusCode.BadRequest, outcome.message)
                    }
                }
                post(Routes.UPLOAD) {
                    val params = call.request.queryParameters
                    val session = params[Params.SESSION]
                    val file = params[Params.FILE]
                    val token = params[Params.TOKEN]
                    val offset = params[Params.OFFSET]?.toLongOrNull()
                    if (session == null || file == null || token == null || offset == null || offset < 0) {
                        call.respondError(HttpStatusCode.BadRequest, "Missing or invalid parameters")
                        return@post
                    }
                    val outcome = handler.onUpload(
                        session, file, token, offset, call.request.origin.remoteAddress, call.receiveChannel(),
                    )
                    when (outcome) {
                        UploadOutcome.Ok -> call.respondText("{}", ContentType.Application.Json)
                        is UploadOutcome.Rejected ->
                            call.respondError(HttpStatusCode.fromValue(outcome.status), outcome.message)
                    }
                }
                post(Routes.CANCEL) {
                    val session = call.request.queryParameters[Params.SESSION]
                    if (session != null) handler.onCancel(session, call.request.origin.remoteAddress)
                    call.respondText("{}", ContentType.Application.Json)
                }
            }
        }
        instance.startSuspend(wait = false)
        server = instance
        return instance.engine.resolvedConnectors().first().port
    }

    suspend fun stop() {
        server?.stopSuspend(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }
}

private suspend fun <T> ApplicationCall.respondJson(serializer: KSerializer<T>, value: T) {
    respondText(ProtocolJson.encodeToString(serializer, value), ContentType.Application.Json)
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respondText(
        ProtocolJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(message)),
        ContentType.Application.Json,
        status,
    )
}

/** Decodes the body or responds 400 and returns null. */
private suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>): T? =
    try {
        ProtocolJson.decodeFromString(serializer, receiveText())
    } catch (e: IllegalArgumentException) {
        // Also covers SerializationException, which extends it.
        respondError(HttpStatusCode.BadRequest, "Malformed request: ${e.message}")
        null
    }
