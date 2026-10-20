import { describe, expect, it } from "vitest";
import { emptyForm, fromSettings, toPayload } from "./storeSettingsForm";

describe("store settings form", () => {
  it("sends an empty field as null, meaning the platform default", () => {
    const payload = toPayload({ ...emptyForm, shippingFee: "49", timeZone: "" });
    expect(payload.commerce).toEqual({ shippingFee: 49, freeShippingAbove: null, defaultTaxRate: null,
      maxCouponAmount: null, minimumAge: null });
    expect(payload.regional).toEqual({ timeZone: null });
  });

  it("keeps a zero, which is a real value (free shipping, 0% GST)", () => {
    expect(toPayload({ ...emptyForm, shippingFee: "0", defaultTaxRate: "0" }).commerce)
      .toMatchObject({ shippingFee: 0, defaultTaxRate: 0 });
  });

  it("trims text and upper-cases the GSTIN", () => {
    const p = toPayload({ ...emptyForm, gstin: " 29aaaca1234b1z9 ", legalName: "  Acme LLP " });
    expect(p.invoice.gstin).toBe("29AAACA1234B1Z9");
    expect(p.storefront.legalName).toBe("Acme LLP");
    expect(p.storefront.address).toBeNull();
  });

  it("fills the form from saved settings, leaving unset ones empty", () => {
    const form = fromSettings({
      commerce: { shippingFee: 49, freeShippingAbove: null, defaultTaxRate: 5, maxCouponAmount: null, minimumAge: null },
      catalog: { pageSize: 24, lowStockThreshold: null },
      invoice: { gstin: "29AAACA1234B1Z9", state: null },
      regional: { timeZone: null },
      storefront: { legalName: "Acme LLP" },
    });
    expect(form).toMatchObject({ shippingFee: "49", freeShippingAbove: "", defaultTaxRate: "5", pageSize: "24",
      gstin: "29AAACA1234B1Z9", state: "", legalName: "Acme LLP", email: "" });
  });
});
