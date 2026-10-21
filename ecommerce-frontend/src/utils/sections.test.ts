import { describe, expect, it } from "vitest";
import { daysLeftLabel, exportFileName, exportPath, SECTION_TABS } from "./sections";

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

  it("starts with the overview tab", () => {
    expect(SECTION_TABS[0]).toEqual({ value: "overview", label: "Overview" });
    expect(SECTION_TABS.map((t) => t.value)).toEqual(["overview", "products", "customers", "coupons", "payments", "tax"]);
  });
});
