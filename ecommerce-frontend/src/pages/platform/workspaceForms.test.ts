import { describe, expect, it } from "vitest";
import {
  addDomain,
  bytesFromSize,
  featureChoice,
  featuresPayload,
  formatBytes,
  nullableInt,
  sizeFromBytes,
  usagePercent,
} from "./workspaceForms";

describe("usage", () => {
  it("formats bytes in the largest sensible unit", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(2 * 1024 ** 3)).toBe("2 GB");
  });

  it("gives a capped whole percentage", () => {
    expect(usagePercent(1, 3)).toBe(33);
    expect(usagePercent(10, 5)).toBe(100);
    expect(usagePercent(1, 0)).toBe(0);
  });
});

describe("features", () => {
  it("reads a missing key as the platform default", () => {
    expect(featureChoice({ variants: true }, "variants")).toBe("on");
    expect(featureChoice({ variants: false }, "variants")).toBe("off");
    expect(featureChoice({}, "marketing")).toBe("default");
  });

  it("sends only explicit choices", () => {
    expect(featuresPayload({ variants: "on", marketing: "default" })).toEqual({ variants: true });
    expect(featuresPayload({ variants: "default", marketing: "default" })).toEqual({});
  });
});

describe("limits", () => {
  it("converts MB and GB to bytes, empty meaning the platform default", () => {
    expect(bytesFromSize("1.5", "GB")).toBe(1610612736);
    expect(bytesFromSize("500", "MB")).toBe(524288000);
    expect(bytesFromSize("", "GB")).toBeNull();
    expect(bytesFromSize("  ", "MB")).toBeNull();
  });

  it("shows bytes back in the unit they were entered in", () => {
    expect(sizeFromBytes(1610612736)).toEqual({ value: "1.5", unit: "GB" });
    expect(sizeFromBytes(524288000)).toEqual({ value: "500", unit: "MB" });
    expect(sizeFromBytes(null)).toEqual({ value: "", unit: "GB" });
  });

  it("parses whole numbers, empty as null", () => {
    expect(nullableInt("250")).toBe(250);
    expect(nullableInt("")).toBeNull();
  });
});

describe("domains", () => {
  it("adds a trimmed lower-case domain once", () => {
    expect(addDomain(["a.test"], " B.Test ")).toEqual(["a.test", "b.test"]);
    expect(addDomain(["a.test"], "A.test")).toEqual(["a.test"]);
    expect(addDomain(["a.test"], "  ")).toEqual(["a.test"]);
  });
});
