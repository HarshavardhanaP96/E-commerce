import { RangeSectionName, SectionName, SeriesPoint } from "../../../types/analytics";

export { bucketLabel } from "../../../utils/dateRange";

/** The one Analytics tab bar, in order. */
export const TABS: { id: SectionName; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "sales", label: "Sales" },
  { id: "orders", label: "Orders" },
  { id: "products", label: "Products" },
  { id: "customers", label: "Customers" },
  { id: "coupons", label: "Coupons" },
  { id: "payments", label: "Payments" },
  { id: "tax", label: "Tax" },
];

export const tabFrom = (value: string | null): SectionName => TABS.find((t) => t.id === value)?.id ?? "overview";

const RANGE_ONLY: readonly SectionName[] = ["products", "customers", "coupons", "payments", "tax"];

/** Step 3's tabs read only the date range, so the compare, basis and category filters are hidden on them. */
export const isRangeOnly = (tab: SectionName): tab is RangeSectionName => RANGE_ONLY.includes(tab);

export const COMPARE_LABEL = { previous: "vs previous period", year: "vs last year", none: "" } as const;

export const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
export const HOURS = Array.from({ length: 24 }, (_, h) => h);

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

/** The comparison period's value at the same position as each current bucket. */
export const alignPrevious = (
  current: SeriesPoint[], previous?: SeriesPoint[] | null, pick: (p: SeriesPoint) => number = (p) => p.revenue,
): number[] => (previous ? current.map((_, i) => (previous[i] ? pick(previous[i]) : 0)) : []);

export const formatHours = (h?: number | null) => {
  if (h == null) return "—";
  if (h < 48) return `${Math.round(h)} h`;
  return `${(h / 24).toFixed(1).replace(/\.0$/, "")} days`;
};
