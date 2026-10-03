package com.yunjam.eztransfer.engine

import com.yunjam.eztransfer.protocol.DeviceInfo
import com.yunjam.eztransfer.protocol.ErrorResponse
import com.yunjam.eztransfer.protocol.Params
import com.yunjam.eztransfer.protocol.PrepareRequest
import com.yunjam.eztransfer.protocol.PrepareResponse
import com.yunjam.eztransfer.protocol.ProtocolJson
import com.yunjam.eztransfer.protocol.Routes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TransferClient {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun info(host: String, port: Int, quick: Boolean = false): DeviceInfo {
        val response = http.get(url(host, port, Routes.INFO)) {
            if (quick) timeout {
                connectTimeoutMillis = 600
                requestTimeoutMillis = 1_500
            }
        }
        return response.decodeOrThrow()
    }

    suspend fun register(host: String, port: Int, self: DeviceInfo): DeviceInfo {
        val response = http.post(url(host, port, Routes.REGISTER)) {
            setBody(jsonBody(ProtocolJson.encodeToString(DeviceInfo.serializer(), self)))
            timeout { requestTimeoutMillis = 3_000 }
        }
        return response.decodeOrThrow()
    }

    /** Blocks until the receiver's user answers, so the request timeout must outlast the receiver's prompt. */
    suspend fun prepare(host: String, port: Int, request: PrepareRequest): PrepareResult {
        val response = http.post(url(host, port, Routes.PREPARE)) {
            setBody(jsonBody(ProtocolJson.encodeToString(PrepareRequest.serializer(), request)))
            timeout {
                requestTimeoutMillis = PROMPT_TIMEOUT_MILLIS + 15_000
                socketTimeoutMillis = PROMPT_TIMEOUT_MILLIS + 15_000
            }
        }
        return when (response.status) {
            HttpStatusCode.OK -> PrepareResult.Accepted(response.decode())
            HttpStatusCode.Forbidden -> PrepareResult.Declined
            HttpStatusCode.Conflict -> PrepareResult.Busy
            else -> PrepareResult.Failed(response.errorMessage())
        }
    }

    suspend fun upload(
        host: String,
        port: Int,
        sessionId: String,
        fileId: String,
        token: String,
        file: FileHandle,
        offset: Long,
        onProgress: (Long) -> Unit,
    ) {
        val response = http.post(url(host, port, Routes.UPLOAD)) {
            parameter(Params.SESSION, sessionId)
            parameter(Params.FILE, fileId)
            parameter(Params.TOKEN, token)
            parameter(Params.OFFSET, offset)
            setBody(FileContent(file, offset, onProgress))
            // A large file legitimately takes far longer than any fixed request timeout;
            // the socket timeout still catches a stalled connection.
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }
        if (response.status != HttpStatusCode.OK) {
            throw TransferException(response.errorMessage())
        }
    }

    suspend fun cancel(host: String, port: Int, sessionId: String) {
        http.post(url(host, port, Routes.CANCEL)) {
            parameter(Params.SESSION, sessionId)
            timeout { requestTimeoutMillis = 3_000 }
        }
    }

    fun close() = http.close()

    private class FileContent(
        private val file: FileHandle,
        private val offset: Long,
        private val onProgress: (Long) -> Unit,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = file.size - offset
        override val contentType: ContentType = ContentType.Application.OctetStream

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = contentLength
            withContext(Dispatchers.IO) {
                file.open(offset).use { input ->
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw TransferException("${file.name} ended early; it may have changed on disk")
                        channel.writeFully(buffer, 0, read)
                        remaining -= read
                        onProgress(read.toLong())
                    }
                }
            }
        }
    }

    sealed interface PrepareResult {
        data class Accepted(val response: PrepareResponse) : PrepareResult
        data object Declined : PrepareResult
        data object Busy : PrepareResult
        data class Failed(val message: String) : PrepareResult
    }

    private companion object {
        fun url(host: String, port: Int, path: String): String {
            val literal = if (':' in host) "[$host]" else host
            return "http://$literal:$port$path"
        }

        fun jsonBody(json: String) = TextContent(json, ContentType.Application.Json)

        suspend inline fun <reified T> HttpResponse.decode(): T =
            ProtocolJson.decodeFromString<T>(bodyAsText())

        suspend inline fun <reified T> HttpResponse.decodeOrThrow(): T {
            if (status != HttpStatusCode.OK) throw TransferException(errorMessage())
            return decode()
        }

        suspend fun HttpResponse.errorMessage(): String {
            val text = runCatching { bodyAsText() }.getOrDefault("")
            val parsed = runCatching { ProtocolJson.decodeFromString<ErrorResponse>(text).message }.getOrNull()
            return parsed ?: "HTTP ${status.value}"
        }
    }
}

class TransferException(message: String) : Exception(message)

internal const val BUFFER_SIZE = 64 * 1024

/** How long the receiver waits for its user to accept or decline an incoming offer. */
internal const val PROMPT_TIMEOUT_MILLIS = 120_000L
