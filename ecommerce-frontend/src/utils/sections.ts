import { getToken } from "../redux/api/baseQuery";
import { server } from "../redux/config";
import type { AudienceCell } from "../types/sections";
import type { DateRange } from "./dateRange";

/** The tables that download as CSV (GET /dashboard/export/{table}). */
export type ExportTable = "top-products" | "categories" | "stock" | "idle-products" | "trending" | "bought-together"
  | "top-customers" | "places" | "audience" | "coupons" | "payment-methods" | "refunds" | "tax";

/** The name the server gives a product that is no longer in the catalogue. */
export const REMOVED_PRODUCT = "Removed product";

/** A rising or falling product's change in units: "New" when nothing sold before, else a signed percent. */
export const trendChange = ({ change, isNew }: { change: number | null; isNew: boolean }) =>
  isNew || change === null ? "New" : `${change > 0 ? "+" : ""}${change}%`;

/**
 * What every hidden audience cell shows instead of its figures (the server sends none). Neutral on purpose: a cell
 * may be a group of fewer than 5 buyers or a bigger one hidden so the totals cannot give such a group away, and
 * telling them apart would itself give something away.
 */
export const HIDDEN_CELL = "hidden for privacy";

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;

/** An audience cell's main figure (revenue) and its detail line, or the hidden label and no detail. */
export const audienceCellText = (cell: AudienceCell, money: (amount: number) => string) =>
  cell.small || cell.revenue === null
    ? { value: HIDDEN_CELL, detail: null }
    : {
      value: money(cell.revenue),
      detail: `${cell.share}% · ${plural(cell.buyers ?? 0, "buyer")} · ${plural(cell.orders ?? 0, "order")}`,
    };

const GENDER_LABELS: Record<string, string> = { female: "Female", male: "Male", unknown: "Unknown", all: "All" };

export const audienceGenderLabel = (gender: string) => GENDER_LABELS[gender] ?? gender;

export const audienceBandLabel = (band: string) => (band === "unknown" ? "Age unknown" : band);

/** The segments Sales and Products can be narrowed to (the server's values, in the audience table's order). */
export const AUDIENCE_GENDERS = ["female", "male", "unknown"] as const;
export const AUDIENCE_BANDS = ["18-24", "25-34", "35-44", "45-54", "55+", "unknown"] as const;

/** An audience segment; a missing field means any. */
export type AudienceFilter = { gender?: string; ageBand?: string };

/** "Female, 25-34", "Gender unknown", "Age unknown"; empty for everyone. */
export const audienceName = ({ gender, ageBand }: AudienceFilter) => [
  gender && (gender === "unknown" ? "Gender unknown" : audienceGenderLabel(gender)),
  ageBand && audienceBandLabel(ageBand),
].filter(Boolean).join(", ");

/** Only the Products tables read the segment; the server ignores it on the others. */
export const exportPath = (table: ExportTable, range: DateRange, audience: AudienceFilter = {}) => {
  const q = new URLSearchParams({ from: range.from, to: range.to });
  if (audience.gender) q.set("gender", audience.gender);
  if (audience.ageBand) q.set("ageBand", audience.ageBand);
  return `/api/v1/dashboard/export/${table}?${q}`;
};

export const exportFileName = (table: ExportTable, range: DateRange, audience: AudienceFilter = {}) =>
  `${[table, audience.gender, audience.ageBand].filter(Boolean).join("-")}_${range.from}_${range.to}.csv`;

export const daysLeftLabel = (days: number | null) =>
  days === null ? "No sales in range" : days === 0 ? "Out of stock" : `${days} ${days === 1 ? "day" : "days"}`;

/** Downloads one table as CSV with the admin's token (a plain link would not carry it). */
export const downloadCsv = async (table: ExportTable, range: DateRange, audience: AudienceFilter = {}) => {
  const res = await fetch(`${server}${exportPath(table, range, audience)}`, {
    headers: { Authorization: `Bearer ${getToken() ?? ""}` },
  });
  if (!res.ok) throw new Error(String(res.status));
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = exportFileName(table, range, audience);
  a.click();
  URL.revokeObjectURL(url);
};
