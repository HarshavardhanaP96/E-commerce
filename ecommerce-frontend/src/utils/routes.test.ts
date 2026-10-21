import { describe, expect, it } from "vitest";
import { surfaceFor } from "./routes";

describe("surfaceFor", () => {
  it("is admin for /admin and anything under it", () => {
    expect(surfaceFor("/admin")).toBe("admin");
    expect(surfaceFor("/admin/analytics")).toBe("admin");
  });

  it("is store everywhere else, including look-alike paths", () => {
    for (const p of ["/", "/search", "/administrator", "/platform"]) expect(surfaceFor(p)).toBe("store");
  });
});
