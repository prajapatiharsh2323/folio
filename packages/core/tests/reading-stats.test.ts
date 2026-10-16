import { describe, it, expect } from "vitest";
import {
  estimateTimeLeftSeconds,
  updateReadingPace,
  formatTimeLeft,
  type ReadingPace
} from "../src/reading/stats.js";

describe("ReadingPace accumulation", () => {
  it("returns a fresh pace when none exists yet", () => {
    const pace = updateReadingPace(undefined, 60_000, 1.2);
    expect(pace).toEqual({ sessionsMs: 60_000, percentRead: 1.2 });
  });

  it("accumulates session time and percent read across sessions", () => {
    let pace: ReadingPace | undefined = { sessionsMs: 30_000, percentRead: 1.5 };
    pace = updateReadingPace(pace, 45_000, 0.9);
    expect(pace).toEqual({ sessionsMs: 75_000, percentRead: 2.4 });
  });

  it("never records negative session durations", () => {
    const pace = updateReadingPace({ sessionsMs: 10_000, percentRead: 5 }, -5_000, 1);
    expect(pace.sessionsMs).toBe(10_000);
  });

  it("credits nothing for navigation jumps (LET 5% threshold: scrubber/TOC skips are not reading)", () => {
    const pace = updateReadingPace(undefined, 120_000, 95);
    expect(pace).toEqual({ sessionsMs: 120_000, percentRead: 0 });
    const jump = updateReadingPace({ sessionsMs: 10_000, percentRead: 2 }, 10_000, 30);
    expect(jump).toEqual({ sessionsMs: 20_000, percentRead: 2 });
  });

  it("is immune to the scrub left-right inflation loop (the +2%-per-update bug)", () => {
    let pace: ReadingPace | undefined = { sessionsMs: 10_000, percentRead: 1 };
    // A user yo-yos the scrubber: forward 30% > threshold (no credit), lands at 1.5%
    for (let i = 0; i < 20; i++) {
      pace = updateReadingPace(pace, 1_000, 30);
      pace = updateReadingPace(pace, 1_000, -0.5); // back-scrolled: below 0, no credit
    }
    expect(pace.percentRead).toBe(1);
    expect(pace.sessionsMs).toBe(50_000);
  });
});

describe("estimateTimeLeftSeconds", () => {
  it("returns null when there is no usable pace data yet", () => {
    expect(estimateTimeLeftSeconds(undefined, 40)).toBeNull();
    expect(estimateTimeLeftSeconds({ sessionsMs: 50_000, percentRead: 0 }, 40)).toBeNull();
  });

  it("extrapolates from observed pace: 1 min per 1% → 40% left = 40 min", () => {
    const pace: ReadingPace = { sessionsMs: 120_000, percentRead: 2 };
    expect(estimateTimeLeftSeconds(pace, 40)).toBe(40 * 60);
  });

  it("returns 0 seconds when the book is finished", () => {
    const pace: ReadingPace = { sessionsMs: 120_000, percentRead: 2 };
    expect(estimateTimeLeftSeconds(pace, 0)).toBe(0);
  });

  it("clamps absurd remaining percents above 100", () => {
    const pace: ReadingPace = { sessionsMs: 60_000, percentRead: 2 };
    expect(estimateTimeLeftSeconds(pace, 900)).toBe(estimateTimeLeftSeconds(pace, 100));
    expect(estimateTimeLeftSeconds(pace, 900)).toBe(50 * 60);
  });
});

describe("formatTimeLeft", () => {
  it("renders human strings: done / under a minute / minutes / hours", () => {
    expect(formatTimeLeft(null)).toBeNull();
    expect(formatTimeLeft(0)).toBe("done");
    expect(formatTimeLeft(45)).toBe("under a minute");
    expect(formatTimeLeft(2 * 60)).toBe("2 min left");
    expect(formatTimeLeft(5400)).toBe("1 hr left");
    expect(formatTimeLeft(3.2 * 3600)).toBe("3 hr left");
  });
});

