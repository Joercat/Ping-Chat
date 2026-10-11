package com.ping.app.core.crypto

/**
 * Attachments are encrypted on the sender's device with a key that is only ever shared inside the
 * end-to-end encrypted conversation, and the ciphertext is what gets stored on both sides.
 *
 * Each chunk is sealed separately and bound to its position, so chunks cannot be reordered,
 * duplicated or silently dropped without detection. Plaintext never touches the filesystem.
 */
object AttachmentCrypto {

    private const val AAD_PREFIX = "PingAttach/v1"

    /** 12-byte nonce derived from the file key and the chunk index (no random numbers needed). */
    fun nonceFor(fileKey: ByteArray, index: Int, chunkCount: Int): ByteArray =
        Hkdf.derive(
            inputKeyMaterial = fileKey,
            info = Bytes.concat(
                Bytes.utf8("PingAttachNonce/v1"),
                byteArrayOf(
                    (index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte(),
                    (chunkCount ushr 24).toByte(), (chunkCount ushr 16).toByte(), (chunkCount ushr 8).toByte(), chunkCount.toByte()
                )
            ),
            length = Aead.NONCE_LEN
        )

    fun additionalData(transferId: ByteArray, index: Int, chunkCount: Int): ByteArray = BufWriter(64)
        .bytes(Bytes.utf8(AAD_PREFIX))
        .blob(transferId)
        .u32(index)
        .u32(chunkCount)
        .toByteArray()

    fun encryptChunk(
        fileKey: ByteArray,
        transferId: ByteArray,
        index: Int,
        chunkCount: Int,
        plaintext: ByteArray
    ): ByteArray = Aead.seal(
        key = fileKey,
        nonce = nonceFor(fileKey, index, chunkCount),
        aad = additionalData(transferId, index, chunkCount),
        plaintext = plaintext
    )

    fun decryptChunk(
        fileKey: ByteArray,
        transferId: ByteArray,
        index: Int,
        chunkCount: Int,
        ciphertext: ByteArray
    ): ByteArray = Aead.open(
        key = fileKey,
        nonce = nonceFor(fileKey, index, chunkCount),
        aad = additionalData(transferId, index, chunkCount),
        ciphertext = ciphertext
    )

    /** Streaming digest used to verify a reassembled attachment. */
    class Sha256Accumulator {
        private val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) = digest.update(bytes, offset, length)
        fun finish(): ByteArray = digest.digest()
    }
}
