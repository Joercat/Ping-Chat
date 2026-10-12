package com.ping.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.ping.app.core.crypto.Bytes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The at-rest encryption layer.
 *
 * A single AES-256-GCM key lives in the Android Keystore (hardware backed, and on devices with a
 * secure element it is generated inside StrongBox). The key material can never leave the chip, so
 * even a full filesystem dump of a rooted device contains nothing readable.
 *
 * Everything sensitive — message bodies, session state, contact names, the database passphrase —
 * is sealed with this key before it touches storage.
 */
class Vault(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val MASTER_ALIAS = "ping.vault.v2"
        private const val PASSPHRASE_ALIAS = "ping.vault.dbpass.v2"
        private const val PREFS = "ping_vault"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val NONCE_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val AAD_MASTER = "PingVault/v2"
        private const val AAD_PASSPHRASE = "PingVaultDbPass/v2"
        private const val GCM = "AES/GCM/NoPadding"
    }

    // ------------------------------------------------------------------ master key

    private fun masterKey(): SecretKey {
        (keyStore.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generateKey(MASTER_ALIAS, strongBox = true)
    }

    private fun generateKey(alias: String, strongBox: Boolean): SecretKey {
        fun build(useStrongBox: Boolean): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            generator.init(builder.build())
            return generator.generateKey()
        }
        return try {
            build(strongBox)
        } catch (e: StrongBoxUnavailableException) {
            build(false)
        } catch (e: Exception) {
            build(false)
        }
    }

    // ------------------------------------------------------------------ sealing

    /** Returns `nonce || ciphertext || tag`. */
    fun seal(plaintext: ByteArray, aad: ByteArray = AAD_MASTER.toByteArray()): ByteArray {
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return Bytes.concat(cipher.iv, ciphertext)
    }

    fun open(sealed: ByteArray, aad: ByteArray = AAD_MASTER.toByteArray()): ByteArray {
        require(sealed.size > NONCE_LENGTH) { "sealed blob is too short" }
        val cipher = Cipher.getInstance(GCM)
        val nonce = sealed.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = sealed.copyOfRange(NONCE_LENGTH, sealed.size)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun sealString(value: String, aad: ByteArray = AAD_MASTER.toByteArray()): ByteArray =
        seal(value.toByteArray(Charsets.UTF_8), aad)

    fun openString(sealed: ByteArray, aad: ByteArray = AAD_MASTER.toByteArray()): String =
        String(open(sealed, aad), Charsets.UTF_8)

    // ------------------------------------------------------------------ database passphrase

    /**
     * Random 256-bit passphrase for the SQLCipher database, itself sealed by the Keystore key.
     * Without the Keystore (i.e. without this device) it is useless.
     */
    fun databasePassphrase(): ByteArray {
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let { encoded ->
            return try {
                open(Bytes.fromBase64Url(encoded), AAD_PASSPHRASE.toByteArray())
            } catch (e: Exception) {
                // Keystore lost the key (app reinstall, restore from a backup): start fresh.
                null
            } ?: createPassphrase()
        }
        return createPassphrase()
    }

    private fun createPassphrase(): ByteArray {
        val passphrase = Bytes.random(32)
        prefs.edit().putString(KEY_DB_PASSPHRASE, Bytes.toBase64Url(seal(passphrase, AAD_PASSPHRASE.toByteArray()))).apply()
        return passphrase
    }

    /** True when a database passphrase already exists (i.e. this is not a fresh install). */
    fun hasDatabasePassphrase(): Boolean = prefs.contains(KEY_DB_PASSPHRASE)

    // ------------------------------------------------------------------ destruction

    /** Destroys every key. Everything sealed before this call becomes undecryptable. */
    fun destroy() {
        for (alias in listOf(MASTER_ALIAS, PASSPHRASE_ALIAS)) {
            try {
                keyStore.deleteEntry(alias)
            } catch (e: Exception) {
                // nothing to delete
            }
        }
        prefs.edit().clear().commit()
    }

    /** Only used by tests and the diagnostics screen. */
    fun selfTest(): Boolean = try {
        val probe = Bytes.random(64)
        val sealed = seal(probe)
        val opened = open(sealed)
        opened.contentEquals(probe) && !sealed.copyOfRange(NONCE_LENGTH, sealed.size).contentEquals(probe)
    } catch (e: Exception) {
        false
    }
}
