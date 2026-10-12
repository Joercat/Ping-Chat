package com.ping.app.core.crypto

/**
 * Group messages, with a shared *sender key* per epoch.
 *
 * A group has one 32-byte epoch key. Every member receives it over their ordinary pairwise Double
 * Ratchet (a `GroupKey` payload), so distributing it is exactly as private as a one-to-one message:
 * the relay never sees it and cannot subscribe to the group.
 *
 * Each message is sealed with a key derived from the epoch key, the sender's identity signing key and
 * a counter — `HKDF(epochKey, "PingGroup/v1" ‖ senderSigningKey ‖ counter)`. Because the counter is
 * unique per sender and never repeats (it is `max(now, last + 1)`), keys and nonces are never reused
 * even across restarts or when two messages are sent in the same millisecond.
 *
 * The sender *signs* the header and ciphertext with their Ed25519 identity key, and the blob carries
 * that key. Every member verifies the signature against the sender's published signing key, so a
 * group member cannot forge another member's message even though everyone holds the same epoch key.
 * [open] checks that the signature matches the key inside the blob; the caller checks the key against
 * the contact record, which is what binds the message to a real identity.
 *
 * Counter replay protection: the receiver remembers the highest counter seen per sender
 * (`group_state.highest_counter`) and rejects anything that is not strictly higher, so a relay or a
 * member cannot replay an old message, and a duplicate delivery is ignored.
 *
 * Honest limits, so nobody has to guess: possession of the epoch key decrypts every message of that
 * epoch, so a member who is removed from the group keeps reading old epochs (rotating the key ends
 * this — which the engine does whenever the membership changes) — and a member's device compromise
 * exposes the epochs it still holds. Individual sender keys per member (MLS-style) would narrow that
 * further; this is the design that keeps group chat compatible with the existing pairwise protocol.
 */
object GroupCrypto {

    const val VERSION = 1
    const val GROUP_ID_LEN = 16
    const val KEY_LEN = 32

    /** Everything a member needs to join an epoch. Travels inside the pairwise ratchet. */
    class EpochKey(
        val groupId: ByteArray,
        val epoch: Int,
        val key: ByteArray
    ) {
        init {
            require(groupId.size == GROUP_ID_LEN) { "group id must be $GROUP_ID_LEN bytes" }
            require(key.size == KEY_LEN) { "epoch key must be $KEY_LEN bytes" }
            require(epoch >= 1) { "epochs start at 1" }
        }
    }

    /** A sealed group message: header, signature and ciphertext. */
    class Sealed(
        val groupId: ByteArray,
        val epoch: Int,
        val counter: Long,
        val senderSigningKey: ByteArray,
        val blob: ByteArray
    ) {
        fun encode(): ByteArray = BufWriter(64 + blob.size)
            .u8(VERSION)
            .blob(groupId)
            .u32(epoch)
            .u64(counter)
            .bytes(senderSigningKey)
            .blob(blob)
            .toByteArray()

        companion object {
            fun decode(bytes: ByteArray): Sealed {
                val reader = BufReader(bytes)
                val version = reader.u8()
                if (version != VERSION) throw WireFormatException("unsupported group message version $version")
                val groupId = reader.blob()
                val epoch = reader.u32()
                val counter = reader.u64()
                val senderKey = reader.bytes(32)
                val blob = reader.blob()
                reader.expectEnd()
                if (groupId.size != GROUP_ID_LEN) throw WireFormatException("bad group id length")
                if (epoch < 1) throw WireFormatException("bad epoch $epoch")
                if (counter < 0) throw WireFormatException("bad counter")
                return Sealed(groupId, epoch, counter, senderKey, blob)
            }
        }
    }

    /**
     * The per-message key. Deterministic in (epoch key, sender, counter), which is what makes
     * out-of-order delivery work without keeping a chain state per sender.
     */
    fun messageKey(epochKey: ByteArray, senderSigningKey: ByteArray, counter: Long): ByteArray =
        Hkdf.derive(
            inputKeyMaterial = epochKey,
            info = BufWriter(64)
                .bytes(Bytes.utf8("PingGroup/v1"))
                .bytes(senderSigningKey)
                .u64(counter)
                .toByteArray(),
            length = Aead.KEY_LEN
        )

    /** Nonce, derived from the same inputs: unique whenever the message key is. */
    fun nonce(epochKey: ByteArray, senderSigningKey: ByteArray, counter: Long): ByteArray =
        Hkdf.derive(
            inputKeyMaterial = epochKey,
            info = BufWriter(64)
                .bytes(Bytes.utf8("PingGroupNonce/v1"))
                .bytes(senderSigningKey)
                .u64(counter)
                .toByteArray(),
            length = Aead.NONCE_LEN
        )

    /** AAD binds the ciphertext to the group, the epoch, the sender and the counter. */
    fun additionalData(groupId: ByteArray, epoch: Int, counter: Long, senderSigningKey: ByteArray): ByteArray =
        BufWriter(64)
            .bytes(Bytes.utf8("PingGroupAAD/v1"))
            .blob(groupId)
            .u32(epoch)
            .u64(counter)
            .bytes(senderSigningKey)
            .toByteArray()

    /**
     * Seals one group message. `counter` must never repeat for the same (group, epoch, sender):
     * the engine hands out `max(now, last + 1)`, which survives restarts.
     */
    fun seal(
        epochKey: EpochKey,
        sender: IdentityKeyPair,
        counter: Long,
        plaintext: ByteArray
    ): Sealed {
        require(counter >= 0) { "counter must not be negative" }
        val senderKey = sender.signing.publicKey
        val aad = additionalData(epochKey.groupId, epochKey.epoch, counter, senderKey)
        val blob = Aead.seal(
            key = messageKey(epochKey.key, senderKey, counter),
            nonce = nonce(epochKey.key, senderKey, counter),
            aad = aad,
            plaintext = plaintext
        )
        val signature = sender.signing.sign(aad + blob)
        return Sealed(epochKey.groupId, epochKey.epoch, counter, senderKey, Bytes.concat(signature, blob))
    }

    /**
     * Opens a sealed group message.
     *
     * Verifies the sender's signature first (against the key carried in the blob) and only then
     * decrypts, so a forged or corrupted message never reaches the plaintext path. The caller still
     * has to check that [Sealed.senderSigningKey] belongs to the member who claims it.
     */
    fun open(epochKey: EpochKey, sealed: Sealed): ByteArray {
        if (!epochKey.groupId.contentEquals(sealed.groupId)) throw WireFormatException("group id mismatch")
        if (epochKey.epoch != sealed.epoch) {
            throw WireFormatException("message is from epoch ${sealed.epoch}, key is epoch ${epochKey.epoch}")
        }
        if (sealed.blob.size <= 64) throw WireFormatException("group message is too short")
        val signature = sealed.blob.copyOfRange(0, 64)
        val ciphertext = sealed.blob.copyOfRange(64, sealed.blob.size)
        val aad = additionalData(sealed.groupId, sealed.epoch, sealed.counter, sealed.senderSigningKey)
        if (!Ed25519.verify(sealed.senderSigningKey, aad + ciphertext, signature)) {
            throw AuthenticationFailedException("group message signature does not verify")
        }
        return Aead.open(
            key = messageKey(epochKey.key, sealed.senderSigningKey, sealed.counter),
            nonce = nonce(epochKey.key, sealed.senderSigningKey, sealed.counter),
            aad = aad,
            ciphertext = ciphertext
        )
    }

    /** Counter allocation: strictly increasing, never repeating, restart-safe. */
    fun nextCounter(lastUsed: Long, now: Long = System.currentTimeMillis()): Long =
        maxOf(now, lastUsed + 1)

    fun randomGroupId(): ByteArray = Bytes.random(GROUP_ID_LEN)

    fun randomEpochKey(): ByteArray = Bytes.random(KEY_LEN)
}
