import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { AUDIT_ACTIONS } from "./auditAPI";

describe("AUDIT_ACTIONS", () => {
  it("lets the audit log be filtered by marketing integration changes", () => {
    expect(AUDIT_ACTIONS).toEqual(expect.arrayContaining(["INTEGRATION_UPDATED", "INTEGRATION_DISCONNECTED", "INTEGRATION_TESTED"]));
  });

  it("lets the audit log be filtered by product feed changes", () => {
    expect(AUDIT_ACTIONS).toEqual(expect.arrayContaining(["FEED_UPDATED", "FEED_TOKEN_ROTATED"]));
  });

  it("offers every action the backend records, so none is missing from the filter", () => {
    const source = readFileSync(new URL("../../../../backend/src/main/java/com/sixpack/audit/AuditAction.java", import.meta.url), "utf8");
    const body = source.slice(source.indexOf("enum AuditAction {") + 18, source.indexOf(";", source.indexOf("enum AuditAction {")));
    const backend = body.replace(/\/\*[\s\S]*?\*\//g, "").match(/[A-Z_]+/g);
    expect([...AUDIT_ACTIONS].sort()).toEqual([...(backend ?? [])].sort());
  });

  it("lists each action once", () => {
    expect(new Set(AUDIT_ACTIONS).size).toBe(AUDIT_ACTIONS.length);
  });
});
