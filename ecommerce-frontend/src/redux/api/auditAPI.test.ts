import { describe, expect, it } from "vitest";
import { AUDIT_ACTIONS } from "./auditAPI";

describe("AUDIT_ACTIONS", () => {
  it("lets the audit log be filtered by marketing integration changes", () => {
    expect(AUDIT_ACTIONS).toEqual(expect.arrayContaining(["INTEGRATION_UPDATED", "INTEGRATION_DISCONNECTED", "INTEGRATION_TESTED"]));
  });

  it("lists each action once", () => {
    expect(new Set(AUDIT_ACTIONS).size).toBe(AUDIT_ACTIONS.length);
  });
});
