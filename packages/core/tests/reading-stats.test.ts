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

  it("clamps percent deltas that arrive out of range (scrubber artifacts)", () => {
    // e.g. jumping forward 95% via TOC should not fake 95% of reading speed
    const pace = updateReadingPace(undefined, 120_000, 95);
    expect(pace).toEqual({ sessionsMs: 120_000, percentRead: 2 });
    const jump = updateReadingPace({ sessionsMs: 10_000, percentRead: 0 }, 10_000, 80);
    expect(jump).toEqual({ sessionsMs: 20_000, percentRead: 2 });
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

