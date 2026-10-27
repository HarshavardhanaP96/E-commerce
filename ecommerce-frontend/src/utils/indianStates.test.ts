import { describe, expect, it } from "vitest";
import { matchState } from "./indianStates";

const STATES = [
  { code: "KA", name: "Karnataka", unionTerritory: false },
  { code: "TN", name: "Tamil Nadu", unionTerritory: false },
];

describe("matchState", () => {
  it("pre-selects a saved address's state by name or code", () => {
    expect(matchState("Karnataka", STATES)).toBe("Karnataka");
    expect(matchState(" tamil  nadu ", STATES)).toBe("Tamil Nadu");
    expect(matchState("ka", STATES)).toBe("Karnataka");
  });

  it("leaves the choice empty when nothing matches", () => {
    expect(matchState("Bangalore", STATES)).toBe("");
    expect(matchState("", STATES)).toBe("");
  });
});
