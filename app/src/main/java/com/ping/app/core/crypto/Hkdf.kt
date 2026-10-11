package com.ping.app.core.crypto

/**
 * HKDF (RFC 5869) built on HMAC-SHA-256.
 *
 * Implemented directly on top of `javax.crypto.Mac` so that it behaves identically on the JVM
 * (unit tests) and on Android (Bouncy Castle provider installed by [PingCrypto]).
 */
object Hkdf {

    private const val HASH_LEN = 32

    /** HKDF-Extract: PRK = HMAC(salt, IKM) */
    fun extract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray =
        Bytes.hmacSha256(salt, inputKeyMaterial)

    /** HKDF-Expand: OKM = T(1) | T(2) | ... */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0 && length <= 255 * HASH_LEN) { "invalid HKDF output length $length" }
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = Bytes.hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, output, offset, take)
            offset += take
            counter++
        }
        return output
    }

    /** Extract-then-expand in one call. */
    fun derive(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, inputKeyMaterial)
        try {
            return expand(prk, info, length)
        } finally {
            Bytes.wipe(prk)
        }
    }

    /** Extract-then-expand with an empty salt (the common case in Ping). */
    fun derive(inputKeyMaterial: ByteArray, info: ByteArray, length: Int): ByteArray =
        derive(inputKeyMaterial, ByteArray(HASH_LEN), info, length)

    fun derive(inputKeyMaterial: ByteArray, info: String, length: Int): ByteArray =
        derive(inputKeyMaterial, Bytes.utf8(info), length)
}
