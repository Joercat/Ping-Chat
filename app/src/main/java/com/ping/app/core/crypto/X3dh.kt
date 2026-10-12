package com.ping.app.core.crypto

/**
 * X3DH (extended triple Diffie-Hellman) — the asynchronous key agreement used to start a session
 * with someone who may be offline. This is the same family of handshake Signal uses.
 *
 *   DH1 = DH(IK_A, SPK_B)     static  ×  signed pre-key
 *   DH2 = DH(EK_A, IK_B)      ephemeral × static
 *   DH3 = DH(EK_A, SPK_B)     ephemeral × signed pre-key
 *   DH4 = DH(EK_A, OPK_B)     ephemeral × one-time pre-key   (when the server still had one)
 *
 *   SK  = HKDF( 0xFF*32 ‖ DH1 ‖ DH2 ‖ DH3 ‖ [DH4], salt = 0, info = "PingX3DH/v1" ‖ IK_A ‖ IK_B )
 *
 * Mixing both identity keys into the info string binds the resulting session to both parties, so
 * a message cannot be replayed against a different contact.
 */
object X3dh {

    private const val INFO = "PingX3DH/v1"
    private val F = ByteArray(32) { 0xFF.toByte() }

    class InitiationResult(
        /** The shared secret which keys the double ratchet. */
        val masterSecret: ByteArray,
        /** Ephemeral (and initial ratchet) key pair of the initiator. */
        val ephemeral: X25519KeyPair,
        val usedOneTimePreKeyId: Int?,
        val associatedData: ByteArray
    )

    /** Initiator side (Alice). */
    fun initiate(identity: IdentityKeyPair, bundle: PreKeyBundle): InitiationResult {
        require(bundle.verify()) { "pre-key bundle signature is invalid" }
        val ephemeral = X25519KeyPair.generate()

        val dh1 = identity.dh.agreeWith(bundle.signedPreKey)
        val dh2 = ephemeral.agreeWith(bundle.dhIdentityKey)
        val dh3 = ephemeral.agreeWith(bundle.signedPreKey)
        val dh4 = bundle.oneTimePreKey?.let { ephemeral.agreeWith(it) }

        val associatedData = Bytes.concat(identity.signing.publicKey, bundle.signingKey)
        val masterSecret = kdf(
            Bytes.concat(F, dh1, dh2, dh3, dh4 ?: ByteArray(0)),
            associatedData
        )
        Bytes.wipe(dh1, dh2, dh3, dh4)

        return InitiationResult(masterSecret, ephemeral, bundle.oneTimePreKeyId, associatedData)
    }

    /** Responder side (Bob), recomputing the same secret from his own private keys. */
    fun respond(
        identity: IdentityKeyPair,
        signedPreKey: X25519KeyPair,
        oneTimePreKey: X25519KeyPair?,
        initiatorSigningKey: ByteArray,
        initiatorDhKey: ByteArray,
        initiatorEphemeral: ByteArray
    ): ByteArray {
        val dh1 = signedPreKey.agreeWith(initiatorDhKey)
        val dh2 = identity.dh.agreeWith(initiatorEphemeral)
        val dh3 = signedPreKey.agreeWith(initiatorEphemeral)
        val dh4 = oneTimePreKey?.agreeWith(initiatorEphemeral)

        val associatedData = Bytes.concat(initiatorSigningKey, identity.signing.publicKey)
        val masterSecret = kdf(
            Bytes.concat(F, dh1, dh2, dh3, dh4 ?: ByteArray(0)),
            associatedData
        )
        Bytes.wipe(dh1, dh2, dh3, dh4)
        return masterSecret
    }

    private fun kdf(inputKeyMaterial: ByteArray, associatedData: ByteArray): ByteArray =
        Hkdf.derive(
            inputKeyMaterial = inputKeyMaterial,
            salt = ByteArray(32),
            info = Bytes.concat(Bytes.utf8(INFO), associatedData),
            length = 32
        )
}
