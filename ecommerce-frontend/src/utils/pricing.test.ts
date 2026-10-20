import { describe, expect, it } from "vitest";
import { cartTotals, shippingNote } from "./pricing";

const STANDARD = { shippingFee: 200, freeShippingAbove: 1000, defaultTaxRate: 18 };

describe("cartTotals", () => {
  it("charges the store's shipping fee at or below its free-shipping level", () => {
    const rules = { shippingFee: 49, freeShippingAbove: 499, defaultTaxRate: 0 };
    expect(cartTotals([{ price: 499, quantity: 1 }], 0, rules).shippingCharges).toBe(49);
    expect(cartTotals([{ price: 500, quantity: 1 }], 0, rules).shippingCharges).toBe(0);
  });

  it("uses the store's default rate only for items without their own", () => {
    const rules = { shippingFee: 0, freeShippingAbove: 0, defaultTaxRate: 5 };
    const t = cartTotals([{ price: 100, quantity: 1 }, { price: 100, quantity: 1, taxRate: 12 }], 0, rules);
    expect(t.tax).toBe(17);
  });

  it("prices an empty cart at zero, shipping included", () => {
    expect(cartTotals([], 0, STANDARD)).toEqual({ subtotal: 0, tax: 0, shippingCharges: 0, total: 0 });
  });

  it("matches the server for the platform's standard rules and caps the discount at the subtotal", () => {
    expect(cartTotals([{ price: 250, quantity: 2 }], 0, STANDARD)).toEqual({ subtotal: 500, tax: 90, shippingCharges: 200, total: 790 });
    expect(cartTotals([{ price: 10, quantity: 1 }], 5000, STANDARD).total).toBe(202);
  });
});

describe("shippingNote", () => {
  const money = (n: number) => `₹${n.toLocaleString("en-IN")}`;
  it("describes the store's own rule", () => {
    expect(shippingNote({ shippingFee: 49, freeShippingAbove: 499 }, money))
      .toBe("Shipping is free on orders over ₹499. Otherwise there is a flat ₹49 charge, shown before you pay.");
  });
  it("says shipping is always free when the fee is zero", () => {
    expect(shippingNote({ shippingFee: 0, freeShippingAbove: 1000 }, money)).toBe("Shipping is free on every order.");
  });
});
