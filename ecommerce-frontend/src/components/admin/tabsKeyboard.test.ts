import { describe, expect, it } from "vitest";
import { nextTabIndex } from "./tabsKeyboard";

describe("nextTabIndex", () => {
  it("moves right and left, wrapping at the ends", () => {
    expect(nextTabIndex("ArrowRight", 0, 3)).toBe(1);
    expect(nextTabIndex("ArrowRight", 2, 3)).toBe(0);
    expect(nextTabIndex("ArrowLeft", 0, 3)).toBe(2);
  });

  it("jumps to the first and last tab", () => {
    expect(nextTabIndex("Home", 2, 5)).toBe(0);
    expect(nextTabIndex("End", 0, 5)).toBe(4);
  });

  it("ignores other keys", () => {
    expect(nextTabIndex("Enter", 1, 3)).toBeNull();
  });
});
