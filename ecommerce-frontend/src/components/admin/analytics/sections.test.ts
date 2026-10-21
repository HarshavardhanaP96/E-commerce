import { describe, expect, it } from "vitest";
import { alignPrevious, bucketLabel, csvPath, fileNameFrom, formatHours, heatLevel, isRangeOnly, TABS, tabFrom } from "./sections";

describe("analytics sections", () => {
  it("reads the tab from the URL and falls back to overview", () => {
    expect(tabFrom("sales")).toBe("sales");
    expect(tabFrom("products")).toBe("products");
    expect(tabFrom("tax")).toBe("tax");
    expect(tabFrom(null)).toBe("overview");
    expect(tabFrom("nope")).toBe("overview");
  });

  it("lists every analytics tab once, overview first", () => {
    expect(TABS.map((t) => t.id))
      .toEqual(["overview", "sales", "orders", "products", "customers", "coupons", "payments", "tax"]);
    expect(TABS[0]).toEqual({ id: "overview", label: "Overview" });
  });

  it("keeps step 3's tabs to the date range and gives the rest every filter", () => {
    expect(TABS.filter((t) => isRangeOnly(t.id)).map((t) => t.id))
      .toEqual(["products", "customers", "coupons", "payments", "tax"]);
    expect(isRangeOnly("overview")).toBe(false);
    expect(isRangeOnly("orders")).toBe(false);
  });

  it("builds a CSV path without empty parameters", () => {
    expect(csvPath("sales", { from: "2026-10-01", to: "2026-10-31", compare: "previous", category: "", limit: undefined }))
      .toBe("/api/v1/dashboard/sales?from=2026-10-01&to=2026-10-31&compare=previous&format=csv");
  });

  it("takes the file name from Content-Disposition", () => {
    expect(fileNameFrom('attachment; filename="overview-2026-10-01_2026-10-31.csv"', "x.csv"))
      .toBe("overview-2026-10-01_2026-10-31.csv");
    expect(fileNameFrom(null, "x.csv")).toBe("x.csv");
  });

  it("scales heat into five levels and never divides by zero", () => {
    expect(heatLevel(0, 10)).toBe(0);
    expect(heatLevel(1, 10)).toBe(1);
    expect(heatLevel(10, 10)).toBe(4);
    expect(heatLevel(5, 0)).toBe(0);
  });

  it("labels buckets without shifting the date", () => {
    expect(bucketLabel("2026-10-05", "day")).toBe("5 Oct");
    expect(bucketLabel("2026-10-01", "month")).toBe("Oct 2026");
  });
});

describe("alignPrevious", () => {
  const point = (start: string, revenue: number) => ({ start, revenue, orders: 0 });

  it("lines the comparison up by position and pads with zeros", () => {
    expect(alignPrevious([point("2026-10-01", 1), point("2026-10-02", 2)], [point("2026-09-29", 5)])).toEqual([5, 0]);
  });

  it("is empty when there is no comparison", () => {
    expect(alignPrevious([point("2026-10-01", 1)], null)).toEqual([]);
  });
});

describe("formatHours", () => {
  it("shows hours under two days and days after", () => {
    expect(formatHours(null)).toBe("—");
    expect(formatHours(5)).toBe("5 h");
    expect(formatHours(30)).toBe("30 h");
    expect(formatHours(72)).toBe("3 days");
    expect(formatHours(60)).toBe("2.5 days");
  });
});

describe("alignPrevious with a picker", () => {
  it("can line up orders instead of revenue", () => {
    const p = (orders: number) => ({ start: "2026-10-01", revenue: 0, orders });
    expect(alignPrevious([p(1), p(2)], [p(7)], (x) => x.orders)).toEqual([7, 0]);
  });
});
