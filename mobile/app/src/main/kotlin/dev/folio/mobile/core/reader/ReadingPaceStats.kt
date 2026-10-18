package dev.folio.mobile.core.reader

import dev.folio.mobile.core.model.ProgressPace

/**
 * Reading pace tracking and time-to-finish estimation — port of
 * `packages/core/src/reading/stats.ts` (kept in the reader package so the
 * model stays a pure schema-1 mirror; Progress.pace is `ProgressPace`).
 *
 * Pace is an accumulating pair of counters per document — total active session
 * time and total percent genuinely read toward the end — which yields a robust
 * ms-per-percent rate without storing per-session history. Relocations give the
 * percent deltas; navigation jumps and repeat scrubbing credit nothing (a raw
 * delta beyond the threshold is navigation, not reading), so the estimate is
 * conservative, never inflated.
 */
object ReadingPaceStats {

    /**
     * Percent deltas beyond this threshold are navigation (TOC / scrubber
     * jumps), not continuous reading, and credit nothing. Genuine page-turn
     * deltas sit well below it; the threshold also keeps repeat scrub
     * left-right cycling from double-crediting the same span.
     */
    const val READING_CREDIT_JUMP_THRESHOLD_PERCENT = 5.0

    /** Merge one reading sample into the running pace. */
    fun updatePace(pace: ProgressPace?, sessionMs: Long, deltaPercent: Double): ProgressPace {
        val base = pace ?: ProgressPace()
        val nextSessions = base.sessionsMs + sessionMs.coerceAtLeast(0)
        // Continuous reading only: tiny forward deltas get full credit, negative
        // or jump-sized deltas get none so re-reads and skips never inflate it.
        val credit = if (deltaPercent in 0.0..READING_CREDIT_JUMP_THRESHOLD_PERCENT) deltaPercent else 0.0
        val nextPercent = base.percentRead + credit
        return ProgressPace(
            sessionsMs = nextSessions,
            percentRead = Math.round(nextPercent * 1000.0) / 1000.0
        )
    }

    /**
     * Seconds left to finish, extrapolated from the observed pace, or null when
     * no credible pace data exists yet (nothing read, or no sessions recorded).
     */
    fun estimateTimeLeftSeconds(pace: ProgressPace?, percentRemaining: Double): Long? {
        if (pace == null || pace.sessionsMs <= 0 || pace.percentRead <= 0.0) return null
        val msPerPercent = pace.sessionsMs.toDouble() / pace.percentRead
        val clamped = percentRemaining.coerceIn(0.0, 100.0)
        return Math.round(clamped * msPerPercent / 1000.0)
    }

    /** Human string for the estimate ("2 min left", "1 hr left"), null if unknown. */
    fun formatTimeLeft(seconds: Long?): String? = when {
        seconds == null -> null
        seconds <= 0 -> "done"
        seconds < 60 -> "under a minute"
        seconds < 3600 -> "${Math.round(seconds / 60.0)} min left"
        // Floor (minimum 1) so an hour-and-a-half reads "1 hr left", not "2 hr left"
        else -> "${Math.max(1, seconds / 3600).toInt()} hr left"
    }
}
