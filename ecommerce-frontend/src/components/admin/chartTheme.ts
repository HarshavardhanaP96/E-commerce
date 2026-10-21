/**
 * Chart palette. Chart.js can't read CSS variables, so these mirror _tokens.scss; chartTheme.test.ts
 * fails if they drift. Series order is fixed so a series keeps its colour across charts.
 * Series checked with the dataviz validator (light, white surface): lightness, chroma, CVD and
 * normal-vision separation all pass; slots 3-5 are under 3:1 on white, so charts keep legends/labels.
 */
export const chartColors = {
  series: ["#0079b0", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7"],
  success: "#047857",
  warning: "#b45309",
  danger: "#b91c1c",
  info: "#1d4ed8",
  ink: "#102227",
  muted: "#5b6b6e",
  grid: "#dbe1e1",
  /** Translucent fill for area charts. */
  fill: (hex: string) => `${hex}1f`,
};
