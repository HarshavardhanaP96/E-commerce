import { describe, expect, it } from "vitest";
import { BUSINESS, POLICIES, isFilledIn } from "./policies";
import { CONSENT_VERSION } from "../utils/consent";

describe("isFilledIn", () => {
  it("is false for the built-in [placeholders] and empty values", () => {
    expect(isFilledIn(BUSINESS.address)).toBe(false); // "[Registered address]" until a store fills it in
    for (const v of ["", "   ", undefined, null, "[support@your-domain]"]) expect(isFilledIn(v)).toBe(false);
  });
  it("is true for real text, including text that merely contains brackets", () => {
    expect(isFilledIn("12 MG Road, Bengaluru")).toBe(true);
    expect(isFilledIn("Shop [Main branch] Ltd")).toBe(true);
  });
});

describe("cookie and privacy notices", () => {
  it("re-asks for cookie consent whenever the Cookie & Storage Notice changes", () => {
    expect(CONSENT_VERSION).toBe(POLICIES.cookies.updated);
  });

  it("name the advertising partners and how to withdraw", () => {
    const privacy = JSON.stringify(POLICIES.privacy);
    for (const word of ["Google", "Meta", "Cookie settings"]) expect(privacy).toContain(word);
    const cookies = JSON.stringify(POLICIES.cookies);
    for (const name of ["_ga", "_gcl_au", "_fbp", "_fbc", "Cookie settings"]) expect(cookies).toContain(name);
  });

  it("say that purchases are sent from the server, with Meta getting hashed contact details", () => {
    const privacy = JSON.stringify(POLICIES.privacy);
    expect(privacy).toContain("hashed");
    expect(privacy).not.toContain("We don't send them your name, email, phone or address");
    expect(JSON.stringify(POLICIES.cookies)).toContain("from its own server");
    expect(POLICIES.cookies.updated).toBe("2026-10-31"); // = backend app.marketing.pii-consent-version
  });
});
