// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

type Consent = typeof import("./consent");
type Attribution = typeof import("./attribution");
let consent: Consent;
let attribution: Attribution;

const LANDING = { pathname: "/product/lamp", search: `?utm_source=google&utm_campaign=${"d".repeat(200)}&gclid=Cj0-abc&fbclid=IwAR-xyz` };
const clearCookies = () => document.cookie.split(";").map((c) => c.split("=")[0].trim()).filter(Boolean)
  .forEach((n) => { document.cookie = `${n}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`; });

beforeEach(async () => {
  localStorage.clear();
  sessionStorage.clear();
  clearCookies();
  vi.restoreAllMocks();
  vi.resetModules(); // module state: the remembered landing URL and the consent subscription
  consent = await import("./consent");
  attribution = await import("./attribution");
});

const choose = (analytics: boolean, marketing: boolean) => consent.saveConsent({ functional: false, analytics, marketing });

describe("attribution", () => {
  it("keeps nothing and sends nothing before the shopper chooses", () => {
    attribution.captureAttribution(LANDING);
    expect(sessionStorage.getItem(attribution.ATTRIBUTION_KEY)).toBeNull();
    expect(attribution.attributionForCheckout()).toBeUndefined();
  });

  it("with marketing consent keeps campaign tags and click ids, capped, and reads the Meta cookies at checkout", () => {
    choose(false, true);
    attribution.captureAttribution(LANDING);
    document.cookie = "_fbp=fb.1.1730000000000.1098115397; path=/";
    document.cookie = "_ga=GA1.1.1234567890.1730000000; path=/";
    const a = attribution.attributionForCheckout(1730000000000)!;
    expect(a.utmSource).toBe("google");
    expect(a.utmCampaign).toHaveLength(150);
    expect(a.gclid).toBe("Cj0-abc");
    expect(a.fbp).toBe("fb.1.1730000000000.1098115397");
    expect(a.fbc).toBe("fb.1.1730000000000.IwAR-xyz"); // made from fbclid when the _fbc cookie is missing
    expect(a.gaClientId).toBeUndefined();
    expect(a.landingPath).toBe("/product/lamp");
    expect(a.consent).toEqual({ analytics: false, marketing: true, version: consent.CONSENT_VERSION });
  });

  it("with analytics only sends the landing path and the GA client id", () => {
    choose(true, false);
    attribution.captureAttribution(LANDING);
    document.cookie = "_ga=GA1.1.1234567890.1730000000; path=/";
    expect(attribution.attributionForCheckout()).toEqual({ landingPath: "/product/lamp", gaClientId: "1234567890.1730000000",
      consent: { analytics: true, marketing: false, version: consent.CONSENT_VERSION } });
  });

  it("captures the landing URL when consent comes later on the same page", () => {
    attribution.captureAttribution(LANDING);
    choose(true, true);
    expect(attribution.attributionForCheckout()?.utmSource).toBe("google");
  });

  it("keeps the first touch of the tab session", async () => {
    choose(true, true);
    attribution.captureAttribution(LANDING);
    vi.resetModules(); // a reload landing from another campaign
    attribution = await import("./attribution");
    attribution.captureAttribution({ pathname: "/", search: "?utm_source=meta" });
    expect(attribution.attributionForCheckout()?.utmSource).toBe("google");
  });

  it("forgets campaign data when marketing consent is withdrawn", () => {
    choose(true, true);
    attribution.captureAttribution(LANDING);
    choose(false, false);
    expect(sessionStorage.getItem(attribution.ATTRIBUTION_KEY)).toBeNull();
    expect(attribution.attributionForCheckout()).toEqual({ consent: { analytics: false, marketing: false, version: consent.CONSENT_VERSION } });
  });

  it("ignores admin landings and survives blocked storage", () => {
    choose(true, true);
    attribution.captureAttribution({ pathname: "/admin/app/marketing", search: "?utm_source=x" });
    expect(attribution.attributionForCheckout()?.utmSource).toBeUndefined();
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    expect(() => attribution.captureAttribution(LANDING)).not.toThrow();
  });
});
