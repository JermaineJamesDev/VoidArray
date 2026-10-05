package io.github.jermainejamesdev.voidarray.engine

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.jermainejamesdev.voidarray.core.QrMatrix
import java.net.InetAddress

/**
 * What a pairing QR code carries: where the showing device listens, the key it will present there, and a
 * single-use token proving the scanner saw the code. The device name is deliberately absent; the scanner
 * learns it from the device itself over TLS pinned to [fingerprint], so the code cannot lie about it.
 *
 * Format: `voidarray://pair?v=1&k=<fingerprint>&p=<port>&a=<ip>,<ip>&t=<token>`
 */
internal data class PairingLink(
    val fingerprint: String,
    val port: Int,
    val addresses: List<String>,
    val token: String,
) {
    fun toUri(): String = "$PREFIX?v=$VERSION&k=$fingerprint&p=$port&a=${addresses.joinToString(",")}&t=$token"

    companion object {
        private const val PREFIX = "voidarray://pair"
        private const val VERSION = "1"
        private const val MAX_ADDRESSES = 4
        private val FINGERPRINT = Regex("[0-9A-Fa-f]{64}")
        private val TOKEN = Regex("[0-9a-f]{32}")
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV6 = Regex("[0-9A-Fa-f:]{2,39}")

        /**
         * Parses a scanned code, or returns null if it is not a valid pairing link. Anything can be encoded
         * in a QR code, so addresses must be literal IPs on a local network: a code cannot point the scanner
         * at a host name or at the internet.
         */
        fun parse(text: String): PairingLink? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("$PREFIX?")) return null
            val params = trimmed.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            if (params["v"] != VERSION) return null
            val fingerprint = params["k"]?.takeIf { it.matches(FINGERPRINT) }?.uppercase() ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val token = params["t"]?.takeIf { it.matches(TOKEN) } ?: return null
            val addresses = params["a"].orEmpty().split(',')
                .filter { isLocalIpLiteral(it) }
                .distinct()
                .take(MAX_ADDRESSES)
            if (addresses.isEmpty()) return null
            return PairingLink(fingerprint, port, addresses, token)
        }

        private fun isLocalIpLiteral(text: String): Boolean {
            // The pattern check comes first: InetAddress.getByName would resolve a host name over DNS.
            if (!text.matches(IPV4) && !(text.contains(':') && text.matches(IPV6))) return false
            val address = runCatching { InetAddress.getByName(text) }.getOrNull() ?: return false
            return isLocalNetworkPeer(address)
        }
    }
}

/** Encodes [text] at medium error correction, which survives glare and a slightly blurry phone camera. */
internal fun encodeQr(text: String): QrMatrix {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
    val size = matrix.width
    return QrMatrix(size, BooleanArray(size * size) { matrix.get(it % size, it / size).toInt() == 1 })
}
