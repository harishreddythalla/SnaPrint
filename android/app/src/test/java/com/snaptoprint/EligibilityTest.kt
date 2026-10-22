package com.snaptoprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EligibilityTest {
    private val start = 1_000_000L
    private val baseline = setOf(1L, 2L, 3L)

    private fun d(id: Long, gen: Long?, taken: Long?, valid: Boolean = true) =
        Eligibility.decide(id, gen, taken, baseline, 100L, valid, start, start + 10_000)

    @Test fun preExistingPhotoIsNeverEligible() {
        assertEquals(Decision.EXCLUDE_BASELINE, d(2, 50, start - 5000).decision)
        assertEquals(Decision.EXCLUDE_BASELINE, d(2, 500, start + 5000).decision)
    }

    @Test fun photoAfterStartIsEligible() {
        val r = d(10, 101, start + 500)
        assertTrue(r.eligible)
        assertEquals(Eligibility.SRC_TAKEN, r.source)
    }

    @Test fun takenBeforeStartButSavedLaterIsExcluded() {
        assertEquals(Decision.EXCLUDE_BEFORE_START, d(11, 101, start - 2000).decision)
    }

    @Test fun missingCaptureTimeUsesFallbackAndRecordsUnknown() {
        val r = d(12, 101, null)
        assertTrue(r.eligible)
        assertEquals(Eligibility.SRC_UNKNOWN, r.source)
    }

    @Test fun staleGenerationIsExcluded() {
        assertEquals(Decision.EXCLUDE_NOT_ADDED_AFTER_BASELINE, d(13, 100, start + 1).decision)
    }

    @Test fun invalidGenerationFallsBackToBaselineIds() {
        assertTrue(d(14, 5, start + 1, valid = false).eligible)
        assertFalse(d(2, 5, start + 1, valid = false).eligible)
    }

    @Test fun absurdFutureTimestampIsUntrustedNotExcluded() {
        val r = d(15, 101, start + 10L * 24 * 3600 * 1000)
        assertTrue(r.eligible)
        assertEquals(Eligibility.SRC_UNKNOWN, r.source)
    }

    @Test fun backoffIsBoundedAndGrows() {
        val rnd = Random(1)
        for (a in 0..30) {
            val v = Backoff.delayMs(a, rnd)
            assertTrue(v in 500L..60_000L)
        }
        assertTrue(Backoff.delayMs(5, Random(2)) > Backoff.delayMs(0, Random(2)))
    }
}
