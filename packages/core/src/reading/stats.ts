/**
 * Reading pace tracking and time-to-finish estimation.
 *
 * Pace is an accumulating pair of counters per document — total active session
 * time and total percent genuinely read toward the end — which yields a robust
 * ms-per-percent rate without storing per-session history. Relocations give the
 * percent deltas; TOC jumps and scrubber commits are capped so that skips do
 * not fabricate reading speed (a skipped jump counts its time but little credit,
 * which keeps the estimate conservative, never inflated).
 */

export interface ReadingPace {
  /** Total milliseconds of audited reading time across all sessions. */
  sessionsMs: number;
  /** Total percent of the book credited to genuine forward progress. */
  percentRead: number;
}

/**
 * Maximum percent credited per pace update. Relocations between neighbouring
 * pages stay below this; chapter jumps and scrub commits clamp to it.
 */
export const MAX_CREDITED_PERCENT_PER_UPDATE = 2;

const clamp = (v: number, lo: number, hi: number): number =>
  Math.min(hi, Math.max(lo, v));

/** Merge one reading sample into the running pace. */
export function updateReadingPace(
  pace: ReadingPace | undefined,
  sessionMs: number,
  deltaPercent: number
): ReadingPace {
  const base = pace ?? { sessionsMs: 0, percentRead: 0 };
  const nextSessions = base.sessionsMs + Math.max(0, sessionMs);
  const nextPercent =
    base.percentRead + clamp(deltaPercent, 0, MAX_CREDITED_PERCENT_PER_UPDATE);
  return {
    sessionsMs: Math.round(nextSessions),
    percentRead: Math.round(nextPercent * 1000) / 1000
  };
}

/**
 * Seconds left to finish, extrapolated from the observed pace, or null when
 * no credible pace data exists yet (nothing read, or no sessions recorded).
 */
export function estimateTimeLeftSeconds(
  pace: ReadingPace | undefined,
  percentRemaining: number
): number | null {
  if (!pace || pace.sessionsMs <= 0 || pace.percentRead <= 0) return null;
  const msPerPercent = pace.sessionsMs / pace.percentRead;
  const seconds = clamp(percentRemaining, 0, 100) * msPerPercent / 1000;
  return Math.round(seconds);
}

/** Human string for the estimate ("2 min left", "1 hr left"), null if unknown. */
export function formatTimeLeft(seconds: number | null): string | null {
  if (seconds === null) return null;
  if (seconds <= 0) return "done";
  if (seconds < 60) return "under a minute";
  if (seconds < 3600) return `${Math.round(seconds / 60)} min left`;
  // Floor (minimum 1) so an hour-and-a-half reads "1 hr left", not "2 hr left"
  return `${Math.max(1, Math.floor(seconds / 3600))} hr left`;
}
