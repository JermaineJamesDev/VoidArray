package io.github.jermainejamesdev.voidarray

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import io.github.jermainejamesdev.voidarray.engine.DeviceIdentity
import io.github.jermainejamesdev.voidarray.engine.FileIdentityStore
import io.github.jermainejamesdev.voidarray.engine.IdentityStore
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/**
 * Keeps the identity in the Android Keystore, where the private key is held by the system (in secure
 * hardware where the device has it) and cannot be read out again, even by this app. A key from an older
 * install's file is imported once, so its fingerprint and existing pairings survive, and the file is then
 * deleted. If the Keystore refuses the key, the file store is used instead and the import is retried on
 * the next launch.
 */
class KeystoreIdentityStore(
    private val legacy: FileIdentityStore,
    private val log: (String) -> Unit,
) : IdentityStore {

    override fun load(): DeviceIdentity {
        runCatching { readKeystore() }
            .onFailure { log("Could not read the Keystore identity: $it") }
            .getOrNull()
            ?.let { identity ->
                legacy.delete()
                return identity
            }
        val source = legacy.readExisting() ?: DeviceIdentity.generate()
        return runCatching { import(source) }
            .onSuccess { legacy.delete() }
            .getOrElse {
                log("Keystore import failed, keeping the key in a file: $it")
                legacy.readExisting() ?: legacy.replace()
            }
    }

    override fun replace(): DeviceIdentity {
        // Generated in software, then imported: Keystore RSA generation can take several seconds on
        // some secure hardware, and the key only exists in app memory for the moment before import.
        val fresh = DeviceIdentity.generate()
        return runCatching { import(fresh) }
            .onSuccess { legacy.delete() }
            .getOrElse {
                log("Keystore import failed, keeping the key in a file: $it")
                runCatching { keyStore().deleteEntry(ALIAS) }
                legacy.replace()
            }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun readKeystore(): DeviceIdentity? {
        val entry = keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
        return DeviceIdentity(entry.privateKey, entry.certificate as X509Certificate)
    }

    private fun import(identity: DeviceIdentity): DeviceIdentity {
        val store = keyStore()
        store.setEntry(ALIAS, KeyStore.PrivateKeyEntry(identity.privateKey, arrayOf(identity.certificate)), tlsSigningProtection())
        val imported = readKeystore() ?: error("Keystore did not keep the imported key")
        try {
            checkUsableForTls(imported)
        } catch (e: Exception) {
            store.deleteEntry(ALIAS)
            throw e
        }
        return imported
    }

    /**
     * The TLS stack signs with a key it cannot read by calling Cipher "RSA/ECB/PKCS1Padding" (TLS 1.2
     * signatures) or "RSA/ECB/NoPadding" (PSS, which BoringSSL pads itself) on the private key, which the
     * Keystore maps to raw signing. So the key must allow signing with no digest and with both PKCS#1 and
     * no padding; the hashed variants cover callers that use Signature directly.
     */
    private fun tlsSigningProtection(): KeyProtection =
        KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()

    /** Runs both raw operations TLS will need, so a Keystore that cannot do them is found now, not mid-handshake. */
    private fun checkUsableForTls(identity: DeviceIdentity) {
        val modulusBytes = (identity.certificate.publicKey as RSAPublicKey).modulus.bitLength() / 8
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, identity.privateKey)
            doFinal(ByteArray(51) { 1 })
        }
        Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, identity.privateKey)
            // A leading zero keeps the block numerically below the modulus.
            doFinal(ByteArray(modulusBytes).also { it[modulusBytes - 1] = 1 })
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "voidarray-identity"
    }
}
