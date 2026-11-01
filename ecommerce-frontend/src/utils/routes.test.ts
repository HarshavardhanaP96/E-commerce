import { describe, expect, it } from "vitest";
import { adminPageVisible, showsBottomNav, surfaceFor } from "./routes";

describe("surfaceFor", () => {
  it("is admin for /admin and anything under it", () => {
    expect(surfaceFor("/admin")).toBe("admin");
    expect(surfaceFor("/admin/analytics")).toBe("admin");
    expect(surfaceFor("/admin/app/marketing")).toBe("admin");
    expect(showsBottomNav("/admin/app/marketing")).toBe(false);
  });

  it("is store everywhere else, including look-alike paths", () => {
    for (const p of ["/", "/search", "/administrator", "/platform"]) expect(surfaceFor(p)).toBe("store");
  });
});

describe("adminPageVisible", () => {
  it("shows Marketing only while the store's marketing switch is on", () => {
    expect(adminPageVisible("/admin/app/marketing", { marketing: true })).toBe(true);
    expect(adminPageVisible("/admin/app/marketing", { marketing: false })).toBe(false);
    expect(adminPageVisible("/admin/app/marketing", {})).toBe(false); // config not loaded: hidden, the API would 404
  });

  it("always shows pages that have no switch", () => {
    for (const url of ["/admin/app/coupon", "/admin/settings"]) expect(adminPageVisible(url, {})).toBe(true);
  });
});
