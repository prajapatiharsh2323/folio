import { describe, it, expect, vi, afterEach } from "vitest";
import { haptic } from "../../../apps/web/src/reader/haptics.js";

describe("haptic feedback utility", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("calls navigator.vibrate with the given pattern when supported", () => {
    const vibrate = vi.fn();
    vi.stubGlobal("navigator", Object.assign(Object.create(Object.getPrototypeOf(navigator)), navigator, { vibrate }));

    haptic(10);

    expect(vibrate).toHaveBeenCalledTimes(1);
    expect(vibrate).toHaveBeenCalledWith(10);
  });

  it("defaults to a short 5 ms buzz", () => {
    const vibrate = vi.fn();
    vi.stubGlobal("navigator", Object.assign(Object.create(Object.getPrototypeOf(navigator)), navigator, { vibrate }));

    haptic();

    expect(vibrate).toHaveBeenCalledWith(5);
  });

  it("never throws when vibration is unsupported (no vibrate in navigator)", () => {
    vi.stubGlobal("navigator", {});

    expect(() => haptic(10)).not.toThrow();
  });

  it("never throws when navigator.vibrate itself throws", () => {
    const vibrate = vi.fn(() => {
      throw new Error("vibration blocked");
    });
    vi.stubGlobal("navigator", { vibrate });

    expect(() => haptic(10)).not.toThrow();
  });

  it("accepts pattern arrays for richer buzzes", () => {
    const vibrate = vi.fn();
    vi.stubGlobal("navigator", { vibrate });

    haptic([10, 30, 10]);

    expect(vibrate).toHaveBeenCalledWith([10, 30, 10]);
  });
});
