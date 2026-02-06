// core/encryption/SecureStorage.kt
package com.ping.app.core.encryption

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If encrypted prefs fail, clear and recreate
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
    
    companion object {
        private const val PREFS_NAME = "ping_secure_prefs"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val PASSPHRASE_LENGTH = 64
    }
    
    @OptIn(ExperimentalEncodingApi::class)
    fun saveKeyPair(keyPair: KeyPair) {
        encryptedPrefs.edit()
            .putString(KEY_PRIVATE_KEY, Base64.encode(keyPair.private.encoded))
            .putString(KEY_PUBLIC_KEY, Base64.encode(keyPair.public.encoded))
            .apply()
    }
    
    @OptIn(ExperimentalEncodingApi::class)
    fun getPrivateKey(): PrivateKey? {
        return try {
            val keyString = encryptedPrefs.getString(KEY_PRIVATE_KEY, null) ?: return null
            val keyBytes = Base64.decode(keyString)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePrivate(keySpec)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    @OptIn(ExperimentalEncodingApi::class)
    fun getPublicKey(): PublicKey? {
        return try {
            val keyString = encryptedPrefs.getString(KEY_PUBLIC_KEY, null) ?: return null
            val keyBytes = Base64.decode(keyString)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePublic(keySpec)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun saveUserId(userId: String) {
        encryptedPrefs.edit().putString(KEY_USER_ID, userId).apply()
    }
    
    fun getUserId(): String? {
        return encryptedPrefs.getString(KEY_USER_ID, null)
    }
    
    fun saveDeviceId(deviceId: String) {
        encryptedPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }
    
    fun getDeviceId(): String? {
        return encryptedPrefs.getString(KEY_DEVICE_ID, null)
    }
    
    /**
     * Gets the database passphrase, generating one if it doesn't exist.
     * FIXED: Added null safety and always returns a valid passphrase.
     */
    fun getDatabasePassphrase(): ByteArray {
        return try {
            var passphrase = encryptedPrefs.getString(KEY_DB_PASSPHRASE, null)
            
            if (passphrase.isNullOrEmpty()) {
                passphrase = generateSecurePassphrase()
                encryptedPrefs.edit().putString(KEY_DB_PASSPHRASE, passphrase).apply()
            }
            
            passphrase.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: generate a temporary passphrase
            // This will cause data loss on app restart, but prevents crash
            generateSecurePassphrase().toByteArray(Charsets.UTF_8)
        }
    }
    
    /**
     * Generates a cryptographically secure passphrase.
     */
    private fun generateSecurePassphrase(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?"
        val secureRandom = SecureRandom()
        return (1..PASSPHRASE_LENGTH)
            .map { chars[secureRandom.nextInt(chars.length)] }
            .joinToString("")
    }
    
    /**
     * Checks if passphrase exists and is valid.
     */
    fun hasValidPassphrase(): Boolean {
        return try {
            val passphrase = encryptedPrefs.getString(KEY_DB_PASSPHRASE, null)
            !passphrase.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clears all stored secure data.
     */
    fun clear() {
        try {
            encryptedPrefs.edit().clear().apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Checks if user is properly set up.
     */
    fun isUserSetUp(): Boolean {
        return getUserId() != null && getPublicKey() != null
    }
}
