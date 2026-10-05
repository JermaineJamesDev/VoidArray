package io.github.jermainejamesdev.voidarray.engine

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

/**
 * This install's TLS identity: a key and a self-signed certificate for it. Peers never validate the
 * certificate chain; they pin the SHA-256 of its public key (SPKI), so the certificate could be re-issued
 * around the same key without breaking existing pairings. [privateKey] may be a handle to a key that
 * cannot be exported, such as one held by the Android Keystore.
 */
class DeviceIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val fingerprint: String = spkiFingerprint(certificate.publicKey)

    /**
     * One context for both directions: it presents this identity as a server certificate and as a client
     * certificate, and accepts any peer chain because authentication is the SPKI pin checked by the caller.
     */
    internal fun sslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleKeyManager(privateKey, certificate)), arrayOf(PinnedByCallerTrustManager), SecureRandom())
        }

    companion object {
        /** A new 2048-bit RSA key with a ten-year self-signed certificate, generated in software. */
        fun generate(): DeviceIdentity {
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
            return DeviceIdentity(
                generated.getKey(ALIAS, password.toCharArray()) as PrivateKey,
                generated.getCertificate(ALIAS) as X509Certificate,
            )
        }

        internal const val ALIAS = "voidarray"
    }
}

/** Where an identity is kept. Platforms with a hardware-backed key store provide their own. */
interface IdentityStore {
    /** Returns the stored identity, creating one on first use. */
    fun load(): DeviceIdentity

    /** Discards the stored identity and returns a newly created one. */
    fun replace(): DeviceIdentity
}

/**
 * Keeps the identity in a PKCS12 file. The keystore password lives in [store]; it only guards against
 * casual copying, since both sit in the same private folder.
 */
class FileIdentityStore(private val file: File, private val store: KeyValueStore) : IdentityStore {
    override fun load(): DeviceIdentity = readExisting() ?: replace()

    override fun replace(): DeviceIdentity = DeviceIdentity.generate().also(::save)

    /** The identity in the file, or null if there is none or it cannot be read. */
    fun readExisting(): DeviceIdentity? {
        val password = store.get(PASSWORD_KEY)?.toCharArray() ?: return null
        if (!file.isFile) return null
        return runCatching {
            val keyStore = KeyStore.getInstance("PKCS12")
            file.inputStream().use { keyStore.load(it, password) }
            DeviceIdentity(
                keyStore.getKey(DeviceIdentity.ALIAS, password) as PrivateKey,
                keyStore.getCertificate(DeviceIdentity.ALIAS) as X509Certificate,
            )
        }.getOrNull()
    }

    /** Removes the file and its password, once the identity has moved somewhere safer. */
    fun delete() {
        file.delete()
        store.put(PASSWORD_KEY, null)
    }

    private fun save(identity: DeviceIdentity) {
        val password = ByteArray(24).also(SecureRandom()::nextBytes).toHex()
        // PKCS12 is readable on both Android and desktop JVMs, unlike each platform's default type.
        val pkcs12 = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(DeviceIdentity.ALIAS, identity.privateKey, password.toCharArray(), arrayOf(identity.certificate))
        }
        writeFileAtomically(file) { pkcs12.store(it, password.toCharArray()) }
        store.put(PASSWORD_KEY, password)
    }

    private companion object {
        const val PASSWORD_KEY = "identityPassword"
    }
}

/**
 * Presents one key in both directions. A KeyManagerFactory would first copy the key into an in-memory
 * KeyStore, which fails for keys that cannot be exported; handing out the key object directly lets the TLS
 * provider delegate signing to whichever provider owns it.
 */
private class SingleKeyManager(
    private val key: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {
    // Matching on the exact algorithm matters: offering an "RSA" key for "RSASSA-PSS" would make JSSE
    // choose a signature scheme the key cannot produce.
    private fun aliasFor(keyType: String?): String? =
        DeviceIdentity.ALIAS.takeIf { keyType.equals(key.algorithm, ignoreCase = true) }

    private fun aliasFor(keyTypes: Array<out String>?): String? =
        DeviceIdentity.ALIAS.takeIf { keyTypes.orEmpty().any { it.equals(key.algorithm, ignoreCase = true) } }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = aliasFor(keyType)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = aliasFor(keyType)
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = aliasFor(keyType)
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = aliasFor(keyType)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = aliasFor(keyType)?.let { arrayOf(it) }
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = aliasFor(keyType)?.let { arrayOf(it) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == DeviceIdentity.ALIAS) arrayOf(certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == DeviceIdentity.ALIAS) key else null
}

/**
 * Accepts any certificate chain, in both directions. Peers use self-signed certificates addressed by LAN
 * IP, so chain and hostname validation are meaningless; every caller instead compares the presented key
 * against the fingerprint it expects. Extended so JSSE does not wrap it with endpoint checks of its own.
 */
internal object PinnedByCallerTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {}
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Both ends are VoidArray, so there is no legacy peer that would need anything older. */
private val allowedTlsProtocols = setOf("TLSv1.3", "TLSv1.2")

internal fun SSLServerSocket.restrictTlsProtocols() {
    enabledProtocols = supportedProtocols.filter { it in allowedTlsProtocols }.toTypedArray()
}

internal fun SSLSocket.restrictTlsProtocols() {
    enabledProtocols = supportedProtocols.filter { it in allowedTlsProtocols }.toTypedArray()
}

/** Uppercase hex SHA-256 of the DER-encoded SubjectPublicKeyInfo. */
fun spkiFingerprint(publicKey: PublicKey): String =
    MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).toHex().uppercase()

/** Groups a fingerprint into blocks of four for display, e.g. "3F2A 9C01 ...". */
fun formatFingerprint(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")

/** A fresh 256-bit nonce, hex, for one pairing exchange. */
internal fun newPairingNonce(random: SecureRandom): String = ByteArray(32).also(random::nextBytes).toHex()

/** What the sender sends before revealing [nonce]; binding its fingerprint stops a relay from reusing it. */
fun pairingCommitment(senderFingerprint: String, nonce: String): String =
    sha256("voidarray-commit-v3|${senderFingerprint.uppercase()}|$nonce").toHex()

/**
 * The six-digit code both screens show for one transfer. It covers both keys and both nonces, and the
 * sender's nonce was committed before the receiver chose its own, so an attacker in the middle gets one
 * blind one-in-a-million guess per attempt instead of being able to search for keys that collide.
 */
fun pairingCode(senderFingerprint: String, receiverFingerprint: String, senderNonce: String, receiverNonce: String): String {
    val digest = sha256(
        "voidarray-pair-v3|${senderFingerprint.uppercase()}|${receiverFingerprint.uppercase()}|$senderNonce|$receiverNonce",
    )
    val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
        ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
    val code = (value % 1_000_000).toString().padStart(6, '0')
    return code.substring(0, 3) + " " + code.substring(3)
}

private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
