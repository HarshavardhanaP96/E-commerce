import { SectionName, SeriesPoint } from "../../../types/analytics";

export const TABS: { id: SectionName; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "sales", label: "Sales" },
  { id: "orders", label: "Orders" },
  { id: "stock", label: "Stock" },
  { id: "customers", label: "Customers" },
  { id: "demand", label: "Demand" },
  { id: "location", label: "Location" },
];

export const tabFrom = (value: string | null): SectionName => TABS.find((t) => t.id === value)?.id ?? "overview";

export const COMPARE_LABEL = { previous: "vs previous period", year: "vs last year", none: "" } as const;

export const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
export const HOURS = Array.from({ length: 24 }, (_, h) => h);

const MONTH_SHORT = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "5 Oct" for days and weeks, "Oct 2026" for months; the date string is read as is, never shifted by time zone. */
export const bucketLabel = (start: string, granularity: "day" | "week" | "month") => {
  const [y, m, d] = start.split("-").map(Number);
  return granularity === "month" ? `${MONTH_SHORT[m - 1]} ${y}` : `${d} ${MONTH_SHORT[m - 1]}`;
};

/** 0 for nothing, else 1..4 relative to the busiest cell. */
export const heatLevel = (value: number, max: number) =>
  max <= 0 || value <= 0 ? 0 : Math.max(1, Math.ceil((value / max) * 4));

export const csvPath = (section: SectionName, params: Record<string, string | number | undefined | null>) => {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") q.set(k, String(v));
  });
  q.set("format", "csv");
  return `/api/v1/dashboard/${section}?${q.toString()}`;
};

export const fileNameFrom = (disposition: string | null, fallback: string) =>
  disposition?.match(/filename="?([^";]+)"?/)?.[1] ?? fallback;

/** The comparison period's revenue at the same position as each current bucket. */
export const alignPrevious = (current: SeriesPoint[], previous?: SeriesPoint[] | null): number[] =>
  previous ? current.map((_, i) => previous[i]?.revenue ?? 0) : [];
