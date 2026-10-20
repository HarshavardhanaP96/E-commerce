import { describe, expect, it } from "vitest";
import { formatMoney, regional } from "./money";

describe("formatMoney", () => {
  it("uses rupees and Indian grouping by default", () => {
    expect(formatMoney(123456.5)).toBe("₹1,23,456.5");
  });

  it("follows the store's currency and locale", () => {
    Object.assign(regional, { currency: "USD", locale: "en-US" });
    try {
      expect(formatMoney(1234.5)).toBe("$1,234.5");
    } finally {
      Object.assign(regional, { currency: "INR", locale: "en-IN" });
    }
  });

  it("falls back to rupees when the store's currency code is unusable", () => {
    Object.assign(regional, { currency: "NOT-A-CODE" });
    try {
      expect(formatMoney(10)).toBe("₹10");
    } finally {
      Object.assign(regional, { currency: "INR" });
    }
  });
});
