import type { StoreSettings } from "../../redux/api/storeAPI";

/** Every field as the input shows it; an empty string means "use the platform default". */
export const emptyForm = {
  shippingFee: "", freeShippingAbove: "", defaultTaxRate: "", maxCouponAmount: "", minimumAge: "",
  pageSize: "", lowStockThreshold: "",
  gstin: "", state: "",
  timeZone: "",
  legalName: "", address: "", email: "", phone: "", grievanceOfficer: "", grievanceEmail: "", logoUrl: "",
};

export type StoreSettingsForm = typeof emptyForm;

const num = (v: string) => (v.trim() === "" ? null : Number(v));
const text = (v: string) => (v.trim() === "" ? null : v.trim());
const show = (v: number | string | null | undefined) => (v === null || v === undefined ? "" : String(v));

export const toPayload = (f: StoreSettingsForm): StoreSettings => ({
  commerce: {
    shippingFee: num(f.shippingFee), freeShippingAbove: num(f.freeShippingAbove), defaultTaxRate: num(f.defaultTaxRate),
    maxCouponAmount: num(f.maxCouponAmount), minimumAge: num(f.minimumAge),
  },
  catalog: { pageSize: num(f.pageSize), lowStockThreshold: num(f.lowStockThreshold) },
  invoice: { gstin: text(f.gstin)?.toUpperCase() ?? null, state: text(f.state) },
  regional: { timeZone: text(f.timeZone) },
  storefront: {
    legalName: text(f.legalName), address: text(f.address), email: text(f.email), phone: text(f.phone),
    grievanceOfficer: text(f.grievanceOfficer), grievanceEmail: text(f.grievanceEmail), logoUrl: text(f.logoUrl),
  },
});

export const fromSettings = (s: StoreSettings): StoreSettingsForm => ({
  shippingFee: show(s.commerce.shippingFee), freeShippingAbove: show(s.commerce.freeShippingAbove),
  defaultTaxRate: show(s.commerce.defaultTaxRate), maxCouponAmount: show(s.commerce.maxCouponAmount),
  minimumAge: show(s.commerce.minimumAge),
  pageSize: show(s.catalog.pageSize), lowStockThreshold: show(s.catalog.lowStockThreshold),
  gstin: show(s.invoice.gstin), state: show(s.invoice.state),
  timeZone: show(s.regional.timeZone),
  legalName: show(s.storefront.legalName), address: show(s.storefront.address), email: show(s.storefront.email),
  phone: show(s.storefront.phone), grievanceOfficer: show(s.storefront.grievanceOfficer),
  grievanceEmail: show(s.storefront.grievanceEmail), logoUrl: show(s.storefront.logoUrl),
});
