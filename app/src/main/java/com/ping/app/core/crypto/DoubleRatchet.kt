package com.ping.app.core.crypto

/**
 * The Double Ratchet — the part that gives Ping **forward secrecy** (an attacker who steals the
 * phone tomorrow cannot read the messages of yesterday) and **post-compromise security** (after a
 * break-in, the next reply from the other side heals the session).
 *
 * Every message is encrypted with a key that is thrown away the moment it is used, and every reply
 * performs a fresh Diffie-Hellman step, so both sides continuously re-key.
 *
 * The implementation follows the published Double Ratchet specification, with these choices:
 *  - HKDF-SHA-256 for the root-key KDF, HMAC-SHA-256 for the chain KDF (as in the spec),
 *  - ChaCha20-Poly1305 for the payload, keyed by a per-message key and nonce,
 *  - the message header (ratchet public key + counters) is authenticated as additional data,
 *  - skipped message keys are kept (bounded) so out-of-order or delayed messages still work.
 */
class RatchetSession private constructor(
    var rootKey: ByteArray,
    var dhSelf: X25519KeyPair,
    var dhRemotePublic: ByteArray?,
    var sendChainKey: ByteArray?,
    var receiveChainKey: ByteArray?,
    var sendCount: Int,
    var receiveCount: Int,
    var previousSendCount: Int,
    var tagKey: ByteArray,
    val associatedData: ByteArray,
    private val skippedKeys: MutableMap<String, ByteArray>
) {

    companion object {
        private const val VERSION = 1
        private const val MAX_SKIP = 1000
        private const val MAX_SKIPPED_STORED = 2000
        private const val ROOT_INFO = "PingRatchet/v1:root"
        private const val CHAIN_MK: Byte = 0x01
        private const val CHAIN_CK: Byte = 0x02
        private const val HEADER_INFO = "PingMsgHeader/v1"
        private const val TAG_INFO = "PingTag/v1"

        /** Alice: she initiated the handshake. */
        fun initiator(masterSecret: ByteArray, remoteSignedPreKey: ByteArray, associatedData: ByteArray): RatchetSession {
            val self = X25519KeyPair.generate()
            val dh = self.agreeWith(remoteSignedPreKey)
            val (rootKey, sendingChain) = kdfRootKey(masterSecret, dh)
            Bytes.wipe(dh)
            return RatchetSession(
                rootKey = rootKey,
                dhSelf = self,
                dhRemotePublic = remoteSignedPreKey,
                sendChainKey = sendingChain,
                receiveChainKey = null,
                sendCount = 0,
                receiveCount = 0,
                previousSendCount = 0,
                tagKey = Hkdf.derive(masterSecret, Bytes.utf8(TAG_INFO), 16),
                associatedData = associatedData,
                skippedKeys = LinkedHashMap()
            )
        }

        /** Bob: he published the signed pre-key which Alice used; his ratchet key starts as that key. */
        fun responder(masterSecret: ByteArray, signedPreKey: X25519KeyPair, associatedData: ByteArray): RatchetSession =
            RatchetSession(
                rootKey = masterSecret.copyOf(),
                dhSelf = signedPreKey,
                dhRemotePublic = null,
                sendChainKey = null,
                receiveChainKey = null,
                sendCount = 0,
                receiveCount = 0,
                previousSendCount = 0,
                tagKey = Hkdf.derive(masterSecret, Bytes.utf8(TAG_INFO), 16),
                associatedData = associatedData,
                skippedKeys = LinkedHashMap()
            )

        private fun kdfRootKey(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
            val out = Hkdf.derive(dhOutput, rootKey, Bytes.utf8(ROOT_INFO), 64)
            val newRoot = out.copyOfRange(0, 32)
            val chain = out.copyOfRange(32, 64)
            Bytes.wipe(out)
            return newRoot to chain
        }

        private fun kdfChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
            val messageKey = Bytes.hmacSha256(chainKey, byteArrayOf(CHAIN_MK))
            val nextChain = Bytes.hmacSha256(chainKey, byteArrayOf(CHAIN_CK))
            return nextChain to messageKey
        }

        /** Restores a session that was persisted with [RatchetSession.toBytes]. */
        fun fromBytes(bytes: ByteArray): RatchetSession {
            val reader = BufReader(bytes)
            val version = reader.u8()
            if (version != VERSION) throw WireFormatException("unsupported session version $version")
            val rootKey = reader.bytes(32)
            val selfPrivate = reader.bytes(32)
            val selfPublic = reader.bytes(32)
            val remote = if (reader.u8() == 1) reader.bytes(32) else null
            val send = if (reader.u8() == 1) reader.bytes(32) else null
            val receive = if (reader.u8() == 1) reader.bytes(32) else null
            val ns = reader.u32()
            val nr = reader.u32()
            val pn = reader.u32()
            val tagKey = reader.bytes(16)
            val associatedData = reader.blob()
            val skipped = LinkedHashMap<String, ByteArray>()
            val count = reader.varint()
            if (count > MAX_SKIPPED_STORED) throw WireFormatException("session carries too many skipped keys")
            repeat(count) {
                val id = reader.string()
                skipped[id] = reader.bytes(32)
            }
            reader.expectEnd()
            return RatchetSession(
                rootKey = rootKey,
                dhSelf = X25519KeyPair.fromPrivateAndPublic(selfPrivate, selfPublic),
                dhRemotePublic = remote,
                sendChainKey = send,
                receiveChainKey = receive,
                sendCount = ns,
                receiveCount = nr,
                previousSendCount = pn,
                tagKey = tagKey,
                associatedData = associatedData,
                skippedKeys = skipped
            )
        }
    }

    /** True once the other side has been heard from and a sending chain exists. */
    val canSend: Boolean get() = sendChainKey != null

    /**
     * Encrypts one message and advances the sending chain. The returned [RatchetMessage] is
     * self-contained: header + ciphertext, authenticating each other.
     */
    fun encrypt(plaintext: ByteArray): RatchetMessage {
        val chainKey = sendChainKey ?: throw IllegalStateException("session cannot send before it has received")
        val (nextChain, messageKey) = kdfChain(chainKey)
        sendChainKey = nextChain
        Bytes.wipe(chainKey)

        val header = RatchetHeader(dhSelf.publicKey, previousSendCount, sendCount)
        sendCount += 1

        val expanded = Aead.expandMessageKey(messageKey)
        Bytes.wipe(messageKey)
        val ciphertext = try {
            Aead.seal(expanded.key, expanded.nonce, header.additionalData(associatedData), plaintext)
        } finally {
            expanded.destroy()
        }
        return RatchetMessage(header, ciphertext)
    }

    /**
     * Decrypts one message, performing a Diffie-Hellman ratchet step when the sender rotated their
     * key, and stashing skipped keys when messages arrive out of order.
     */
    fun decrypt(header: RatchetHeader, ciphertext: ByteArray): ByteArray {
        val skippedId = skippedKeyId(header.dhPublicKey, header.messageNumber)
        skippedKeys.remove(skippedId)?.let { messageKey ->
            return openWith(messageKey, header, ciphertext)
        }

        val remote = dhRemotePublic
        if (remote == null || !Bytes.constantTimeEquals(remote, header.dhPublicKey)) {
            skipMessageKeys(header.previousChainLength)
            dhRatchet(header.dhPublicKey)
        }

        skipMessageKeys(header.messageNumber)

        val chainKey = receiveChainKey ?: throw IllegalStateException("no receiving chain")
        val (nextChain, messageKey) = kdfChain(chainKey)
        receiveChainKey = nextChain
        receiveCount += 1
        Bytes.wipe(chainKey)
        return openWith(messageKey, header, ciphertext)
    }

    private fun openWith(messageKey: ByteArray, header: RatchetHeader, ciphertext: ByteArray): ByteArray {
        val expanded = Aead.expandMessageKey(messageKey)
        Bytes.wipe(messageKey)
        return try {
            Aead.open(expanded.key, expanded.nonce, header.additionalData(associatedData), ciphertext)
        } finally {
            expanded.destroy()
        }
    }

    private fun skipMessageKeys(until: Int) {
        val chainKey = receiveChainKey ?: return
        if (until < receiveCount) return
        if (until - receiveCount > MAX_SKIP) {
            throw AuthenticationFailedException("too many skipped messages (${until - receiveCount})")
        }
        var chain = chainKey
        while (receiveCount < until) {
            val (nextChain, messageKey) = kdfChain(chain)
            skippedKeys[skippedKeyId(dhRemotePublic!!, receiveCount)] = messageKey
            Bytes.wipe(chain)
            chain = nextChain
            receiveCount += 1
        }
        receiveChainKey = chain
        evictOldSkippedKeys()
    }

    private fun dhRatchet(remotePublicKey: ByteArray) {
        previousSendCount = sendCount
        sendCount = 0
        receiveCount = 0
        dhRemotePublic = remotePublicKey

        val receiveDh = dhSelf.agreeWith(remotePublicKey)
        val (rootAfterReceive, receivingChain) = kdfRootKey(rootKey, receiveDh)
        Bytes.wipe(receiveDh, rootKey)
        rootKey = rootAfterReceive
        receiveChainKey = receivingChain

        val newSelf = X25519KeyPair.generate()
        val sendDh = newSelf.agreeWith(remotePublicKey)
        val (rootAfterSend, sendingChain) = kdfRootKey(rootKey, sendDh)
        Bytes.wipe(sendDh, rootKey)
        rootKey = rootAfterSend
        sendChainKey = sendingChain
        dhSelf = newSelf
    }

    private fun evictOldSkippedKeys() {
        while (skippedKeys.size > MAX_SKIPPED_STORED) {
            val oldest = skippedKeys.keys.firstOrNull() ?: break
            skippedKeys.remove(oldest)?.let { Bytes.wipe(it) }
        }
    }

    /** Number of stored skipped keys (exposed for the diagnostics screen). */
    val skippedKeyCount: Int get() = skippedKeys.size

    private fun skippedKeyId(dhPublicKey: ByteArray, counter: Int): String =
        Bytes.toHex(dhPublicKey) + ":" + counter

    // ------------------------------------------------------------------ persistence

    /** Serialises the whole ratchet state so a session survives app restarts. */
    fun toBytes(): ByteArray {
        val writer = BufWriter(256)
        writer.u8(VERSION)
        writer.bytes(rootKey)
        writer.bytes(dhSelf.privateKey)
        writer.bytes(dhSelf.publicKey)
        dhRemotePublic?.let { writer.u8(1).bytes(it) } ?: writer.u8(0)
        sendChainKey?.let { writer.u8(1).bytes(it) } ?: writer.u8(0)
        receiveChainKey?.let { writer.u8(1).bytes(it) } ?: writer.u8(0)
        writer.u32(sendCount).u32(receiveCount).u32(previousSendCount)
        writer.bytes(tagKey)
        writer.blob(associatedData)
        writer.varint(skippedKeys.size)
        for ((id, key) in skippedKeys) {
            writer.string(id)
            writer.bytes(key)
        }
        return writer.toByteArray()
    }

    fun destroy() {
        Bytes.wipe(rootKey, sendChainKey, receiveChainKey, tagKey)
        for (key in skippedKeys.values) Bytes.wipe(key)
        skippedKeys.clear()
    }

/** Convenience: session tag used to address a message to this session without naming the sender. */
    fun sessionTag(): ByteArray = Bytes.hmacSha256(tagKey, Bytes.utf8("PingSessionTag/v1")).copyOfRange(0, 8)
}

/**
 * Ratchet header. It travels in the clear (inside the encrypted envelope) and is authenticated by
 * the AEAD tag, so neither the relay nor an attacker can alter the ratchet without detection.
 */
class RatchetHeader(
    val dhPublicKey: ByteArray,
    val previousChainLength: Int,
    val messageNumber: Int
) {
    init {
        require(dhPublicKey.size == 32) { "ratchet public key must be 32 bytes" }
        require(previousChainLength >= 0 && messageNumber >= 0) { "negative counters" }
    }

    fun additionalData(associatedData: ByteArray): ByteArray = BufWriter(48 + associatedData.size)
        .bytes(Bytes.utf8("PingMsgHeader/v1"))
        .blob(associatedData)
        .bytes(dhPublicKey)
        .u32(previousChainLength)
        .u32(messageNumber)
        .toByteArray()

    fun encode(): ByteArray = BufWriter(40)
        .bytes(dhPublicKey).u32(previousChainLength).u32(messageNumber).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): RatchetHeader {
            val reader = BufReader(bytes)
            val key = reader.bytes(32)
            val pn = reader.u32()
            val n = reader.u32()
            reader.expectEnd()
            return RatchetHeader(key, pn, n)
        }
    }
}

/** One ratchet-encrypted message. */
class RatchetMessage(val header: RatchetHeader, val ciphertext: ByteArray) {
    fun encode(): ByteArray = BufWriter(64 + ciphertext.size)
        .blob(header.encode())
        .blob(ciphertext)
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): RatchetMessage {
            val reader = BufReader(bytes)
            val header = RatchetHeader.decode(reader.blob())
            val ciphertext = reader.blob()
            reader.expectEnd()
            return RatchetMessage(header, ciphertext)
        }
    }
}
