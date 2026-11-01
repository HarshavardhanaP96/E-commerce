/**
 * Single gate for optional (non-essential) browser storage and third-party scripts (compliance-consent.md §5).
 * The shopper's banner choice is kept in localStorage under "consent" with the cookie-policy version and the
 * time it was made; it expires after six months or when CONSENT_VERSION changes, and the banner asks again.
 * Load optional scripts only when hasConsent(category) is true, never straight from index.html.
 *
 * shortcut: the choice stays in this browser only (also for signed-in shoppers); send it to POST /consent
 * as cookies.* purposes once the backend accepts them (compliance-consent.md §6).
 */
export type ConsentCategory = "functional" | "analytics" | "marketing";
export type ConsentChoice = Record<ConsentCategory, boolean>;
type Stored = ConsentChoice & { version: string; at: string };

/** Bump together with the Cookie & Storage Notice's `updated` date: every shopper is asked again. */
export const CONSENT_VERSION = "2026-10-31";
export const CONSENT_KEY = "consent";
export const COOKIE_SETTINGS_EVENT = "cookie-settings:open";
export const CATEGORIES: ConsentCategory[] = ["functional", "analytics", "marketing"];
export const NONE: ConsentChoice = { functional: false, analytics: false, marketing: false };
export const ALL: ConsentChoice = { functional: true, analytics: true, marketing: true };

const MAX_AGE_MS = 183 * 24 * 60 * 60 * 1000; // six months

/** Cookies the tags set, by the category that allows them (Google Analytics, Google Ads, Meta Pixel). */
const COOKIES: Partial<Record<ConsentCategory, RegExp>> = {
  analytics: /^(_ga|_ga_.+|_gid|_gat.*)$/,
  marketing: /^(_gcl_.+|_fbp|_fbc)$/,
};

// Set only when storage refused the write (private mode, site data off): the choice then lasts for this page load.
let memory: Stored | null = null;
const listeners = new Set<(choice: ConsentChoice) => void>();

const isStored = (v: unknown): v is Stored => {
  const s = v as Stored | null;
  return !!s && typeof s === "object" && typeof s.version === "string" && typeof s.at === "string" &&
    CATEGORIES.every((c) => typeof s[c] === "boolean");
};

/** The shopper's unexpired choice for the current policy version, or null when the banner must ask. */
export const currentChoice = (now = Date.now()): ConsentChoice | null => {
  let stored: unknown;
  try {
    const raw = localStorage.getItem(CONSENT_KEY);
    stored = raw ? JSON.parse(raw) : memory;
  } catch {
    stored = memory; // storage blocked, or a corrupt entry
  }
  if (!isStored(stored) || stored.version !== CONSENT_VERSION) return null;
  if (!(now - Date.parse(stored.at) < MAX_AGE_MS)) return null; // also rejects an unparsable date
  return { functional: stored.functional, analytics: stored.analytics, marketing: stored.marketing };
};

export const hasConsent = (category: ConsentCategory): boolean => currentChoice()?.[category] === true;

/** Deletes the known tag cookies of these categories on this host and its parent domains, as far as possible. */
export const deleteTrackingCookies = (categories: ConsentCategory[]) => {
  const labels = window.location.hostname.split(".");
  const domains = [undefined, ...labels.slice(0, -1).map((_, i) => labels.slice(i).join("."))];
  const names = document.cookie.split(";").map((c) => c.split("=")[0].trim()).filter(Boolean);
  for (const name of names) {
    if (!categories.some((c) => COOKIES[c]?.test(name))) continue;
    for (const domain of domains) {
      document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/${domain ? `; domain=.${domain}` : ""}`;
    }
  }
};

/** Records the shopper's choice, removes cookies of every category now off, and tells subscribers. */
export const saveConsent = (choice: ConsentChoice, now = new Date()) => {
  const clean: ConsentChoice = { functional: choice.functional, analytics: choice.analytics, marketing: choice.marketing };
  const stored: Stored = { ...clean, version: CONSENT_VERSION, at: now.toISOString() };
  try {
    localStorage.setItem(CONSENT_KEY, JSON.stringify(stored));
    memory = null;
  } catch {
    memory = stored; // storage blocked: kept for this visit
  }
  deleteTrackingCookies(CATEGORIES.filter((c) => !clean[c]));
  listeners.forEach((cb) => cb(clean));
};

/** Calls `cb` with every new choice. Returns the unsubscribe function. */
export const onConsentChange = (cb: (choice: ConsentChoice) => void) => {
  listeners.add(cb);
  return () => {
    listeners.delete(cb);
  };
};

/** Reopens the cookie banner's settings (footer "Cookie settings"). */
export const openCookieSettings = () => window.dispatchEvent(new Event(COOKIE_SETTINGS_EVENT));
