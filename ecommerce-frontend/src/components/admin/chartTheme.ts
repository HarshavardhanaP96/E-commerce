/**
 * Chart palette — mirrors the admin tokens in styles/_tokens.scss (Chart.js can't read CSS vars).
 * Categorical order is fixed so the same series keeps the same colour across charts.
 */
export const chartColors = {
  series: ["#4f46e5", "#0ea5e9", "#10b981", "#f59e0b", "#ec4899", "#94a3b8"],
  success: "#10b981",
  warning: "#f59e0b",
  danger: "#ef4444",
  info: "#0ea5e9",
  accent: "#4f46e5",
  /** Translucent fill for area charts. */
  fill: (hex: string) => `${hex}1f`,
};
