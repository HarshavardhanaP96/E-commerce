import { describe, expect, it } from "vitest";
import type { StoreTypeInfo } from "../../redux/api/platformAPI";
import { fieldOf, presetSummary, slugFromName } from "./onboarding";

const none = { shippingFee: null, freeShippingAbove: null, defaultTaxRate: null, maxCouponAmount: null,
  minimumAge: null, stuckOrderDays: null, orderPrefix: null };
const type = (features: Record<string, boolean>, commerce = {}, catalog = {}): StoreTypeInfo => ({
  key: "GROCERY", label: "Grocery", description: "",
  features,
  settings: {
    commerce: { ...none, ...commerce },
    catalog: { pageSize: null, lowStockThreshold: null, ...catalog },
    invoice: { gstin: null, state: null },
    regional: { timeZone: null },
  },
});

describe("onboarding", () => {
  it("makes a slug from the name", () => {
    expect(slugFromName("Acme Traders")).toBe("acme-traders");
    expect(slugFromName("  Café & Co.  ")).toBe("cafe-co");
    expect(slugFromName("x".repeat(50))).toHaveLength(40);
    expect(slugFromName("---")).toBe("");
  });

  it("summarises what a type pre-sets", () => {
    expect(presetSummary(type({ variants: false }, { shippingFee: 40, freeShippingAbove: 500, defaultTaxRate: 5, stuckOrderDays: 1 },
      { lowStockThreshold: 10 })))
      .toBe("Variants off · GST 5% · shipping ₹40, free above ₹500 · low stock at 10 · orders flagged after 1 day");
    expect(presetSummary(type({}))).toBe("Platform defaults");
  });

  it("finds the field an API error names", () => {
    expect(fieldOf("slug: use 1-40 lowercase letters, digits or dashes")).toBe("slug");
    expect(fieldOf("admin.password: 12 to 72 characters")).toBe("admin.password");
    expect(fieldOf("A tenant with this slug already exists")).toBeNull();
  });
});
