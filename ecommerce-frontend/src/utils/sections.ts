import { getToken } from "../redux/api/baseQuery";
import { server } from "../redux/config";
import type { DateRange } from "./dateRange";

export type SectionTab = "overview" | "products" | "customers" | "coupons" | "payments" | "tax";

export const SECTION_TABS: { value: SectionTab; label: string }[] = [
  { value: "overview", label: "Overview" },
  { value: "products", label: "Products" },
  { value: "customers", label: "Customers" },
  { value: "coupons", label: "Coupons" },
  { value: "payments", label: "Payments" },
  { value: "tax", label: "Tax" },
];

/** The tables that download as CSV (GET /dashboard/export/{table}). */
export type ExportTable = "top-products" | "categories" | "stock" | "idle-products" | "top-customers" | "places"
  | "coupons" | "payment-methods" | "refunds" | "tax";

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
