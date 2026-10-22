package com.snaptoprint

import kotlin.random.Random

enum class Decision { ELIGIBLE, EXCLUDE_BASELINE, EXCLUDE_BEFORE_START, EXCLUDE_NOT_ADDED_AFTER_BASELINE }

data class EligibilityResult(val decision: Decision, val source: String) {
    val eligible: Boolean get() = decision == Decision.ELIGIBLE
}

/** Pure decision logic (no Android classes) so it can be unit-tested on the JVM. */
object Eligibility {
    const val SRC_TAKEN = "DATE_TAKEN"
    const val SRC_UNKNOWN = "UNKNOWN_FALLBACK"
    private const val MAX_FUTURE_MS = 24L * 3600 * 1000

    /** DATE_TAKEN is trusted only if present, positive and not absurdly in the future. */
    fun trustedTaken(takenMs: Long?, nowMs: Long): Long? =
        if (takenMs != null && takenMs > 0 && takenMs <= nowMs + MAX_FUTURE_MS) takenMs else null

    fun decide(
        mediaId: Long,
        generationAdded: Long?,
        takenMs: Long?,
        baselineIds: Set<Long>,
        baselineGen: Long,
        generationValid: Boolean,
        startMs: Long,
        nowMs: Long
    ): EligibilityResult {
        if (mediaId in baselineIds) return EligibilityResult(Decision.EXCLUDE_BASELINE, Eligibility.SRC_UNKNOWN)
        if (generationValid && generationAdded != null && generationAdded <= baselineGen)
            return EligibilityResult(Decision.EXCLUDE_NOT_ADDED_AFTER_BASELINE, SRC_UNKNOWN)
        val t = trustedTaken(takenMs, nowMs)
        if (t != null && t < startMs) return EligibilityResult(Decision.EXCLUDE_BEFORE_START, SRC_TAKEN)
        return EligibilityResult(Decision.ELIGIBLE, if (t != null) SRC_TAKEN else SRC_UNKNOWN)
    }
}

object Backoff {
    /** Bounded exponential backoff with +/-20% jitter: ~1s, 2s, 4s ... capped at 60s. */
    fun delayMs(attempt: Int, rnd: Random = Random.Default): Long {
        val base = minOf(60_000L, 1_000L shl minOf(attempt, 6))
        val jitter = (base * 0.2 * (rnd.nextDouble() * 2 - 1)).toLong()
        return (base + jitter).coerceIn(500L, 60_000L)
    }
}
