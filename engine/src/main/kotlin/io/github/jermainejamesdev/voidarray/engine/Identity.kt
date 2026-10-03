package io.github.jermainejamesdev.voidarray.engine

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * This install's TLS identity: a self-signed certificate generated on first run and kept in a PKCS12
 * file. Peers never validate the certificate chain; they pin the SHA-256 of its public key (SPKI), so
 * the certificate could be re-issued around the same key without breaking existing pairings.
 */
class DeviceIdentity private constructor(
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) {
    val fingerprint: String = spkiFingerprint(certificate.publicKey)

    fun serverSslContext(): SSLContext {
        val password = CharArray(0)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, password) }
            .keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }

    companion object {
        private const val ALIAS = "voidarray"
        private const val PASSWORD_KEY = "identityPassword"

        /**
         * Loads the identity from [file], creating it if missing or unreadable. The keystore password lives
         * in [store]; it only guards against casual copying, since both sit in app-private storage.
         */
        fun loadOrCreate(file: File, store: KeyValueStore): DeviceIdentity {
            val password = store.get(PASSWORD_KEY)
            if (password != null && file.isFile) {
                runCatching { load(file, password.toCharArray()) }.getOrNull()?.let { return it }
            }
            return create(file, store)
        }

        private fun load(file: File, password: CharArray): DeviceIdentity {
            val keyStore = KeyStore.getInstance("PKCS12")
            file.inputStream().use { keyStore.load(it, password) }
            val key = keyStore.getKey(ALIAS, password) as PrivateKey
            val cert = keyStore.getCertificate(ALIAS) as X509Certificate
            return DeviceIdentity(key, cert)
        }

        private fun create(file: File, store: KeyValueStore): DeviceIdentity {
            val password = ByteArray(24).also(SecureRandom()::nextBytes).toHex()
            // buildKeyStore's defaults (SHA-1, 1024-bit RSA, 3 days) would be refused by modern TLS stacks.
            val generated = buildKeyStore {
                certificate(ALIAS) {
                    this.password = password
                    hash = HashAlgorithm.SHA256
                    sign = SignatureAlgorithm.RSA
                    keySizeInBits = 2048
                    daysValid = 3650
                    // Cosmetic only (peers pin the key), but the library default names JetBrains.
                    subject = X500Principal("CN=VoidArray")
                }
            }
            val key = generated.getKey(ALIAS, password.toCharArray()) as PrivateKey
            val cert = generated.getCertificate(ALIAS) as X509Certificate

            // PKCS12 is readable on both Android and desktop JVMs, unlike each platform's default type.
            val pkcs12 = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(ALIAS, key, password.toCharArray(), arrayOf(cert))
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { pkcs12.store(it, password.toCharArray()) }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            store.put(PASSWORD_KEY, password)
            return DeviceIdentity(key, cert)
        }
    }
}

/** Uppercase hex SHA-256 of the DER-encoded SubjectPublicKeyInfo. */
fun spkiFingerprint(publicKey: PublicKey): String =
    MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).toHex().uppercase()

/** Groups a fingerprint into blocks of four for display, e.g. "3F2A 9C01 ...". */
fun formatFingerprint(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")

/**
 * A six-digit code derived from both devices' fingerprints, identical on both screens regardless of who
 * is sending. Comparing it out loud confirms no one in the middle substituted their own key.
 */
fun pairingCode(fingerprintA: String, fingerprintB: String): String {
    val (first, second) = listOf(fingerprintA, fingerprintB).sorted()
    val digest = MessageDigest.getInstance("SHA-256").digest("$first:$second".toByteArray())
    val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
        ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
    val code = (value % 1_000_000).toString().padStart(6, '0')
    return code.substring(0, 3) + " " + code.substring(3)
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
