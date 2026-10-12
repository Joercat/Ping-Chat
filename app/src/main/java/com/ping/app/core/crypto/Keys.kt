package com.ping.app.core.crypto

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Minimal DER reader/writer for the two shapes we care about:
 *
 *  - SubjectPublicKeyInfo  (RFC 5280) carrying an RFC 8410 key
 *  - PrivateKeyInfo        (RFC 5208) carrying an RFC 8410 key
 *
 * Keys are kept as raw 32-byte curve points everywhere in Ping — the compact form is what goes
 * into QR codes, wire packets and the relay. DER is only built when handing a key to the JCE.
 */
internal object Der {

    // 30 2a 30 05 06 03 2b 65 {6e|70} 03 21 00  => 44 bytes
    private fun spkiHeader(oidLast: Int) = byteArrayOf(
        0x30, 0x2a,
        0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, oidLast.toByte(),
        0x03, 0x21, 0x00
    )

    // 30 2e 02 01 00 30 05 06 03 2b 65 {6e|70} 04 22 04 20 => 48 bytes
    private fun pkcs8Header(oidLast: Int) = byteArrayOf(
        0x30, 0x2e,
        0x02, 0x01, 0x00,
        0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, oidLast.toByte(),
        0x04, 0x22, 0x04, 0x20
    )

    const val OID_X25519 = 0x6e
    const val OID_ED25519 = 0x70

    fun spki(raw: ByteArray, oidLast: Int): ByteArray {
        require(raw.size == 32) { "curve25519 keys are 32 bytes" }
        return Bytes.concat(spkiHeader(oidLast), raw)
    }

    fun pkcs8(raw: ByteArray, oidLast: Int): ByteArray {
        require(raw.size == 32) { "curve25519 keys are 32 bytes" }
        return Bytes.concat(pkcs8Header(oidLast), raw)
    }

    /** Extracts the raw 32-byte key from a SubjectPublicKeyInfo blob. */
    fun rawFromSpki(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        reader.readSequence()
        reader.readSequence()          // AlgorithmIdentifier
        reader.skipElement()           // OID (+ optional params)
        reader.endSequence()
        val bitString = reader.readElement(0x03)
        // BIT STRING: first content byte is the number of unused bits (0 for keys).
        require(bitString.size == 33 && bitString[0].toInt() == 0) { "unexpected BIT STRING length" }
        return bitString.copyOfRange(1, 33)
    }

    /** Extracts the raw 32-byte key from a PrivateKeyInfo blob. */
    fun rawFromPkcs8(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        reader.readSequence()
        reader.readElement(0x02)       // version
        reader.readSequence()          // AlgorithmIdentifier
        reader.skipElement()           // OID (+ optional params)
        reader.endSequence()
        val octets = reader.readElement(0x04)
        return if (octets.size == 34 && octets[0].toInt() == 0x04 && octets[1].toInt() == 0x20) {
            octets.copyOfRange(2, 34)
        } else if (octets.size == 32) {
            octets
        } else {
            throw WireFormatException("unexpected PKCS#8 private key length ${octets.size}")
        }
    }

    /** Very small, strict DER reader — only handles definite-length, single-byte-tag elements. */
    private class DerReader(private val data: ByteArray) {
        private var position = 0
        private val sequenceEnds = ArrayDeque<Int>()

        fun readElement(expectedTag: Int): ByteArray {
            if (position >= data.size) throw WireFormatException("truncated DER")
            val tag = data[position].toInt() and 0xff
            if (tag != expectedTag) throw WireFormatException("DER tag 0x${tag.toString(16)} != expected 0x${expectedTag.toString(16)}")
            position++
            val length = readLength()
            if (position + length > data.size) throw WireFormatException("DER element overruns buffer")
            val content = data.copyOfRange(position, position + length)
            position += length
            return content
        }

        /** Enters a SEQUENCE: the reader is moved to its first child element. */
        fun readSequence() {
            if (position >= data.size) throw WireFormatException("truncated DER")
            if (data[position].toInt() and 0xff != 0x30) throw WireFormatException("expected DER SEQUENCE")
            position++
            val length = readLength()
            if (position + length > data.size) throw WireFormatException("DER sequence overruns buffer")
            sequenceEnds.addLast(position + length)
            // position now points at the first child
        }

        /** Leaves the current SEQUENCE. */
        fun endSequence() {
            val end = sequenceEnds.removeLastOrNull() ?: throw WireFormatException("no open DER sequence")
            position = end
        }

        fun skipElement() {
            if (position >= data.size) throw WireFormatException("truncated DER")
            position++
            val length = readLength()
            if (position + length > data.size) throw WireFormatException("DER element overruns buffer")
            position += length
        }

        private fun readLength(): Int {
            if (position >= data.size) throw WireFormatException("truncated DER length")
            val first = data[position].toInt() and 0xff
            position++
            if (first < 0x80) return first
            val count = first and 0x7f
            if (count == 0 || count > 3) throw WireFormatException("unsupported DER length")
            var length = 0
            repeat(count) {
                if (position >= data.size) throw WireFormatException("truncated DER length")
                length = (length shl 8) or (data[position].toInt() and 0xff)
                position++
            }
            return length
        }
    }
}

/**
 * An X25519 (Curve25519 Diffie-Hellman) key pair held as raw 32-byte values.
 */
class X25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {

    init {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
    }

    private fun privateJce(): PrivateKey =
        PingCrypto.keyFactory("X25519").generatePrivate(PKCS8EncodedKeySpec(Der.pkcs8(privateKey, Der.OID_X25519)))

    private fun publicJce(raw: ByteArray): PublicKey =
        PingCrypto.keyFactory("X25519").generatePublic(X509EncodedKeySpec(Der.spki(raw, Der.OID_X25519)))

    /**
     * Raw X25519 Diffie-Hellman.
     *
     * Low-order (small subgroup) public keys are refused: either the provider rejects them
     * (Conscrypt/JDK throw InvalidKeyException) or the resulting secret is all zeros, which is
     * checked explicitly so the behaviour is identical on every provider.
     */
    fun agreeWith(peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == 32) { "X25519 public key must be 32 bytes" }
        val secret = try {
            val agreement = PingCrypto.keyAgreement("X25519")
            agreement.init(privateJce())
            agreement.doPhase(publicJce(peerPublicKey), true)
            agreement.generateSecret()
        } catch (e: Exception) {
            throw AuthenticationFailedException("X25519 agreement rejected: ${e.message}")
        }
        if (secret.size != 32) throw WireFormatException("unexpected X25519 secret length ${secret.size}")
        var acc = 0
        for (b in secret) acc = acc or b.toInt()
        if (acc == 0) {
            Bytes.wipe(secret)
            throw AuthenticationFailedException("X25519 shared secret is all zero (low order point)")
        }
        return secret
    }

    companion object {
        fun generate(): X25519KeyPair {
            val pair = PingCrypto.keyPairGenerator("X25519").generateKeyPair()
            return X25519KeyPair(
                privateKey = Der.rawFromPkcs8(pair.private.encoded),
                publicKey = Der.rawFromSpki(pair.public.encoded)
            )
        }

        fun fromPrivateAndPublic(privateKey: ByteArray, publicKey: ByteArray) = X25519KeyPair(privateKey, publicKey)
    }
}

/** Ed25519 signing key pair held as raw 32-byte values. */
class Ed25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    init {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes" }
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
    }

    fun sign(message: ByteArray): ByteArray {
        val signature = PingCrypto.signature("Ed25519")
        val key = PingCrypto.keyFactory("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(Der.pkcs8(privateKey, Der.OID_ED25519)))
        signature.initSign(key)
        signature.update(message)
        return signature.sign()
    }

    companion object {
        fun generate(): Ed25519KeyPair {
            val pair = PingCrypto.keyPairGenerator("Ed25519").generateKeyPair()
            return Ed25519KeyPair(
                privateKey = Der.rawFromPkcs8(pair.private.encoded),
                publicKey = Der.rawFromSpki(pair.public.encoded)
            )
        }
    }
}

object Ed25519 {
    /** Strict signature verification. Returns false for malformed input instead of throwing. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val verifier = PingCrypto.signature("Ed25519")
            val key = PingCrypto.keyFactory("Ed25519")
                .generatePublic(X509EncodedKeySpec(Der.spki(publicKey, Der.OID_ED25519)))
            verifier.initVerify(key)
            verifier.update(message)
            verifier.verify(signature)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * A Ping identity: an Ed25519 signing key plus a separate X25519 key used for key agreement.
 *
 * The signing key is the long-term name of the device — safety numbers, contact cards and every
 * bundle signature are built on it. The Diffie-Hellman key is bound to it with a self signature
 * so that nobody can replace a peer's DH key while keeping their identity.
 */
class IdentityKeyPair(
    val signing: Ed25519KeyPair,
    val dh: X25519KeyPair
) {
    /** Signature proving `dh.publicKey` belongs to the owner of `signing.publicKey`. */
    val dhBinding: ByteArray by lazy { signing.sign(bindingMessage(signing.publicKey, dh.publicKey)) }

    companion object {
        fun generate(): IdentityKeyPair = IdentityKeyPair(Ed25519KeyPair.generate(), X25519KeyPair.generate())

        fun bindingMessage(signingKey: ByteArray, dhKey: ByteArray): ByteArray =
            Bytes.concat(Bytes.utf8("PingIdentity/v1"), signingKey, dhKey)

        /** Verifies that `dhPublicKey` is bound to `signingPublicKey` by `signature`. */
        fun verifyBinding(signingPublicKey: ByteArray, dhPublicKey: ByteArray, signature: ByteArray): Boolean =
            Ed25519.verify(signingPublicKey, bindingMessage(signingPublicKey, dhPublicKey), signature)
    }
}

/** Short, human-comparable fingerprint of a public key (hex, first 8 bytes). */
object ShortId {
    fun of(publicKey: ByteArray): String = Bytes.toHex(Bytes.sha256(publicKey).copyOfRange(0, 8))
}
