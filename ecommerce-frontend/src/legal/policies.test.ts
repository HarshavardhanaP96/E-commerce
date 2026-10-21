import { describe, expect, it } from "vitest";
import { BUSINESS, isFilledIn } from "./policies";

describe("isFilledIn", () => {
  it("is false for the built-in [placeholders] and empty values", () => {
    expect(isFilledIn(BUSINESS.address)).toBe(false); // "[Registered address]" until a store fills it in
    for (const v of ["", "   ", undefined, null, "[support@your-domain]"]) expect(isFilledIn(v)).toBe(false);
  });
  it("is true for real text, including text that merely contains brackets", () => {
    expect(isFilledIn("12 MG Road, Bengaluru")).toBe(true);
    expect(isFilledIn("Shop [Main branch] Ltd")).toBe(true);
  });
});
