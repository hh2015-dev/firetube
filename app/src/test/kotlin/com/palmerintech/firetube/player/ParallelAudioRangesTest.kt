package com.palmerintech.firetube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Range math must never overlap, skip a byte, or trust an unverified total. */
class ParallelAudioRangesTest {
    @Test fun acceptsOnlyExactOneByteContentRangeAndKnownLength() {
        assertEquals(5_000_000L, ParallelAudioRanges.totalFromProbe("bytes 0-0/5000000"))
        assertEquals(99L, ParallelAudioRanges.totalFromProbe("BYTES 0-0/99"))
        assertNull(ParallelAudioRanges.totalFromProbe(null))
        assertNull(ParallelAudioRanges.totalFromProbe("bytes */5000000"))
        assertNull(ParallelAudioRanges.totalFromProbe("bytes 0-99/5000000"))
        assertNull(ParallelAudioRanges.totalFromProbe("bytes 1-1/5000000"))
        assertNull(ParallelAudioRanges.totalFromProbe("bytes 0-0/*"))
        assertNull(ParallelAudioRanges.totalFromProbe("bytes 0-0/0"))
        assertNull(ParallelAudioRanges.totalFromProbe("bytes 0-0/9223372036854775807"))
    }

    @Test fun fallbackForSmallFiles() {
        val parts = ParallelAudioRanges.split(1_000_000)
        assertEquals(listOf(ParallelAudioRanges.Part(0, 1_000_000)), parts)
    }

    @Test fun slicesAreContiguousAndCoverEveryByteExactlyOnce() {
        for (total in listOf(2L * 1024 * 1024, 3_000_001L, 3_000_002L, 10_000_000L)) {
            val parts = ParallelAudioRanges.split(total)
            assertEquals(3, parts.size)
            assertEquals(0L, parts.first().position)
            parts.zipWithNext { a, b -> assertEquals(a.position + a.length, b.position) }
            assertEquals(total, parts.last().position + parts.last().length)
            assertEquals(total, parts.sumOf { it.length })
            assertTrue(parts.all { it.length > 0 })
        }
    }
}
