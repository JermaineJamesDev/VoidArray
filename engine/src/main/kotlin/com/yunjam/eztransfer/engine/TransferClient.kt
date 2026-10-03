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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * HTTPS client that authenticates peers by pinned public key instead of by certificate chain. Peers are
 * addressed by LAN IP with self-signed certificates, so chain and hostname validation are meaningless;
 * every request that carries data instead names the fingerprint it expects, and a network interceptor
 * checks it against the TLS handshake before any request bytes are written.
 */
internal class TransferClient {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 30_000
        }
        engine {
            config {
                sslSocketFactory(acceptAnyContext.socketFactory, AcceptAnyTrustManager)
                hostnameVerifier { _, _ -> true }
                // Shorter than the server's 60s keep-alive limit; see HttpsServer.READ_TIMEOUT_MILLIS.
                connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            }
            addNetworkInterceptor(PinningInterceptor)
        }
    }

    /**
     * Fetches a peer's info. With [pin] the connection must present that key. Without it the call is
     * trust-on-first-use, but the returned info is still rejected unless its claimed fingerprint matches
     * the key actually presented, so a peer cannot claim another device's identity.
     */
    suspend fun info(host: String, port: Int, pin: String? = null, quick: Boolean = false): DeviceInfo {
        val response = http.get(url(host, port, Routes.INFO)) {
            pin(pin)
            if (quick) timeout {
                connectTimeoutMillis = 600
                requestTimeoutMillis = 2_000
            }
        }
        val info = response.decodeOrThrow<DeviceInfo>()
        val presented = response.headers[OBSERVED_HEADER]
        if (presented == null || !presented.equals(info.fingerprint, ignoreCase = true)) {
            throw TransferException("The device at $host presented a certificate that does not match its identity.")
        }
        return info
    }

    suspend fun register(host: String, port: Int, pin: String, self: DeviceInfo): DeviceInfo {
        val response = http.post(url(host, port, Routes.REGISTER)) {
            pin(pin)
            setBody(jsonBody(ProtocolJson.encodeToString(DeviceInfo.serializer(), self)))
            timeout { requestTimeoutMillis = 3_000 }
        }
        return response.decodeOrThrow()
    }

    /** Blocks until the receiver's user answers, so the request timeout must outlast the receiver's prompt. */
    suspend fun prepare(host: String, port: Int, pin: String, request: PrepareRequest): PrepareResult {
        val response = http.post(url(host, port, Routes.PREPARE)) {
            pin(pin)
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
        pin: String,
        sessionId: String,
        fileId: String,
        token: String,
        file: FileHandle,
        offset: Long,
        onProgress: (Long) -> Unit,
    ) {
        val response = http.post(url(host, port, Routes.UPLOAD)) {
            pin(pin)
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

    suspend fun cancel(host: String, port: Int, pin: String, sessionId: String) {
        http.post(url(host, port, Routes.CANCEL)) {
            pin(pin)
            parameter(Params.SESSION, sessionId)
            timeout { requestTimeoutMillis = 3_000 }
        }
    }

    fun close() = http.close()

    private fun HttpRequestBuilder.pin(fingerprint: String?) {
        if (fingerprint != null) header(PIN_HEADER, fingerprint)
    }

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

    private object AcceptAnyTrustManager : X509TrustManager {
        // Intentionally accepts any chain: authentication is the SPKI pin checked in PinningInterceptor.
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Runs after the TLS handshake and before the request is written. Requests tagged with [PIN_HEADER]
     * fail unless the server's key matches; every response is tagged with the key that was presented.
     */
    private object PinningInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val expected = chain.request().header(PIN_HEADER)
            val request = chain.request().newBuilder().removeHeader(PIN_HEADER).build()
            // Read from the raw TLS session: OkHttp's Handshake.peerCertificates runs the chain through a cleaner
            // that returns an empty list for certificates not anchored in a trusted root, i.e. all of ours.
            val session = (chain.connection()?.socket() as? SSLSocket)?.session
            val certificate = runCatching { session?.peerCertificates?.firstOrNull() }.getOrNull() as? X509Certificate
            val presented = certificate?.let { spkiFingerprint(it.publicKey) }
                ?: throw IOException("Peer did not present a certificate")
            if (expected != null && !expected.equals(presented, ignoreCase = true)) {
                throw PinMismatchException()
            }
            return chain.proceed(request).newBuilder().header(OBSERVED_HEADER, presented).build()
        }
    }

    private companion object {
        const val PIN_HEADER = "X-EzTransfer-Expect-Key"
        const val OBSERVED_HEADER = "X-EzTransfer-Presented-Key"

        val acceptAnyContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(AcceptAnyTrustManager), SecureRandom())
        }

        fun url(host: String, port: Int, path: String): String {
            val literal = if (':' in host) "[$host]" else host
            return "https://$literal:$port$path"
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

/** The peer's key differs from the one pinned for it. An IOException so OkHttp surfaces it unchanged. */
class PinMismatchException : IOException("This device's security key changed. It may be a different device using the same name.")

internal const val BUFFER_SIZE = 64 * 1024

/** How long the receiver waits for its user to accept or decline an incoming offer. */
internal const val PROMPT_TIMEOUT_MILLIS = 120_000L
