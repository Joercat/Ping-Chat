// core/encryption/CryptoManager.kt
package com.ping.app.core.encryption

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Singleton
class CryptoManager @Inject constructor(
    private val secureStorage: SecureStorage
) {
    
    init {
        Security.addProvider(BouncyCastleProvider())
    }
    
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    
    companion object {
        private const val KEY_ALIAS = "ping_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val EC_CURVE = "secp256r1"
    }
    
    // Generate ECDH key pair for key exchange
    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC")
        keyPairGenerator.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }
    
    // Get or create master key for local encryption
    private fun getMasterKey(): SecretKey {
        return if (keyStore.containsAlias(KEY_ALIAS)) {
            (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            generateMasterKey()
        }
    }
    
    private fun generateMasterKey(): SecretKey {
        val keyGenerator = javax.crypto.KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
    
    // Perform ECDH key agreement
    fun performKeyAgreement(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH", "BC")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }
    
    // Derive session key from shared secret using HKDF
    fun deriveSessionKey(sharedSecret: ByteArray, salt: ByteArray = ByteArray(32)): SecretKey {
        val hkdf = hkdfExpand(sharedSecret, salt, 32)
        return SecretKeySpec(hkdf, "AES")
    }
    
    private fun hkdfExpand(ikm: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1
        
        while (offset < length) {
            mac.update(t)
            mac.update(i.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            i++
        }
        
        return result
    }
    
    // Encrypt message with session key
    fun encryptMessage(plaintext: ByteArray, sessionKey: SecretKey): EncryptedData {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey)
        
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        
        return EncryptedData(iv, ciphertext)
    }
    
    // Decrypt message with session key
    fun decryptMessage(encryptedData: EncryptedData, sessionKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec)
        
        return cipher.doFinal(encryptedData.ciphertext)
    }
    
    // Encrypt for local storage
    fun encryptLocal(data: ByteArray): EncryptedData {
        return encryptMessage(data, getMasterKey())
    }
    
    // Decrypt from local storage
    fun decryptLocal(encryptedData: EncryptedData): ByteArray {
        return decryptMessage(encryptedData, getMasterKey())
    }
    
    // Encrypt file
    fun encryptFile(fileBytes: ByteArray, sessionKey: SecretKey): EncryptedData {
        return encryptMessage(fileBytes, sessionKey)
    }
    
    // Decrypt file
    fun decryptFile(encryptedData: EncryptedData, sessionKey: SecretKey): ByteArray {
        return decryptMessage(encryptedData, sessionKey)
    }
    
    @OptIn(ExperimentalEncodingApi::class)
    fun publicKeyToString(publicKey: PublicKey): String {
        return Base64.encode(publicKey.encoded)
    }
    
    @OptIn(ExperimentalEncodingApi::class)
    fun stringToPublicKey(keyString: String): PublicKey {
        val keyBytes = Base64.decode(keyString)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("EC", "BC")
        return keyFactory.generatePublic(keySpec)
    }
    
    // Calculate file checksum
    fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

data class EncryptedData(
    val iv: ByteArray,
    val ciphertext: ByteArray
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun toByteArray(): ByteArray {
        return iv + ciphertext
    }
    
    companion object {
        fun fromByteArray(data: ByteArray, ivLength: Int = 12): EncryptedData {
            val iv = data.copyOfRange(0, ivLength)
            val ciphertext = data.copyOfRange(ivLength, data.size)
            return EncryptedData(iv, ciphertext)
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedData
        return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
    }
    
    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}
