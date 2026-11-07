import { RangeSectionName, SectionName, SeriesPoint } from "../../../types/analytics";
import { AUDIENCE_BANDS, AUDIENCE_GENDERS, AudienceFilter } from "../../../utils/sections";

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
  { id: "location", label: "Location" },
];

export const tabFrom = (value: string | null): SectionName => TABS.find((t) => t.id === value)?.id ?? "overview";

const RANGE_ONLY: readonly SectionName[] = ["products", "customers", "coupons", "payments", "tax"];

/** Step 3's tabs read only the date range, so the compare, basis and category filters are hidden on them. */
export const isRangeOnly = (tab: SectionName): tab is RangeSectionName => RANGE_ONLY.includes(tab);

/** Only Sales and Products can be narrowed to a gender and age band. */
export const usesAudience = (tab: SectionName) => tab === "sales" || tab === "products";

/** The segment in the URL; a value the server would reject (a hand-edited link) is dropped. */
export const audienceFrom = (params: URLSearchParams): AudienceFilter => {
  const gender = params.get("gender") ?? "";
  const ageBand = params.get("ageBand") ?? "";
  return {
    ...((AUDIENCE_GENDERS as readonly string[]).includes(gender) ? { gender } : {}),
    ...((AUDIENCE_BANDS as readonly string[]).includes(ageBand) ? { ageBand } : {}),
  };
};

/** URL changes for opening a tab: drill-downs reset, and the segment goes where it would do nothing. */
export const switchTab = (tab: SectionName): Record<string, string> => ({
  tab: tab === "overview" ? "" : tab, country: "", state: "", pinPrefix: "",
  ...(usesAudience(tab) ? {} : { gender: "", ageBand: "" }),
});

/** The URL parameter that drills into a Location row, or null when the row cannot be opened. */
export const drillTo = (level: string, key: string): Record<string, string> | null => {
  if (level === "country") return key === "india" ? { country: "India" } : null;
  if (level === "state") return /^[A-Z]{2}$/.test(key) ? { state: key } : null;
  if (level === "pin_prefix") return /^\d{3}$/.test(key) ? { pinPrefix: key } : null;
  return null;
};

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
