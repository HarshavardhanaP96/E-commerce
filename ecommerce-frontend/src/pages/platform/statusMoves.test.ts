import { describe, expect, it } from "vitest";
import { statusMoves } from "./statusMoves";

describe("status moves", () => {
  it("offers what each status allows", () => {
    expect(statusMoves({ slug: "acme", status: "ACTIVE" })).toEqual(["suspend", "cancel", "offboard"]);
    expect(statusMoves({ slug: "acme", status: "TRIAL" })).toEqual(["activate", "suspend", "cancel", "offboard"]);
    expect(statusMoves({ slug: "acme", status: "SUSPENDED" })).toEqual(["activate", "cancel", "offboard"]);
    expect(statusMoves({ slug: "acme", status: "CANCELLED" })).toEqual(["activate", "offboard"]);
  });

  it("only restores a store scheduled for deletion, and nothing for a deleted one", () => {
    expect(statusMoves({ slug: "acme", status: "SCHEDULED_FOR_DELETION" })).toEqual(["restore"]);
    expect(statusMoves({ slug: "acme", status: "DELETED" })).toEqual([]);
  });

  it("never moves the default store", () => {
    expect(statusMoves({ slug: "default", status: "ACTIVE" })).toEqual([]);
  });
});
