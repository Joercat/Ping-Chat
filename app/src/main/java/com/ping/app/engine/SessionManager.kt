package com.ping.app.engine

import com.ping.app.core.crypto.IdentityKeyPair
import com.ping.app.core.crypto.PreKeyBundle
import com.ping.app.core.crypto.RatchetMessage
import com.ping.app.core.crypto.RatchetSession
import com.ping.app.core.crypto.X25519KeyPair
import com.ping.app.core.crypto.X3dh
import com.ping.app.core.protocol.Wire
import com.ping.app.data.Contact
import com.ping.app.data.CryptoStore
import com.ping.app.data.SessionRecord

/**
 * Turns the cryptographic primitives into conversations: it starts sessions (X3DH), keeps the
 * ratchet state in storage after every message, and seals/opens payloads.
 */
class SessionManager(private val cryptoStore: CryptoStore) {

    class Started(val session: RatchetSession, val initiation: X3dh.InitiationResult)

    fun load(peerHash: String): RatchetSession? {
        val record = cryptoStore.session(peerHash) ?: return null
        return try {
            RatchetSession.fromBytes(record.state)
        } catch (e: Exception) {
            // A session we cannot parse is worse than none: drop it and let a new handshake happen.
            cryptoStore.deleteSession(peerHash)
            null
        }
    }

    fun record(peerHash: String): SessionRecord? = cryptoStore.session(peerHash)

    fun save(peerHash: String, session: RatchetSession, peerIdentityKey: ByteArray?, handshakeSent: Boolean) {
        cryptoStore.saveSession(
            SessionRecord(
                peerHash = peerHash,
                state = session.toBytes(),
                tag = session.sessionTag(),
                updatedAt = System.currentTimeMillis(),
                peerIdentityKey = peerIdentityKey,
                handshakeSent = handshakeSent
            )
        )
    }

    fun forget(peerHash: String) = cryptoStore.deleteSession(peerHash)

    /** We start the conversation: run X3DH against the peer's published bundle. */
    fun startOutbound(identity: IdentityKeyPair, bundle: PreKeyBundle): Started {
        val initiation = X3dh.initiate(identity, bundle)
        val session = RatchetSession.initiator(
            masterSecret = initiation.masterSecret,
            remoteSignedPreKey = bundle.signedPreKey,
            associatedData = initiation.associatedData
        )
        return Started(session, initiation)
    }

    /**
     * Somebody started a conversation with us: rebuild the same secret from our own pre-keys.
     *
     * Returns null when the handshake names a pre-key we no longer hold (for example after a
     * restore) — the sender will be told to start over.
     */
    fun completeInbound(
        identity: IdentityKeyPair,
        handshake: Wire.HandshakeInit,
        signedPreKey: X25519KeyPair,
        oneTimePreKey: X25519KeyPair?
    ): RatchetSession {
        val masterSecret = X3dh.respond(
            identity = identity,
            signedPreKey = signedPreKey,
            oneTimePreKey = oneTimePreKey,
            initiatorSigningKey = handshake.senderSigningKey,
            initiatorDhKey = handshake.senderDhIdentity,
            initiatorEphemeral = handshake.ephemeralKey
        )
        val associatedData = com.ping.app.core.crypto.Bytes.concat(
            handshake.senderSigningKey, identity.signing.publicKey
        )
        return RatchetSession.responder(masterSecret, signedPreKey, associatedData)
    }

    fun encrypt(session: RatchetSession, payload: Wire.Payload): RatchetMessage =
        session.encrypt(payload.encode())

    fun decrypt(session: RatchetSession, message: RatchetMessage): Wire.Payload =
        Wire.Payload.decode(session.decrypt(message.header, message.ciphertext))

    /**
     * Wraps a first message in the handshake envelope the receiver needs: which of *their*
     * pre-keys we used, our identity keys and the first ratchet message.
     */
    fun buildHandshake(
        identity: IdentityKeyPair,
        started: Started,
        bundle: PreKeyBundle,
        message: RatchetMessage
    ): Wire.HandshakeInit = Wire.HandshakeInit(
        senderSigningKey = identity.signing.publicKey,
        senderDhIdentity = identity.dh.publicKey,
        senderBindingSignature = identity.dhBinding,
        ephemeralKey = started.initiation.ephemeral.publicKey,
        signedPreKeyId = bundle.signedPreKeyId,
        oneTimePreKeyId = started.initiation.usedOneTimePreKeyId,
        firstMessage = message
    )
}
