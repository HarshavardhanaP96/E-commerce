import { describe, expect, it } from "vitest";
import { contrastRatio, luminance, parseHex, readableOn } from "./contrast";

describe("contrast maths", () => {
  it("gives 21:1 for black on white and 1:1 for a colour on itself, in either order", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 5);
    expect(contrastRatio("#ffffff", "#000000")).toBeCloseTo(21, 5);
    expect(contrastRatio("#e4572e", "#e4572e")).toBeCloseTo(1, 5);
  });

  it("matches a known pair: white on coral #e4572e is 3.68:1", () => {
    expect(contrastRatio("#ffffff", "#e4572e")).toBeCloseTo(3.68, 2);
  });

  it("accepts upper case and rejects anything that is not #rrggbb", () => {
    expect(parseHex("#E4572E")).toEqual([228, 87, 46]);
    for (const bad of ["red", "#fff", "", "#12345g", "--text"]) {
      expect(() => parseHex(bad)).toThrow(/#rrggbb/);
    }
  });
});

describe("readableOn", () => {
  it("returns the colour itself (lower-cased) when it already passes", () => {
    expect(readableOn("#1C1917", "#ffffff", 4.5)).toBe("#1c1917");
  });

  it("darkens a colour that fails until it passes", () => {
    for (const hex of ["#e4572e", "#ffeb3b", "#22c55e"]) {
      const out = readableOn(hex, "#ffffff", 4.5);
      expect(contrastRatio(out, "#ffffff")).toBeGreaterThanOrEqual(4.5);
      expect(luminance(out)).toBeLessThan(luminance(hex));
    }
  });

  it("rejects a value that is not #rrggbb", () => {
    expect(() => readableOn("tomato", "#ffffff", 4.5)).toThrow(/#rrggbb/);
  });
});
