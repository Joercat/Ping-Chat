package com.ping.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-chunk progress of an attachment transfer.
 *
 * The property that matters: chunks can land in any order, and progress only ever counts distinct
 * chunks. A "highest index seen" would call a file complete while earlier pieces are still missing.
 */
class ChunkBitsTest {

    @Test
    fun outOfOrderArrivalsCountDistinctChunks() {
        val count = 10
        var bits = ChunkBits.empty(count)
        // Chunk 9 lands first. A "highest index" counter would now claim 10 of 10 done.
        bits = ChunkBits.withBit(bits, count, 9)
        assertEquals(1, ChunkBits.count(bits, count))
        assertFalse(ChunkBits.isSet(bits, 0))
        for (index in listOf(3, 0, 7, 3, 9)) bits = ChunkBits.withBit(bits, count, index)
        // Duplicates (3 and 9 again) must not inflate the count.
        assertEquals(4, ChunkBits.count(bits, count))
        assertTrue(ChunkBits.isSet(bits, 7))
        assertFalse(ChunkBits.isSet(bits, 8))
    }

    @Test
    fun fullMarksEveryChunkAndNothingPastTheEnd() {
        for (count in listOf(1, 7, 8, 9, 16, 17, 129)) {
            val bits = ChunkBits.full(count)
            assertEquals("count=$count", count, ChunkBits.count(bits, count))
            assertFalse("bit past the end, count=$count", ChunkBits.isSet(bits, count))
        }
    }

    @Test
    fun emptyAndOutOfRangeAreSafe() {
        val bits = ChunkBits.empty(12)
        assertEquals(2, bits.size)
        assertEquals(0, ChunkBits.count(bits, 12))
        assertFalse(ChunkBits.isSet(bits, -1))
        assertFalse(ChunkBits.isSet(bits, 200))
        // Setting an index outside the transfer changes nothing.
        assertEquals(0, ChunkBits.count(ChunkBits.withBit(bits, 12, 12), 12))
        assertEquals(0, ChunkBits.count(ChunkBits.withBit(bits, 12, -5), 12))
    }

    @Test
    fun withBitDoesNotMutateTheOriginal() {
        val original = ChunkBits.empty(8)
        val updated = ChunkBits.withBit(original, 8, 5)
        assertEquals(0, ChunkBits.count(original, 8))
        assertEquals(1, ChunkBits.count(updated, 8))
    }

    @Test
    fun widensAShortArrayInsteadOfFailing() {
        // A row written before the bit map existed can be shorter than the transfer needs.
        val short = ByteArray(0)
        val widened = ChunkBits.withBit(short, 20, 19)
        assertEquals(3, widened.size)
        assertEquals(1, ChunkBits.count(widened, 20))
    }
}
