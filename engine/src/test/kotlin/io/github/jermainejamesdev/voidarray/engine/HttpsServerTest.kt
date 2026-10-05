package io.github.jermainejamesdev.voidarray.engine

import java.io.File
import java.nio.file.Files
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpsServerTest {
    private lateinit var dir: File
    private lateinit var server: HttpsServer
    private var port = 0

    private class MemoryStore : KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val clientContext = SSLContext.getInstance("TLS").apply {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        init(null, arrayOf(trustAll), null)
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("voidarray-http").toFile()
        val identity = FileIdentityStore(File(dir, "id.p12"), MemoryStore()).load()
        server = HttpsServer(identity.sslContext(), { request ->
            val body = request.body.readBytes().toString(Charsets.UTF_8)
            val text = "${request.method} ${request.path} ${request.query} $body key=${request.peerFingerprint}"
            HttpResponse(200, text.toByteArray(), "text/plain")
        }, log = {})
        port = server.start(0)
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    /** Sends raw bytes over TLS and returns everything the server writes until it closes the connection. */
    private fun exchange(raw: String, context: SSLContext = clientContext): String {
        val socket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.use {
            it.soTimeout = 3_000
            it.outputStream.write(raw.toByteArray())
            it.outputStream.flush()
            val out = StringBuilder()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val n = it.inputStream.read(buffer)
                    if (n < 0) break
                    out.append(String(buffer, 0, n))
                }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: javax.net.ssl.SSLException) {
            }
            return out.toString()
        }
    }

    @Test
    fun servesRequestWithQueryAndBody() {
        val response = exchange("POST /x?a=1&b=two%20words HTTP/1.1\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue(response.contains("POST /x {a=1, b=two words} hello"), response)
    }

    @Test
    fun keepsConnectionAliveForSequentialRequests() {
        val response = exchange(
            "GET /one HTTP/1.1\r\nContent-Length: 0\r\n\r\n" +
                "GET /two HTTP/1.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        )
        assertEquals(2, Regex("HTTP/1.1 200").findAll(response).count(), response)
        assertTrue(response.contains("GET /two"), response)
    }

    @Test
    fun rejectsHostileInputAndKeepsServing() {
        val hostile = listOf(
            "NOT A REQUEST\r\n\r\n",
            "GET / HTTP/1.1\r\nX-Big: ${"a".repeat(20_000)}\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: -5\r\n\r\n",
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n",
            "GET / HTTP/1.1\r\n" + (1..100).joinToString("") { "H$it: v\r\n" } + "\r\n",
        )
        for (raw in hostile) {
            val response = exchange(raw)
            assertTrue(!response.contains("200"), "Accepted hostile input: ${raw.take(60)}")
        }
        val healthy = exchange("GET /ok HTTP/1.1\r\nConnection: close\r\n\r\n")
        assertTrue(healthy.startsWith("HTTP/1.1 200"), healthy)
    }

    @Test
    fun reportsClientCertificateKeyIncludingOnResumedSessions() {
        val caller = FileIdentityStore(File(dir, "caller.p12"), MemoryStore()).load()
        val context = caller.sslContext()
        val request = "GET /who HTTP/1.1\r\nConnection: close\r\n\r\n"
        // The second connection from the same context resumes the TLS session; the key must survive that.
        repeat(2) { attempt ->
            val response = exchange(request, context)
            assertTrue(response.contains("key=${caller.fingerprint}"), "attempt $attempt: $response")
        }
        assertTrue(exchange(request).contains("key=null"), "a client without a certificate has no key")
    }
}
