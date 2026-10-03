package io.github.jermainejamesdev.voidarray.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

internal class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names are lowercased. */
    val headers: Map<String, String>,
    val remoteAddress: String,
    /** Bounded to Content-Length; reading past it returns EOF. */
    val body: InputStream,
) {
    val contentLength: Long = headers["content-length"]?.toLongOrNull() ?: 0L
}

internal class HttpResponse(val status: Int, val body: ByteArray, val contentType: String = "application/json")

/**
 * A deliberately small HTTP/1.1 server over TLS, built on the JDK's SSLServerSocket so it runs on both
 * Android and desktop. Ktor's CIO server cannot serve HTTPS on the JVM, and the alternatives were heavy
 * or fragile. It only needs to serve VoidArray's own client, so it supports exactly what that client
 * sends: Content-Length bodies, keep-alive, and no chunked encoding. Every size is bounded because
 * requests arrive from the network before anything is authenticated.
 */
internal class HttpsServer(
    private val sslContext: SSLContext,
    private val handler: (HttpRequest) -> HttpResponse,
    private val log: (String) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val connections = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val connectionPermits = Semaphore(MAX_CONNECTIONS)

    /** Binds [port] on all interfaces (0 for ephemeral) and returns the bound port. */
    @Synchronized
    fun start(port: Int): Int {
        check(serverSocket == null) { "Already started" }
        val socket = sslContext.serverSocketFactory.createServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port), BACKLOG)
        }
        val threadCount = AtomicInteger()
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "voidarray-http-${threadCount.incrementAndGet()}").apply { isDaemon = true }
        }
        serverSocket = socket
        executor = pool
        pool.execute { acceptLoop(socket, pool) }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        synchronized(connections) { connections.forEach { runCatching { it.close() } } }
        executor?.shutdownNow()
        serverSocket = null
        executor = null
    }

    private fun acceptLoop(server: ServerSocket, pool: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (e: IOException) {
                log("Accept failed: $e")
                continue
            }
            if (!connectionPermits.tryAcquire()) {
                // Refuse rather than queue: a flood of connections must not starve the real peer's transfer.
                runCatching { client.close() }
                continue
            }
            connections += client
            pool.execute {
                try {
                    serve(client)
                } finally {
                    connections -= client
                    runCatching { client.close() }
                    connectionPermits.release()
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            (socket as SSLSocket).startHandshake()
            val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
            val output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
            val remote = socket.inetAddress.hostAddress ?: return
            socket.soTimeout = READ_TIMEOUT_MILLIS
            while (!socket.isClosed) {
                val request = readRequest(input, remote) ?: return
                val response = try {
                    handler(request)
                } catch (e: Exception) {
                    log("Handler failed for ${request.path}: $e")
                    HttpResponse(500, """{"message":"Internal error"}""".toByteArray())
                }
                // If the handler did not consume the whole body, the stream is no longer aligned on the next
                // request, so the connection cannot be reused.
                val bodyConsumed = (request.body as BoundedInputStream).remaining == 0L
                val keepAlive = bodyConsumed && !request.headers["connection"].equals("close", ignoreCase = true)
                writeResponse(output, response, keepAlive)
                if (!keepAlive) return
            }
        } catch (_: SSLException) {
            // Handshake failures are expected from scanners and from peers that pinned a different key.
        } catch (_: ProtocolException) {
            // Malformed input from the network; the connection is simply dropped.
        } catch (_: SocketException) {
        } catch (_: java.net.SocketTimeoutException) {
        } catch (e: IOException) {
            log("Connection error: $e")
        }
    }

    /** Returns null on a clean end of stream between requests. Throws on malformed input. */
    private fun readRequest(input: InputStream, remoteAddress: String): HttpRequest? {
        val requestLine = readLine(input, allowEof = true) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw ProtocolException("Bad request line")
        val headers = HashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input, allowEof = false) ?: throw ProtocolException("Unexpected end of headers")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES || headers.size >= MAX_HEADERS) throw ProtocolException("Headers too large")
            val colon = line.indexOf(':')
            if (colon <= 0) throw ProtocolException("Bad header")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw ProtocolException("Transfer-Encoding is not supported")
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw ProtocolException("Bad Content-Length") } ?: 0L
        if (length < 0) throw ProtocolException("Bad Content-Length")

        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
        return HttpRequest(parts[0], path, query, headers, remoteAddress, BoundedInputStream(input, length))
    }

    /** Reads a CRLF- or LF-terminated ASCII line of at most [MAX_LINE_BYTES]. */
    private fun readLine(input: InputStream, allowEof: Boolean): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (buffer.isEmpty() && allowEof) return null
                throw IOException("Connection closed mid-line")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.append(b.toChar())
            if (buffer.length > MAX_LINE_BYTES) throw ProtocolException("Line too long")
        }
        return buffer.toString()
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse, keepAlive: Boolean) {
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        410 -> "Gone"
        413 -> "Payload Too Large"
        else -> if (status >= 500) "Server Error" else "Status"
    }

    internal class BoundedInputStream(input: InputStream, length: Long) : FilterInputStream(input) {
        var remaining: Long = length
            private set

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b >= 0) remaining-- else remaining = 0
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n else if (n < 0) remaining = 0
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(minOf(n, remaining))
            remaining -= skipped
            return skipped
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

        // Closing the body must not close the shared socket stream.
        override fun close() {}

        override fun markSupported(): Boolean = false
    }

    private companion object {
        const val BACKLOG = 50
        const val MAX_CONNECTIONS = 32
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_HEADERS = 64
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        // Also the keep-alive idle limit. The client's pool evicts idle connections sooner (see TransferClient)
        // so it never writes a one-shot upload body into a socket this side already closed.
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}
