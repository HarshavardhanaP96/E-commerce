import { describe, expect, it } from "vitest";
import { Product } from "../types/types";
import { cardPricing, discountPercent } from "./variants";

const base = { _id: "p1", name: "Shoe", photo: "", category: "shoes", stock: 5, price: 4999 } as Product;

describe("discountPercent", () => {
  it("rounds the saving against MRP", () => {
    expect(discountPercent(4999, 6499)).toBe(23);
  });
  it("is 0 when there is no saving or no MRP", () => {
    expect(discountPercent(4999, 4999)).toBe(0);
    expect(discountPercent(4999, 3000)).toBe(0);
    expect(discountPercent(4999, null)).toBe(0);
    expect(discountPercent(4999)).toBe(0);
  });
});

describe("cardPricing", () => {
  it("shows MRP and % off for a plain product sold below MRP", () => {
    expect(cardPricing({ ...base, mrp: 6499 })).toEqual({ price: 4999, from: false, mrp: 6499, off: 23 });
  });
  it("hides MRP when it is missing or not above the price", () => {
    expect(cardPricing({ ...base, mrp: null } as unknown as Product)).toEqual({ price: 4999, from: false, mrp: null, off: 0 });
    expect(cardPricing({ ...base, mrp: 4999 })).toEqual({ price: 4999, from: false, mrp: null, off: 0 });
  });
  it("shows 'from' the lowest variant price and no MRP when variants differ in price", () => {
    const p = {
      ...base,
      mrp: 9999,
      variants: { axes: [], items: [
        { id: "a", sku: "A", label: "M", options: [], price: 1500, stock: 2, status: "ACTIVE" },
        { id: "b", sku: "B", label: "L", options: [], price: 1200, stock: 2, status: "ACTIVE" },
      ] },
    } as unknown as Product;
    expect(cardPricing(p)).toEqual({ price: 1200, from: true, mrp: null, off: 0 });
  });
});
