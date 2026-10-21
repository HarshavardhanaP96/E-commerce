import { describe, expect, it } from "vitest";
import { MAX_PRICE, activeFilters } from "./filters";

const money = (n: number) => `₹${n}`;
const none = { search: "", category: "", maxPrice: MAX_PRICE, picked: {} };

describe("activeFilters", () => {
  it("is empty when nothing narrows the list", () => {
    expect(activeFilters(none, money)).toEqual([]);
  });

  it("lists each active filter in a stable order with a readable label", () => {
    expect(
      activeFilters({ search: "shoe", category: "footwear", maxPrice: 5000, picked: { Size: "M", Colour: "Black" } }, money)
    ).toEqual([
      { key: "search", label: "“shoe”" },
      { key: "category", label: "Footwear" },
      { key: "price", label: "Up to ₹5000" },
      { key: "option:Size", label: "Size: M" },
      { key: "option:Colour", label: "Colour: Black" },
    ]);
  });

  it("ignores whitespace-only search text", () => {
    expect(activeFilters({ ...none, search: "   " }, money)).toEqual([]);
  });
});
