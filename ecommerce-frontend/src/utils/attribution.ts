import { CONSENT_VERSION, currentChoice, onConsentChange } from "./consent";
import { surfaceFor } from "./routes";

/**
 * Marketing first touch for server-side conversions (marketing spec §5.5, phase 3). The landing URL is remembered
 * on load; its campaign tags and click ids are kept in sessionStorage only once marketing cookies are allowed, the
 * landing path once analytics or marketing is. Cookies (_fbp, _fbc, _ga) are read at checkout under the consent in
 * force then. Values are capped here and checked again by the server.
 */
export type CheckoutAttribution = {
  utmSource?: string; utmMedium?: string; utmCampaign?: string; utmContent?: string; utmTerm?: string;
  gclid?: string; fbclid?: string; fbp?: string; fbc?: string; gaClientId?: string; landingPath?: string;
  consent: { analytics: boolean; marketing: boolean; version: string };
};
type Saved = Pick<CheckoutAttribution, "utmSource" | "utmMedium" | "utmCampaign" | "utmContent" | "utmTerm" | "gclid" | "fbclid" | "landingPath">;

export const ATTRIBUTION_KEY = "attribution";
const UTM = { utmSource: "utm_source", utmMedium: "utm_medium", utmCampaign: "utm_campaign", utmContent: "utm_content", utmTerm: "utm_term" } as const;
const MARKETING_FIELDS: (keyof Saved)[] = ["utmSource", "utmMedium", "utmCampaign", "utmContent", "utmTerm", "gclid", "fbclid"];

let landing: { path: string; search: string } | null = null;
let subscribed = false;

const cap = (value: string | null | undefined, max: number) => value?.trim().slice(0, max) || undefined;
const defined = <T extends object>(o: T): T =>
  Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as T;

const read = (): Saved => {
  try {
    const v: unknown = JSON.parse(sessionStorage.getItem(ATTRIBUTION_KEY) ?? "{}");
    return v && typeof v === "object" ? (v as Saved) : {};
  } catch {
    return {};
  }
};

const write = (v: Saved | null) => {
  try {
    if (v && Object.keys(v).length) sessionStorage.setItem(ATTRIBUTION_KEY, JSON.stringify(v));
    else sessionStorage.removeItem(ATTRIBUTION_KEY);
  } catch {
    // storage blocked: checkout then goes without campaign data
  }
};

const cookie = (name: string) => {
  const hit = document.cookie.split(";").map((c) => c.trim()).find((c) => c.startsWith(`${name}=`));
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : undefined;
};

/** Keeps what the current consent allows; values already kept in this tab session win (first touch). */
const store = () => {
  const choice = currentChoice();
  if (!choice) return;
  if (!choice.marketing && !choice.analytics) return write(null);
  const saved = read();
  if (!choice.marketing) MARKETING_FIELDS.forEach((k) => delete saved[k]);
  const next: Saved = {};
  if (landing) {
    const p = new URLSearchParams(landing.search);
    next.landingPath = cap(landing.path, 512);
    if (choice.marketing) {
      for (const [key, param] of Object.entries(UTM)) next[key as keyof typeof UTM] = cap(p.get(param), 150);
      next.gclid = cap(p.get("gclid"), 512);
      next.fbclid = cap(p.get("fbclid"), 512);
    }
  }
  write(defined({ ...next, ...saved }));
};

/** Call once when the app starts. Admin pages are never a landing. */
export const captureAttribution = (loc: { pathname: string; search: string } = window.location) => {
  if (!landing && surfaceFor(loc.pathname) !== "admin") landing = { path: loc.pathname, search: loc.search };
  store();
  if (!subscribed) {
    subscribed = true;
    onConsentChange(store);
  }
};

/** The checkout's `attribution`, or undefined before the shopper made a cookie choice. */
export const attributionForCheckout = (now = Date.now()): CheckoutAttribution | undefined => {
  const choice = currentChoice();
  if (!choice) return undefined;
  const saved = choice.marketing || choice.analytics ? read() : {};
  const out: CheckoutAttribution = { consent: { analytics: choice.analytics, marketing: choice.marketing, version: CONSENT_VERSION } };
  if (choice.marketing || choice.analytics) out.landingPath = saved.landingPath;
  if (choice.marketing) {
    MARKETING_FIELDS.forEach((k) => { out[k] = saved[k]; });
    out.fbp = cap(cookie("_fbp"), 512);
    out.fbc = cap(cookie("_fbc"), 512) ?? (saved.fbclid ? `fb.1.${now}.${saved.fbclid}` : undefined);
  }
  if (choice.analytics) out.gaClientId = /^GA\d\.\d\.(\d+\.\d+)$/.exec(cookie("_ga") ?? "")?.[1];
  return defined(out);
};
