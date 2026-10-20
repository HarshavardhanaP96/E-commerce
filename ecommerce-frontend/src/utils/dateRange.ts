/** Date-range presets for the analytics filters. Dates are local calendar days as YYYY-MM-DD. */
export type Preset = "7d" | "30d" | "month" | "lastMonth" | "quarter" | "fy" | "custom";

export type DateRange = { from: string; to: string };

export const PRESETS: { value: Preset; label: string }[] = [
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
  { value: "month", label: "This month" },
  { value: "lastMonth", label: "Last month" },
  { value: "quarter", label: "This quarter" },
  { value: "fy", label: "This financial year" },
  { value: "custom", label: "Custom" },
];

export const isoDay = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

const day = (y: number, m: number, d: number) => isoDay(new Date(y, m, d));

/** The range a preset stands for on {@code today}; "custom" falls back to the last 30 days. */
export const presetRange = (preset: Preset, today = new Date()): DateRange => {
  const y = today.getFullYear();
  const m = today.getMonth();
  const to = isoDay(today);
  switch (preset) {
    case "7d":
      return { from: day(y, m, today.getDate() - 6), to };
    case "month":
      return { from: day(y, m, 1), to };
    case "lastMonth":
      return { from: day(y, m - 1, 1), to: day(y, m, 0) };
    case "quarter":
      return { from: day(y, m - (m % 3), 1), to };
    case "fy":
      return { from: day(m >= 3 ? y : y - 1, 3, 1), to };
    default:
      return { from: day(y, m, today.getDate() - 29), to };
  }
};
