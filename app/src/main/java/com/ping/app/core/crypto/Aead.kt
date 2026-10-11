package com.ping.app.core.crypto

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for Ping.
 *
 * ChaCha20-Poly1305 (RFC 8439) with a **unique nonce per message**: every message key is used
 * exactly once, and its nonce is derived from that key, so nonce reuse is structurally impossible
 * even if the random number generator misbehaves.
 *
 * Additional authenticated data (AAD) binds the ciphertext to the message header (ratchet public
 * key, message counters) so headers cannot be swapped between messages.
 */
object Aead {

    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16

    private const val ALGORITHM = "ChaCha20-Poly1305"

    /** Encrypts `plaintext`, returning `ciphertext || tag`. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "AEAD key must be $KEY_LEN bytes, got ${key.size}" }
        require(nonce.size == NONCE_LEN) { "AEAD nonce must be $NONCE_LEN bytes, got ${nonce.size}" }
        val cipher = PingCrypto.cipher(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts and authenticates. Throws [AuthenticationFailedException] if the tag does not
     * verify — callers must treat that as an attack/corruption and never as "try again".
     */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "AEAD key must be $KEY_LEN bytes, got ${key.size}" }
        require(nonce.size == NONCE_LEN) { "AEAD nonce must be $NONCE_LEN bytes, got ${nonce.size}" }
        if (ciphertext.size < TAG_LEN) throw AuthenticationFailedException("ciphertext shorter than tag")
        val cipher = PingCrypto.cipher(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw AuthenticationFailedException()
        } catch (e: GeneralSecurityException) {
            throw AuthenticationFailedException(e.message ?: "decryption failed")
        }
    }

    /** Fresh random nonce. Only used where a key is guaranteed unique (one-off blobs). */
    fun randomNonce(): ByteArray = Bytes.random(NONCE_LEN)

    /**
     * Expands a single-use message key into its 32-byte encryption key and 12-byte nonce.
     * Distinct `info` labels guarantee key and nonce are independent.
     */
    fun expandMessageKey(messageKey: ByteArray): MessageKey {
        val key = Hkdf.derive(messageKey, Bytes.utf8("PingMsg/v1:key"), KEY_LEN)
        val nonce = Hkdf.derive(messageKey, Bytes.utf8("PingMsg/v1:nonce"), NONCE_LEN)
        return MessageKey(key, nonce)
    }

    /** One-time key + nonce pair derived from a message key. */
    class MessageKey(val key: ByteArray, val nonce: ByteArray) {
        fun destroy() {
            Bytes.wipe(key, nonce)
        }
    }
}
