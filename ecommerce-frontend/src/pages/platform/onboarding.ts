import type { StoreTypeInfo } from "../../redux/api/platformAPI";

/** Same rule as the API: 1–40 lowercase letters, digits or dashes, not starting or ending with a dash. */
export const slugFromName = (name: string) =>
  name
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+/, "")
    .slice(0, 40)
    .replace(/-+$/, "");

/** "Variants on · GST 5% · low stock at 3": what a type writes into the new store. */
export const presetSummary = ({ features, settings }: StoreTypeInfo) => {
  const c = settings.commerce;
  const parts: string[] = [];
  if (features.variants !== undefined) parts.push(`Variants ${features.variants ? "on" : "off"}`);
  if (c.defaultTaxRate !== null) parts.push(`GST ${c.defaultTaxRate}%`);
  if (c.shippingFee !== null) {
    parts.push(`shipping ₹${c.shippingFee}${c.freeShippingAbove !== null ? `, free above ₹${c.freeShippingAbove}` : ""}`);
  }
  if (settings.catalog.lowStockThreshold !== null) parts.push(`low stock at ${settings.catalog.lowStockThreshold}`);
  if (c.stuckOrderDays !== null) parts.push(`orders flagged after ${c.stuckOrderDays} day${c.stuckOrderDays === 1 ? "" : "s"}`);
  return parts.length ? parts.join(" · ") : "Platform defaults";
};

/** API validation messages start with the field: "slug: use 1-40 …". */
export const fieldOf = (message: string) => /^([a-zA-Z.]+):\s/.exec(message)?.[1] ?? null;
