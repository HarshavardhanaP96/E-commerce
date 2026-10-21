import { describe, expect, it } from "vitest";
import { checkoutSteps } from "./checkout";

describe("checkoutSteps", () => {
  it("marks earlier steps done and later ones upcoming", () => {
    expect(checkoutSteps("address").map((s) => [s.label, s.state])).toEqual([
      ["Cart", "done"],
      ["Address", "current"],
      ["Payment", "upcoming"],
    ]);
  });
  it("has nothing done on the first step", () => {
    expect(checkoutSteps("cart").map((s) => s.state)).toEqual(["current", "upcoming", "upcoming"]);
  });
});
