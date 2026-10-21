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
 * What a hidden audience cell shows instead of its figures (the server sends none): a group of fewer than 5 buyers,
 * or a cell hidden so the totals cannot give such a group away.
 */
export const SMALL_GROUP = "fewer than 5 buyers";

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;

/** An audience cell's main figure (revenue) and its detail line, or the small-group text and no detail. */
export const audienceCellText = (cell: AudienceCell, money: (amount: number) => string) =>
  cell.small || cell.revenue === null
    ? { value: SMALL_GROUP, detail: null }
    : {
      value: money(cell.revenue),
      detail: `${cell.share}% · ${plural(cell.buyers ?? 0, "buyer")} · ${plural(cell.orders ?? 0, "order")}`,
    };

const GENDER_LABELS: Record<string, string> = { female: "Female", male: "Male", unknown: "Unknown", all: "All" };

export const audienceGenderLabel = (gender: string) => GENDER_LABELS[gender] ?? gender;

export const audienceBandLabel = (band: string) => (band === "unknown" ? "Age unknown" : band);

export const exportPath = (table: ExportTable, range: DateRange) =>
  `/api/v1/dashboard/export/${table}?${new URLSearchParams({ from: range.from, to: range.to })}`;

export const exportFileName = (table: ExportTable, range: DateRange) => `${table}_${range.from}_${range.to}.csv`;

export const daysLeftLabel = (days: number | null) =>
  days === null ? "No sales in range" : days === 0 ? "Out of stock" : `${days} ${days === 1 ? "day" : "days"}`;

/** Downloads one table as CSV with the admin's token (a plain link would not carry it). */
export const downloadCsv = async (table: ExportTable, range: DateRange) => {
  const res = await fetch(`${server}${exportPath(table, range)}`, {
    headers: { Authorization: `Bearer ${getToken() ?? ""}` },
  });
  if (!res.ok) throw new Error(String(res.status));
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = exportFileName(table, range);
  a.click();
  URL.revokeObjectURL(url);
};
