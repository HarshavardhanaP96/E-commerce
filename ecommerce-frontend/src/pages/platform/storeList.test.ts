import { describe, expect, it } from "vitest";
import type { PlatformTenant } from "../../redux/api/platformAPI";
import { byChip, domainSummary, findBySlug, statusNote, typeLabel } from "./storeList";

const store = (slug: string, status: PlatformTenant["status"], extra: Partial<PlatformTenant> = {}): PlatformTenant => ({
  id: `id-${slug}`, slug, name: slug, status, domains: [`${slug}.test`], theme: {}, type: "GENERAL", ...extra,
});

const all = [
  store("a", "ACTIVE"), store("t", "TRIAL"), store("s", "SUSPENDED"),
  store("c", "CANCELLED"), store("d", "SCHEDULED_FOR_DELETION"),
];

describe("store list", () => {
  it("filters by status chip, with Closing covering cancelled and scheduled-for-deletion stores", () => {
    expect(byChip(all, "all")).toHaveLength(5);
    expect(byChip(all, "active").map((t) => t.slug)).toEqual(["a"]);
    expect(byChip(all, "closing").map((t) => t.slug)).toEqual(["c", "d"]);
  });

  it("shows the first domain and how many more", () => {
    expect(domainSummary(["a.test"])).toBe("a.test");
    expect(domainSummary(["a.test", "b.test", "c.test"])).toBe("a.test +2");
    expect(domainSummary([])).toBe("—");
  });

  it("finds a store by slug for deep links, and nothing for an unknown slug", () => {
    expect(findBySlug(all, "t")?.id).toBe("id-t");
    expect(findBySlug(all, "nope")).toBeUndefined();
    expect(findBySlug(undefined, "t")).toBeUndefined();
  });

  it("names types and explains dates", () => {
    expect(typeLabel("ELECTRONICS")).toBe("Electronics");
    expect(statusNote(store("t", "TRIAL", { trialEndsAt: "2026-11-20T00:00:00Z" }))).toBe("Trial ends 20 Nov 2026");
    expect(statusNote(store("d", "SCHEDULED_FOR_DELETION", { deletionDueAt: "2026-12-05T00:00:00Z" })))
      .toBe("Data deleted on 5 Dec 2026");
    expect(statusNote(store("a", "ACTIVE"))).toBeNull();
  });
});
