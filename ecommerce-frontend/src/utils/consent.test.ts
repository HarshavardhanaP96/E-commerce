// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type ConsentModule = typeof import("./consent");
let consent: ConsentModule;

const DAY = 24 * 60 * 60 * 1000;
const cookieNames = () => document.cookie.split(";").map((c) => c.split("=")[0].trim()).filter(Boolean).sort();

beforeEach(async () => {
  localStorage.clear();
  for (const name of cookieNames()) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  vi.resetModules(); // the module keeps listeners and an in-memory fallback
  consent = await import("./consent");
});

afterEach(() => vi.restoreAllMocks());

describe("hasConsent", () => {
  it("is false for every optional category before the shopper chooses", () => {
    expect(consent.currentChoice()).toBeNull();
    for (const c of consent.CATEGORIES) expect(consent.hasConsent(c)).toBe(false);
  });

  it("follows a saved choice per category", () => {
    consent.saveConsent({ functional: false, analytics: true, marketing: false });
    expect(consent.hasConsent("analytics")).toBe(true);
    expect(consent.hasConsent("marketing")).toBe(false);
    expect(consent.hasConsent("functional")).toBe(false);
  });

  it("stores the choice with the policy version and time in localStorage", () => {
    consent.saveConsent(consent.ALL, new Date("2026-10-30T10:00:00Z"));
    expect(JSON.parse(localStorage.getItem(consent.CONSENT_KEY)!)).toEqual({
      functional: true, analytics: true, marketing: true, version: consent.CONSENT_VERSION, at: "2026-10-30T10:00:00.000Z",
    });
  });
});

describe("asking again", () => {
  it("asks again once the choice is older than six months", () => {
    const at = new Date("2026-01-01T00:00:00Z");
    consent.saveConsent(consent.ALL, at);
    expect(consent.currentChoice(at.getTime() + 180 * DAY)).not.toBeNull();
    expect(consent.currentChoice(at.getTime() + 184 * DAY)).toBeNull();
  });

  it("asks again when the cookie policy version changed", () => {
    localStorage.setItem(consent.CONSENT_KEY, JSON.stringify({
      functional: true, analytics: true, marketing: true, version: "2020-01-01", at: new Date().toISOString(),
    }));
    expect(consent.currentChoice()).toBeNull();
    expect(consent.hasConsent("analytics")).toBe(false);
  });

  it("treats a corrupt or tampered entry as no choice", () => {
    for (const raw of ["{not json", JSON.stringify({ analytics: "yes", version: consent.CONSENT_VERSION, at: "x" })]) {
      localStorage.setItem(consent.CONSENT_KEY, raw);
      expect(consent.currentChoice()).toBeNull();
    }
  });
});

describe("blocked storage", () => {
  it("keeps the choice in memory for this visit when localStorage throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("SecurityError"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("QuotaExceeded"); });
    expect(consent.hasConsent("analytics")).toBe(false);
    expect(() => consent.saveConsent({ functional: false, analytics: true, marketing: false })).not.toThrow();
    expect(consent.hasConsent("analytics")).toBe(true);
  });
});

describe("onConsentChange and withdrawal", () => {
  it("tells subscribers about every saved choice until they unsubscribe", () => {
    const seen: unknown[] = [];
    const off = consent.onConsentChange((c) => seen.push(c));
    consent.saveConsent(consent.ALL);
    off();
    consent.saveConsent(consent.NONE);
    expect(seen).toEqual([consent.ALL]);
  });

  it("deletes the analytics and marketing cookies of every category that is off", () => {
    for (const c of ["_ga=GA1.1.1", "_ga_ABC123=GS1", "_gcl_au=1.1", "_fbp=fb.1", "_fbc=fb.1.x", "keep=1"]) {
      document.cookie = `${c}; path=/`;
    }
    consent.saveConsent({ functional: false, analytics: false, marketing: true });
    expect(cookieNames()).toEqual(["_fbc", "_fbp", "_gcl_au", "keep"]);
    consent.saveConsent(consent.NONE);
    expect(cookieNames()).toEqual(["keep"]);
  });

  it("opens the cookie settings through a window event", () => {
    const opened = vi.fn();
    window.addEventListener(consent.COOKIE_SETTINGS_EVENT, opened);
    consent.openCookieSettings();
    expect(opened).toHaveBeenCalledTimes(1);
    window.removeEventListener(consent.COOKIE_SETTINGS_EVENT, opened);
  });
});
