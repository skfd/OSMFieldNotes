package com.surveyme.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceSnapshotTest {

    @Test
    fun `summary is the first non-blank line, trimmed`() {
        assertEquals("Is this shop open?", EvidenceSnapshot.summaryOf("\n   \n  Is this shop open?  \nSecond line"))
    }

    @Test
    fun `summary of blank or null text is empty`() {
        assertEquals("", EvidenceSnapshot.summaryOf(null))
        assertEquals("", EvidenceSnapshot.summaryOf(" \n\t"))
    }

    @Test
    fun `summary is cut with an ellipsis at the limit`() {
        val long = "x".repeat(50)
        assertEquals(long, EvidenceSnapshot.summaryOf(long, max = 50))
        val cut = EvidenceSnapshot.summaryOf(long + "y", max = 50)
        assertEquals(50, cut.length)
        assertEquals("…", cut.takeLast(1))
    }

    @Test
    fun `sample size is the largest power of two that keeps the requested size`() {
        // 4000x3000 into 200x150: 8 gives 500x375, 16 gives 250x187, 32 gives 125x93 (too small).
        assertEquals(16, EvidenceSnapshot.sampleSize(4000, 3000, 200, 150))
        // Portrait shot into a square thumbnail: the narrow side limits.
        assertEquals(8, EvidenceSnapshot.sampleSize(3000, 4000, 300, 300))
    }

    @Test
    fun `sample size is one when the image already fits or bounds are unknown`() {
        assertEquals(1, EvidenceSnapshot.sampleSize(100, 100, 200, 200))
        assertEquals(1, EvidenceSnapshot.sampleSize(0, 0, 200, 200))
        assertEquals(1, EvidenceSnapshot.sampleSize(4000, 3000, 0, 150))
    }
}
