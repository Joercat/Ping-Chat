package com.ping.app.core.crypto

/**
 * A signed pre-key: a medium-term X25519 key whose public half is published to the relay and
 * signed by the identity key. Rotated regularly; the recipient keeps using it for a while after
 * rotation so that messages already on their way still decrypt.
 */
class SignedPreKey(
    val id: Int,
    val keyPair: X25519KeyPair,
    val signature: ByteArray,
    val createdAt: Long
) {
    companion object {
        const val LIFETIME_MS = 7L * 24 * 60 * 60 * 1000      // publish a fresh one weekly
        const val RETIRE_MS = 14L * 24 * 60 * 60 * 1000       // then keep the old one for 2 weeks
    }
}

/** A one-time pre-key: used at most once, gives the first message forward secrecy. */
class OneTimePreKey(
    val id: Int,
    val keyPair: X25519KeyPair
)

/**
 * Everything a sender needs to start a session with somebody who is offline.
 *
 * Bundles only ever contain public keys, so publishing them to a relay reveals nothing about the
 * conversation — but they are signed so that a hostile relay cannot swap in its own keys.
 */
class PreKeyBundle(
    val signingKey: ByteArray,
    val dhIdentityKey: ByteArray,
    val dhBindingSignature: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val oneTimePreKeyId: Int?,
    val oneTimePreKey: ByteArray?,
    val publishedAt: Long
) {

    /** The exact bytes covered by [signature]; stable across encoders. */
    fun canonicalBytes(): ByteArray = BufWriter()
        .bytes(Bytes.utf8("PingBundle/v1"))
        .bytes(signingKey)
        .bytes(dhIdentityKey)
        .bytes(dhBindingSignature)
        .u32(signedPreKeyId)
        .bytes(signedPreKey)
        .bytes(signedPreKeySignature)
        .u32(oneTimePreKeyId ?: -1)
        .bytes(oneTimePreKey ?: ByteArray(0))
        .u64(publishedAt)
        .toByteArray()

    /** Verifies both signature layers of the bundle. */
    fun verify(): Boolean {
        if (signingKey.size != 32 || dhIdentityKey.size != 32 || signedPreKey.size != 32) return false
        if (signedPreKeySignature.size != 64) return false
        if (!IdentityKeyPair.verifyBinding(signingKey, dhIdentityKey, dhBindingSignature)) return false
        return Ed25519.verify(signingKey, signedPreKeyMessage(signedPreKeyId, signedPreKey), signedPreKeySignature)
    }

    companion object {
        fun signedPreKeyMessage(id: Int, publicKey: ByteArray): ByteArray =
            Bytes.concat(Bytes.utf8("PingSignedPreKey/v1"), byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte()), publicKey)

        /** Builds the bundle a client publishes for itself. */
        fun create(
            identity: IdentityKeyPair,
            signedPreKey: SignedPreKey,
            oneTimePreKey: OneTimePreKey?,
            now: Long
        ): PreKeyBundle = PreKeyBundle(
            signingKey = identity.signing.publicKey,
            dhIdentityKey = identity.dh.publicKey,
            dhBindingSignature = identity.dhBinding,
            signedPreKeyId = signedPreKey.id,
            signedPreKey = signedPreKey.keyPair.publicKey,
            signedPreKeySignature = signedPreKey.signature,
            oneTimePreKeyId = oneTimePreKey?.id,
            oneTimePreKey = oneTimePreKey?.keyPair?.publicKey,
            publishedAt = now
        )

        fun newSignedPreKey(identity: IdentityKeyPair, id: Int, now: Long): SignedPreKey {
            val pair = X25519KeyPair.generate()
            val signature = identity.signing.sign(signedPreKeyMessage(id, pair.publicKey))
            return SignedPreKey(id, pair, signature, now)
        }

        fun newOneTimePreKey(id: Int): OneTimePreKey = OneTimePreKey(id, X25519KeyPair.generate())
    }
}
