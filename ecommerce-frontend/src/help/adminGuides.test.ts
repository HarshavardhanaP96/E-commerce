import { describe, expect, it } from "vitest";
import { GUIDES, GUIDE_GROUPS, guideBySlug, guideForPath } from "./adminGuides";

describe("admin guides", () => {
  it("has unique slugs", () => {
    const slugs = GUIDES.map((g) => g.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
  });

  it("links only to guides that exist", () => {
    for (const g of GUIDES) for (const r of g.related) expect(guideBySlug(r), `${g.slug} → ${r}`).toBeDefined();
  });

  it("puts every guide in a listed group", () => {
    for (const g of GUIDES) expect(GUIDE_GROUPS).toContain(g.group);
  });
});

describe("guideForPath", () => {
  it.each([
    ["/admin/analytics", "analytics"],
    ["/admin/product", "products"],
    ["/admin/product/new", "add-product"],
    ["/admin/product/abc", "edit-product"],
    ["/admin/category", "categories"],
    ["/admin/customer", "customers"],
    ["/admin/customer/abc", "customer-roles"],
    ["/admin/transaction", "orders"],
    ["/admin/transaction/abc", "fulfil-order"],
    ["/admin/app/coupon", "coupons"],
    ["/admin/app/marketing", "marketing"],
    ["/admin/audit", "audit-log"],
    ["/admin/settings", "store-settings"],
    ["/admin/somewhere-else", "getting-started"],
    ["/admin/help/tables", ""],
  ])("%s opens %s", (path, slug) => {
    expect(guideForPath(path)).toBe(slug);
  });

  it("opens a guide that exists on every admin page", () => {
    for (const path of ["/admin/analytics", "/admin/audit", "/admin/settings", "/admin/product/new", "/admin/app/marketing"]) {
      expect(guideBySlug(guideForPath(path))).toBeDefined();
    }
  });
});
