import { describe, expect, it } from "vitest";
import { fillSpan } from "./grid";

describe("fillSpan: columns the 'View all' tile spans after `count` cards", () => {
  it("fills the rest of a short last row", () => {
    expect(fillSpan(5, 4)).toBe(3);
    expect(fillSpan(5, 3)).toBe(1);
    expect(fillSpan(1, 2)).toBe(1);
  });
  it("takes a whole row of its own when the cards fill their rows exactly", () => {
    expect(fillSpan(8, 4)).toBe(4);
    expect(fillSpan(6, 3)).toBe(3);
  });
  it("rejects a column count below 1", () => {
    expect(() => fillSpan(3, 0)).toThrow();
  });
});
