package com.ping.app.data

/**
 * Which chunks of one attachment transfer are done, one bit per chunk.
 *
 * A bit map rather than a counter or a "highest index seen": chunks do not arrive in order. Each
 * one is retried on its own schedule by the outbox, so chunk 9 can land before chunk 4. A "highest
 * index" would claim the file is complete while pieces are still missing, and the receiver would
 * then fail a perfectly good transfer at the digest check.
 */
object ChunkBits {

    fun empty(chunkCount: Int): ByteArray = ByteArray((chunkCount + 7) / 8)

    fun full(chunkCount: Int): ByteArray {
        val bits = empty(chunkCount)
        for (index in 0 until chunkCount) bits[index ushr 3] = (bits[index ushr 3].toInt() or (1 shl (index and 7))).toByte()
        return bits
    }

    fun isSet(bits: ByteArray, index: Int): Boolean {
        if (index < 0) return false
        val byte = index ushr 3
        if (byte >= bits.size) return false
        return ((bits[byte].toInt() shr (index and 7)) and 1) == 1
    }

    /** A copy of [bits] with bit [index] set. The copy is widened if the stored array is too short. */
    fun withBit(bits: ByteArray, chunkCount: Int, index: Int): ByteArray {
        val copy = bits.copyOf(maxOf(bits.size, (chunkCount + 7) / 8))
        if (index in 0 until chunkCount) {
            copy[index ushr 3] = (copy[index ushr 3].toInt() or (1 shl (index and 7))).toByte()
        }
        return copy
    }

    /** How many of the first [chunkCount] chunks are done. */
    fun count(bits: ByteArray, chunkCount: Int): Int {
        var done = 0
        for (index in 0 until chunkCount) if (isSet(bits, index)) done += 1
        return done
    }
}
