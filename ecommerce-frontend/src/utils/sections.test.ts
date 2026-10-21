import { describe, expect, it } from "vitest";
import { daysLeftLabel, exportFileName, exportPath, REMOVED_PRODUCT, trendChange } from "./sections";

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
});
