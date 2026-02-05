// core/encryption/KeyExchange.kt
package com.ping.app.core.encryption

import com.ping.app.data.models.User
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyExchange @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val secureStorage: SecureStorage
) {
    
    private var myKeyPair: KeyPair? = null
    private val sessionKeys = mutableMapOf<String, SecretKey>()
    
    fun initializeKeys(): KeyPair {
        if (myKeyPair == null) {
            // Try to load from secure storage first
            val storedPrivateKey = secureStorage.getPrivateKey()
            val storedPublicKey = secureStorage.getPublicKey()
            
            myKeyPair = if (storedPrivateKey != null && storedPublicKey != null) {
                KeyPair(storedPublicKey, storedPrivateKey)
            } else {
                val newKeyPair = cryptoManager.generateKeyPair()
                secureStorage.saveKeyPair(newKeyPair)
                newKeyPair
            }
        }
        return myKeyPair!!
    }
    
    fun getMyPublicKey(): PublicKey {
        return initializeKeys().public
    }
    
    fun getMyPublicKeyString(): String {
        return cryptoManager.publicKeyToString(getMyPublicKey())
    }
    
    fun establishSession(userId: String, peerPublicKeyString: String): SecretKey {
        val peerPublicKey = cryptoManager.stringToPublicKey(peerPublicKeyString)
        return establishSession(userId, peerPublicKey)
    }
    
    fun establishSession(userId: String, peerPublicKey: PublicKey): SecretKey {
        val myPrivateKey = initializeKeys().private
        val sharedSecret = cryptoManager.performKeyAgreement(myPrivateKey, peerPublicKey)
        val sessionKey = cryptoManager.deriveSessionKey(sharedSecret)
        sessionKeys[userId] = sessionKey
        return sessionKey
    }
    
    fun getSessionKey(userId: String): SecretKey? {
        return sessionKeys[userId]
    }
    
    fun hasSessionWith(userId: String): Boolean {
        return sessionKeys.containsKey(userId)
    }
    
    fun removeSession(userId: String) {
        sessionKeys.remove(userId)
    }
    
    fun clearAllSessions() {
        sessionKeys.clear()
    }
}
