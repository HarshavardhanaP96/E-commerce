import type { StoreSettings } from "../../redux/api/storeAPI";

/** Every field as the input shows it; an empty string means "use the platform default". */
export const emptyForm = {
  shippingFee: "", freeShippingAbove: "", defaultTaxRate: "", maxCouponAmount: "", minimumAge: "",
  stuckOrderDays: "", orderPrefix: "",
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
    maxCouponAmount: num(f.maxCouponAmount), minimumAge: num(f.minimumAge), stuckOrderDays: num(f.stuckOrderDays),
    orderPrefix: text(f.orderPrefix)?.toUpperCase() ?? null,
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
  minimumAge: show(s.commerce.minimumAge), stuckOrderDays: show(s.commerce.stuckOrderDays),
  orderPrefix: show(s.commerce.orderPrefix),
  pageSize: show(s.catalog.pageSize), lowStockThreshold: show(s.catalog.lowStockThreshold),
  gstin: show(s.invoice.gstin), state: show(s.invoice.state),
  timeZone: show(s.regional.timeZone),
  legalName: show(s.storefront.legalName), address: show(s.storefront.address), email: show(s.storefront.email),
  phone: show(s.storefront.phone), grievanceOfficer: show(s.storefront.grievanceOfficer),
  grievanceEmail: show(s.storefront.grievanceEmail), logoUrl: show(s.storefront.logoUrl),
});

export type SettingsTab = "shipping" | "orders" | "catalogue" | "invoice" | "region";

export const SETTINGS_TABS: { key: SettingsTab; label: string }[] = [
  { key: "shipping", label: "Shipping & tax" },
  { key: "orders", label: "Orders & coupons" },
  { key: "catalogue", label: "Catalogue" },
  { key: "invoice", label: "Invoice & contact" },
  { key: "region", label: "Region" },
];

export const TAB_FIELDS: Record<SettingsTab, (keyof StoreSettingsForm)[]> = {
  shipping: ["shippingFee", "freeShippingAbove", "defaultTaxRate"],
  orders: ["maxCouponAmount", "minimumAge", "stuckOrderDays", "orderPrefix"],
  catalogue: ["pageSize", "lowStockThreshold"],
  invoice: ["legalName", "address", "gstin", "state", "email", "phone", "grievanceOfficer", "grievanceEmail", "logoUrl"],
  region: ["timeZone"],
};

const TAB_GROUPS: Record<SettingsTab, (keyof StoreSettings)[]> = {
  shipping: ["commerce"],
  orders: ["commerce"],
  catalogue: ["catalog"],
  invoice: ["invoice", "storefront"],
  region: ["regional"],
};

/**
 * Only this tab's groups. Commerce spans two tabs, so it is sent whole from the form; the other tab can hold no
 * unsaved edits because switching tabs asks to discard them first.
 */
export const toTabPayload = (f: StoreSettingsForm, tab: SettingsTab): Partial<StoreSettings> => {
  const all = toPayload(f);
  return Object.fromEntries(TAB_GROUPS[tab].map((g) => [g, all[g]])) as Partial<StoreSettings>;
};

export const tabDirty = (form: StoreSettingsForm, saved: StoreSettingsForm, tab: SettingsTab) =>
  TAB_FIELDS[tab].some((k) => form[k] !== saved[k]);
