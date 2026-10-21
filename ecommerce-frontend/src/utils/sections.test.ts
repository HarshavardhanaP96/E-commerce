import { describe, expect, it } from "vitest";
import {
  audienceBandLabel, audienceCellText, audienceGenderLabel, daysLeftLabel, exportFileName, exportPath, REMOVED_PRODUCT,
  HIDDEN_CELL, trendChange,
} from "./sections";

describe("dashboard sections", () => {
  it("builds the export path with the range", () => {
    expect(exportPath("top-products", { from: "2026-10-01", to: "2026-10-31" }))
      .toBe("/api/v1/dashboard/export/top-products?from=2026-10-01&to=2026-10-31");
  });

  it("names the downloaded file after the table and range", () => {
    expect(exportFileName("tax", { from: "2026-04-01", to: "2027-03-31" })).toBe("tax_2026-04-01_2027-03-31.csv");
  });

  it("describes days of stock left", () => {
    expect(daysLeftLabel(0)).toBe("Out of stock");
    expect(daysLeftLabel(null)).toBe("No sales in range");
    expect(daysLeftLabel(1)).toBe("1 day");
    expect(daysLeftLabel(31)).toBe("31 days");
  });

  it("exports the trending and bought-together tables", () => {
    expect(exportPath("trending", { from: "2026-12-01", to: "2026-12-31" }))
      .toBe("/api/v1/dashboard/export/trending?from=2026-12-01&to=2026-12-31");
    expect(exportFileName("bought-together", { from: "2026-12-01", to: "2026-12-31" }))
      .toBe("bought-together_2026-12-01_2026-12-31.csv");
  });

  it("describes a trending product's change", () => {
    expect(trendChange({ change: null, isNew: true })).toBe("New");
    expect(trendChange({ change: 300, isNew: false })).toBe("+300%");
    expect(trendChange({ change: -17, isNew: false })).toBe("-17%");
    expect(trendChange({ change: 0, isNew: false })).toBe("0%");
  });

  it("names the placeholder the server uses for removed products", () => {
    expect(REMOVED_PRODUCT).toBe("Removed product");
  });

  it("exports the audience table", () => {
    expect(exportPath("audience", { from: "2026-09-01", to: "2026-09-30" }))
      .toBe("/api/v1/dashboard/export/audience?from=2026-09-01&to=2026-09-30");
  });

  it("labels every hidden cell neutrally and never shows a number for it", () => {
    // hidden cells may be small groups or cells hidden to protect them: the label must fit both
    expect(HIDDEN_CELL).toBe("hidden for privacy");
    expect(audienceCellText({ buyers: null, orders: null, revenue: null, share: null, small: true }, String))
      .toEqual({ value: "hidden for privacy", detail: null });
  });

  it("shows a cell's revenue, share and buyers", () => {
    expect(audienceCellText({ buyers: 5, orders: 6, revenue: 600, share: 31.6, small: false }, (n) => `Rs ${n}`))
      .toEqual({ value: "Rs 600", detail: "31.6% · 5 buyers · 6 orders" });
    expect(audienceCellText({ buyers: 7, orders: 1, revenue: 0, share: 0, small: false }, String).detail)
      .toBe("0% · 7 buyers · 1 order");
  });

  it("labels the audience rows and columns", () => {
    expect(audienceGenderLabel("female")).toBe("Female");
    expect(audienceGenderLabel("male")).toBe("Male");
    expect(audienceGenderLabel("unknown")).toBe("Unknown");
    expect(audienceGenderLabel("all")).toBe("All");
    expect(audienceBandLabel("25-34")).toBe("25-34");
    expect(audienceBandLabel("unknown")).toBe("Age unknown");
  });
});
