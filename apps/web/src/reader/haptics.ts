export type HapticPattern = number | number[];

/**
 * Fire a platform haptic vibration. Safe no-op on devices / contexts without
 * haptics, and never throws (some engines reject patterns or block the API).
 * Shared by pages turns, taps and annotation actions so every haptic in the
 * reader behaves consistently.
 */
export function haptic(pattern: HapticPattern = 5): void {
  try {
    if (typeof navigator !== "undefined" && "vibrate" in navigator) {
      navigator.vibrate(pattern);
    }
  } catch {
    /* haptics are an enhancement: ignore any engine rejection */
  }
}
