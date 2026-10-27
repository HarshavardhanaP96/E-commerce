import { describe, expect, it } from "vitest";
import { matchState, savedStateChoice } from "./indianStates";

const STATES = [
  { code: "KA", name: "Karnataka", unionTerritory: false, aliases: [] },
  { code: "TN", name: "Tamil Nadu", unionTerritory: false, aliases: [] },
  { code: "OD", name: "Odisha", unionTerritory: false, aliases: ["OR", "Orissa"] },
  { code: "DL", name: "Delhi", unionTerritory: true, aliases: ["New Delhi", "NCT of Delhi"] },
];

describe("matchState", () => {
  it("pre-selects a saved address's state by name or code", () => {
    expect(matchState("Karnataka", STATES)).toBe("Karnataka");
    expect(matchState(" tamil  nadu ", STATES)).toBe("Tamil Nadu");
    expect(matchState("ka", STATES)).toBe("Karnataka");
  });

  it("accepts the old spellings the server accepts", () => {
    expect(matchState("Orissa", STATES)).toBe("Odisha");
    expect(matchState("or", STATES)).toBe("Odisha");
    expect(matchState("new delhi", STATES)).toBe("Delhi");
  });

  it("leaves the choice empty when nothing matches", () => {
    expect(matchState("Bangalore", STATES)).toBe("");
    expect(matchState("", STATES)).toBe("");
  });
});

describe("savedStateChoice", () => {
  it("shows a recognised saved state under its official name, with no extra option", () => {
    expect(savedStateChoice("Orissa", STATES)).toEqual({ value: "Odisha", extra: null });
    expect(savedStateChoice("", STATES)).toEqual({ value: "", extra: null });
    expect(savedStateChoice("  ", STATES)).toEqual({ value: "", extra: null });
  });

  it("keeps an unrecognised saved state visible so clearing it is a real change", () => {
    expect(savedStateChoice("Bangalore", STATES)).toEqual({
      value: "Bangalore",
      extra: { value: "Bangalore", label: "Bangalore (not recognised, choose a state)" },
    });
  });

  it("shows the saved state as it is when the list did not load", () => {
    expect(savedStateChoice("Karnataka", [])).toEqual({
      value: "Karnataka",
      extra: { value: "Karnataka", label: "Karnataka" },
    });
  });
});
