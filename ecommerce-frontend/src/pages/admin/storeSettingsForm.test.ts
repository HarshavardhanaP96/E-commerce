import { describe, expect, it } from "vitest";
import type { StoreSettings } from "../../redux/api/storeAPI";
import { emptyForm, fromSettings, tabDirty, TAB_FIELDS, toPayload, toTabPayload } from "./storeSettingsForm";

const saved: StoreSettings = {
  commerce: { shippingFee: 49, freeShippingAbove: null, defaultTaxRate: 5, maxCouponAmount: null, minimumAge: null,
    stuckOrderDays: null, orderPrefix: null },
  catalog: { pageSize: 24, lowStockThreshold: null },
  invoice: { gstin: "29AAACA1234B1Z9", state: null },
  regional: { timeZone: null },
  storefront: { legalName: "Acme LLP" },
};

describe("store settings form", () => {
  it("sends an empty field as null, meaning the platform default", () => {
    const payload = toPayload({ ...emptyForm, shippingFee: "49", timeZone: "" });
    expect(payload.commerce).toEqual({ shippingFee: 49, freeShippingAbove: null, defaultTaxRate: null,
      maxCouponAmount: null, minimumAge: null, stuckOrderDays: null, orderPrefix: null });
    expect(payload.regional).toEqual({ timeZone: null });
  });

  it("keeps a zero, which is a real value (free shipping, 0% GST)", () => {
    expect(toPayload({ ...emptyForm, shippingFee: "0", defaultTaxRate: "0" }).commerce)
      .toMatchObject({ shippingFee: 0, defaultTaxRate: 0 });
  });

  it("round-trips the stuck-order days", () => {
    const payload = toPayload({ ...emptyForm, stuckOrderDays: "7" });
    expect(payload.commerce.stuckOrderDays).toBe(7);
  });

  it("trims and upper-cases the order number prefix", () => {
    expect(toPayload({ ...emptyForm, orderPrefix: " acme " }).commerce.orderPrefix).toBe("ACME");
    expect(fromSettings({ ...saved, commerce: { ...saved.commerce, orderPrefix: "ACME" } }).orderPrefix).toBe("ACME");
  });

  it("trims text and upper-cases the GSTIN", () => {
    const p = toPayload({ ...emptyForm, gstin: " 29aaaca1234b1z9 ", legalName: "  Acme LLP " });
    expect(p.invoice.gstin).toBe("29AAACA1234B1Z9");
    expect(p.storefront.legalName).toBe("Acme LLP");
    expect(p.storefront.address).toBeNull();
  });

  it("fills the form from saved settings, leaving unset ones empty", () => {
    const form = fromSettings(saved);
    expect(form).toMatchObject({ orderPrefix: "", shippingFee: "49", freeShippingAbove: "", defaultTaxRate: "5", pageSize: "24",
      gstin: "29AAACA1234B1Z9", state: "", legalName: "Acme LLP", email: "" });
  });
});

describe("settings tabs", () => {
  it("sends only the groups of the tab being saved", () => {
    const p = toTabPayload({ ...emptyForm, shippingFee: "49", legalName: "Acme" }, "shipping");
    expect(Object.keys(p)).toEqual(["commerce"]);
    expect(p.commerce?.shippingFee).toBe(49);
    expect(Object.keys(toTabPayload(emptyForm, "invoice")).sort()).toEqual(["invoice", "storefront"]);
    expect(Object.keys(toTabPayload(emptyForm, "region"))).toEqual(["regional"]);
  });

  it("knows which tab has unsaved changes", () => {
    const saved = { ...emptyForm, shippingFee: "49" };
    expect(tabDirty({ ...saved, shippingFee: "50" }, saved, "shipping")).toBe(true);
    expect(tabDirty({ ...saved, shippingFee: "50" }, saved, "orders")).toBe(false);
    expect(tabDirty({ ...saved, orderPrefix: "ACME" }, saved, "orders")).toBe(true);
  });

  it("puts every form field on exactly one tab", () => {
    const all = Object.values(TAB_FIELDS).flat();
    expect(all.sort()).toEqual(Object.keys(emptyForm).sort());
  });
});
