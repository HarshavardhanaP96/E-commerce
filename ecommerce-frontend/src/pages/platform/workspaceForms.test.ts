import { describe, expect, it } from "vitest";
import { formatBytes, usagePercent } from "./workspaceForms";

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
