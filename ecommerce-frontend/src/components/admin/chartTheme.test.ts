import { describe, expect, it } from "vitest";
import { ADMIN, SHARED } from "../../styles/readTokens";
import { chartColors } from "./chartTheme";

describe("chart colours follow the design tokens", () => {
  it("status colours equal the status tokens", () => {
    const t = SHARED();
    expect([chartColors.success, chartColors.warning, chartColors.danger, chartColors.info])
      .toEqual([t["--success"], t["--warning"], t["--danger"], t["--info"]]);
  });

  it("text and gridlines equal the admin tokens", () => {
    const t = ADMIN();
    expect([chartColors.ink, chartColors.muted, chartColors.grid]).toEqual([t["--text"], t["--text-muted"], t["--border"]]);
  });

  it("series colours are the validated set and never reuse a status colour", () => {
    expect(chartColors.series).toEqual(["#0079b0", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7"]);
    const status = [chartColors.success, chartColors.warning, chartColors.danger, chartColors.info];
    expect(chartColors.series.filter((s) => status.includes(s))).toEqual([]);
  });
});
