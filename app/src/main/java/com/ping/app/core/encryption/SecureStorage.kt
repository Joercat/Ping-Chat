// core/encryption/SecureStorage.kt
package com.ping.app.core.encryption

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
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
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "ping_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    companion object {
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
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
        val keyString = encryptedPrefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val keyBytes = Base64.decode(keyString)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(keySpec)
    }
    
    @OptIn(ExperimentalEncodingApi::class)
    fun getPublicKey(): PublicKey? {
        val keyString = encryptedPrefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        val keyBytes = Base64.decode(keyString)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(keySpec)
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
    
    fun getDatabasePassphrase(): ByteArray {
        var passphrase = encryptedPrefs.getString(KEY_DB_PASSPHRASE, null)
        if (passphrase == null) {
            passphrase = generatePassphrase()
            encryptedPrefs.edit().putString(KEY_DB_PASSPHRASE, passphrase).apply()
        }
        return passphrase.toByteArray()
    }
    
    private fun generatePassphrase(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()"
        return (1..64).map { chars.random() }.joinToString("")
    }
    
    fun clear() {
        encryptedPrefs.edit().clear().apply()
    }
}
