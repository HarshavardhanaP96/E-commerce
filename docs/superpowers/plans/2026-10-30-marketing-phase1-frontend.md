# Marketing Phase 0 + 1 — Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shoppers get an opt-in cookie banner with a real `hasConsent`; consenting shoppers' browsers load the store's own GA4, Google Ads, GTM and Meta Pixel tags and send view item, add to cart, begin checkout and purchase; store owners paste those tag IDs on a new Admin → Marketing page.

**Architecture:** Three small modules carry the logic, each unit-tested: `utils/consent.ts` (choice in `localStorage`, expiry, version, subscribers, cookie deletion), `utils/tracking.ts` (tag loader with Consent Mode v2 and the four shop events) and `pages/admin/apps/marketingForm.ts` (provider fields and the server's ID patterns). React only wires them in: `CookieBanner` (bottom bar), one-line `track(...)` calls on the shop pages, `configureTracking` fed by the existing `GET /tenant/config`, and an RTK Query slice whose mutations patch the cached list instead of refetching.

**Tech Stack:** React 18.3 + TypeScript 5 + RTK Query 2.11 + React Router 6, SCSS on the ADR-013 tokens, Vitest 3.2 with jsdom 25 (no Testing Library — a 60-line helper using `react-dom/client` + `act`).

**Spec:** `docs/superpowers/specs/2026-10-30-marketing-integrations-design.md` (§4.3, §5.1, §6, §7, §8). **Master plan:** `docs/superpowers/plans/2026-10-30-marketing-integrations.md` — this file details its Tasks 0.2, 1.4 and 1.5. **Consent rules:** `docs/foundations/compliance-consent.md` §3 (C6, C14), §4, §5, §6.

## Global Constraints

- Work in the worktree `/home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing`. Before Task 1 create the phase sub-branch from `feat/marketing`: `git switch -c feat/marketing--phase1-frontend` (master plan: one sub-branch per phase, merged back into `feat/marketing` by PR). Never commit on `dev`/`main`. Commit after each task; do not push.
- All commands run from `ecommerce-frontend/`. If `node_modules` is missing run `npm ci` first. One test file: `npm test -- <path>`; everything: `npm test`; `npm run lint` (ESLint, `--max-warnings 0`); `npm run build` (`tsc` + Vite).
- **Do not edit `docs/foundations/*` or the spec** — other work owns them. This plan touches only `ecommerce-frontend/`.
- **No new dependencies.** There is no Testing Library and no Playwright package; use `src/test/dom.tsx` (Task 2) for component tests.
- `react-refresh/only-export-components` is on with `--max-warnings 0`: a `.tsx` file that exports a component exports nothing else. Pure helpers go in `.ts` files.
- Backend contract is fixed (do not change shapes): feature key `marketing`; `GET/PUT/DELETE /api/v1/admin/marketing[/{provider}]` as in the header of Task 7; tenant config `marketing: { tags: { ga4, adsId, adsPurchaseLabel, gtm, metaPixel } }` only when configured.
- ID patterns (same as the server): GA4 `^G-[A-Z0-9]{4,12}$`, Ads `^AW-\d{6,12}$`, label `^[A-Za-z0-9_-]{1,40}$`, GTM `^GTM-[A-Z0-9]{4,10}$`, Meta pixel `^\d{10,20}$`. They live once, in `TAG_PATTERNS` (`utils/tracking.ts`).
- Money: event values in the order's/store's currency (`regional.currency` or the checkout's `currency`), never a hard-coded INR.
- Every storage access (`localStorage`, `sessionStorage`) is wrapped in `try/catch`.
- Styling only with existing tokens and mixins (ADR-013): `button(...)`, `text(...)`, `flex(...)`, `$spacing-*`, `$color-*`. Reuse `.card`, `.form-grid`, `.store-settings`, `.badge`, `.field-error`, `.checkbox`, `.sr-only`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **No consent, no tracking.** With no choice or "Reject all", no `<script>` is added, `window.dataLayer`/`window.fbq` stay undefined and `track()` returns early. Pinned by `tracking.test.ts` → "without consent" and `consent.test.ts` → "is false for every optional category before the shopper chooses".
2. **Category split.** Analytics → GA4 only; Marketing → Google Ads, GTM, Meta Pixel only. Pinned by `tracking.test.ts` → "loading by category".
3. **Consent Mode v2 order.** `consent default` (all denied) is the first `gtag()` call, `consent update` the second, `config` after both. Pinned by `tracking.test.ts` → "Consent Mode v2".
4. **Script injection.** Only IDs matching `TAG_PATTERNS` reach a script URL; hosts are constants. Pinned by `tracking.test.ts` → "ID validation".
5. **Double counting.** Purchase is sent once per order id, also after a reload, with `transaction_id` = Meta `eventID` = order `_id` (the server will reuse it in phase 3). Pinned by `tracking.test.ts` → "sends a purchase only once per order…".
6. **Withdrawal.** After a category is switched off no further event is sent, Google tags get `ga-disable-<ID>`, Meta gets `consent revoke`, and `_ga`, `_ga_*`, `_gcl_*`, `_fbp`, `_fbc` are deleted on this host and its parent domains. Pinned by `tracking.test.ts` → "withdrawal and admin pages" and `consent.test.ts` → "deletes the analytics and marketing cookies…".
7. **Admin pages stay clean.** Nothing loads on `/admin/*`; tags loaded earlier in the tab are paused there. Pinned by `tracking.test.ts` → "loads nothing…on admin pages" and "pauses the loaded tags…".
8. **Call budget.** Marketing page first load = exactly 1 request; Save = 1 `PUT`; Disconnect = 1 `DELETE`; no refetch. Pinned by `marketing.test.tsx` (counts every `fetch`). Storefront gains no request (tags ride on `/tenant/config`).
9. **Dark patterns.** Reject all / Customise / Accept all share one style; no box is pre-ticked; no overlay. Pinned by `CookieBanner.test.tsx`.

## Changes from the spec and contract (decided while planning — flag to the caller)

- **Purchase fires in `pages/shipping.tsx`, right after `confirmOrder` succeeds, not on an "order-success page".** The app has no success page: checkout navigates to `/order/:id`, the general order-details page that also shows past orders, so firing there would count old orders. The once-per-order guard (`sessionStorage`) is kept anyway. Value = `checkout.total`, currency = `checkout.currency` from the checkout response.
- **Nav placement:** the sidebar has no "Apps" group (one "Manage" list); Marketing goes right after Coupons. The route follows the existing app prefix, `/admin/app/marketing` (singular), while the file is `pages/admin/apps/marketing.tsx` beside `coupon.tsx`.
- **Feature visibility:** the existing `featureOn()` treats an unknown feature as **on**. Marketing uses a new `adminPageVisible()` (in `utils/routes.ts`) that treats unknown as **off**, so a failed config load never shows a page whose API returns 404. The route is registered only when the switch is on (otherwise `*` redirects to `/`).
- **CSP hosts are wider than spec §8**, following Google's published CSP list for GA4 + Ads and Meta's pixel endpoints: `script-src` + `https://*.googletagmanager.com https://www.googleadservices.com https://googleads.g.doubleclick.net https://www.google.com https://connect.facebook.net`; `connect-src` + `https://*.google-analytics.com https://*.analytics.google.com https://*.googletagmanager.com https://*.g.doubleclick.net https://*.google.com https://pagead2.googlesyndication.com https://www.facebook.com https://connect.facebook.net`; `frame-src` + `https://td.doubleclick.net https://www.googletagmanager.com`. Still no `'unsafe-inline'`/`'unsafe-eval'` for scripts.
- **No Playwright request-count test.** No e2e suite exists (`docs/foundations/test-rollout.md`: "E2E with Playwright … is TBD") and adding `@playwright/test` is a new dependency. The call budget is pinned in Vitest by counting every `fetch` through a real RTK Query store (Task 8), and checked once by hand in the browser pass (Task 10).
- **Meta's automatic SPA page views are switched off** (`fbq.disablePushState = true`) so admin URLs never reach Meta; `routeChanged()` (called from `App`'s `SurfaceScope` on every navigation) sends `PageView` on store pages itself and sets gtag's `ga-disable-<ID>` while an admin page is open.
- **Server 400 message:** `"<field>: <reason>"` is shown under that field; an optional `browser.` prefix on the field is accepted too (Spring often reports nested paths that way). Any other message is shown at the top of the card.
- **`updatedAt` is typed `string | null`** (a never-saved card has no time).
- **Privacy Policy text changes** (new recipients: Google and Meta, only with consent). That is a material change under compliance-consent §6; bumping the backend's `LEGAL_PRIVACY_VERSION` (which re-prompts every signed-in user) is a backend/compliance decision **not made in this plan**. The cookie banner itself re-asks because `CONSENT_VERSION` = the Cookie notice's new `updated` date.
- **Signed-in consent sync is deferred** (contract): the choice stays in `localStorage`; a `shortcut:` comment in `consent.ts` marks where `POST /consent` with `cookies.*` purposes goes later.

## File map

| File | Responsibility | Task |
|---|---|---|
| `src/utils/consent.ts` (+ `consent.test.ts`) | choice storage, 6-month/version expiry, `hasConsent`, `onConsentChange`, cookie deletion, `openCookieSettings` | 1 |
| `src/test/dom.tsx` | render/act/click/type helpers for jsdom component tests | 2 |
| `src/components/CookieBanner.tsx` (+ `.test.tsx`) | bottom-bar banner, Customise toggles, reopen from footer | 2 |
| `src/components/footer.tsx`, `src/App.tsx`, `src/styles/_legal.scss`, `src/styles/_invoice.scss` | "Cookie settings" link, banner mount, banner styles, hidden in print | 2 |
| `src/legal/policies.ts` (+ `policies.test.ts`) | Cookie notice and Privacy Policy text | 3 |
| `src/utils/tracking.ts` (+ `tracking.test.ts`) | tag loader, Consent Mode v2, shop events, purchase guard, admin pause | 4 |
| `src/tenant/tenant.ts` (+ `tenant.test.ts`), `src/App.tsx`, `src/pages/{product,home,search,cart,shipping}.tsx` | feed tags from tenant config; fire events | 5 |
| `nginx.conf.template`, `src/utils/csp.test.ts` | both CSP lines allow the tag hosts | 6 |
| `src/redux/api/marketingAPI.ts`, `src/redux/store.ts`, `src/pages/admin/apps/marketingForm.ts` (+ `.test.ts`) | admin API slice; provider fields, validation, payload | 7 |
| `src/pages/admin/apps/marketing.tsx` (+ `.test.tsx`), `src/styles/admin-styles/_admin-ui.scss` | the admin page | 8 |
| `src/utils/routes.ts` (+ `routes.test.ts`), `src/components/admin/AdminSidebar.tsx`, `src/App.tsx`, `src/help/adminGuides.ts` (+ `.test.ts`) | feature-gated nav and route; help article | 9 |

---

### Task 0: Baseline

**Files:** none.

- [ ] **Step 1: Branch and install**

```bash
cd /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing
git status --short          # expect clean, branch feat/marketing
git switch -c feat/marketing--phase1-frontend
cd ecommerce-frontend
[ -d node_modules ] || npm ci
```

- [ ] **Step 2: Record the baseline**

Run: `npm test && npm run lint && npm run build`
Expected: `Test Files  18 passed (18)`, `Tests  93 passed (93)`; lint prints nothing; build ends `✓ built in …` (Sass `@import` deprecation warnings are pre-existing). If anything fails here, stop and report — it is not caused by this plan.

---

### Task 1: Consent store — real `hasConsent`, expiry, subscribers, cookie deletion

**Files:**
- Modify (rewrite): `ecommerce-frontend/src/utils/consent.ts`
- Test: `ecommerce-frontend/src/utils/consent.test.ts` (new)

**Interfaces:**
- Consumes: nothing.
- Produces: `ConsentCategory`, `ConsentChoice`, `CONSENT_VERSION = "2026-10-30"`, `CONSENT_KEY = "consent"`, `COOKIE_SETTINGS_EVENT`, `CATEGORIES`, `NONE`, `ALL`, `currentChoice(now?)`, `hasConsent(category)`, `deleteTrackingCookies(categories)`, `saveConsent(choice, now?)`, `onConsentChange(cb) → unsubscribe`, `openCookieSettings()`.

- [ ] **Step 1: Write the failing test**

`ecommerce-frontend/src/utils/consent.test.ts`:

```ts
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/utils/consent.test.ts`
Expected: `Tests  10 failed (10)` with `TypeError: consent.currentChoice is not a function` / `consent.saveConsent is not a function` (today's `consent.ts` only exports an always-false `hasConsent`).

- [ ] **Step 3: Replace `consent.ts`**

`ecommerce-frontend/src/utils/consent.ts` (whole file):

```ts
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
export const CONSENT_VERSION = "2026-10-30";
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
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/utils/consent.test.ts`
Expected: `Tests  10 passed (10)`.

- [ ] **Step 5: Lint and commit**

Run: `npm run lint` (expect no output), then:

```bash
git add src/utils/consent.ts src/utils/consent.test.ts
git commit -m "feat(consent): store the cookie choice with expiry and version, notify subscribers

hasConsent() now reads the shopper's choice (localStorage, 6 months, re-asked when
CONSENT_VERSION changes). Switching a category off deletes the GA, Ads and Meta cookies.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Cookie banner, footer "Cookie settings", test helper

**Files:**
- Create: `ecommerce-frontend/src/test/dom.tsx`, `ecommerce-frontend/src/components/CookieBanner.tsx`
- Test: `ecommerce-frontend/src/components/CookieBanner.test.tsx`
- Modify: `ecommerce-frontend/src/components/footer.tsx`, `ecommerce-frontend/src/App.tsx`, `ecommerce-frontend/src/styles/_legal.scss`, `ecommerce-frontend/src/styles/_invoice.scss`

**Interfaces:**
- Consumes: Task 1's `currentChoice`, `saveConsent`, `ALL`, `NONE`, `COOKIE_SETTINGS_EVENT`, `openCookieSettings`; existing `surfaceFor` (`utils/routes.ts`), `brand` (`tenant/tenant.ts`), `policyPath` (`legal/policies.ts`).
- Produces: default export `CookieBanner`; test helpers `render`, `cleanup`, `settle`, `click`, `button`, `queryButton`, `field`, `type` (used again in Task 8).

- [ ] **Step 1: Add the test helper**

jsdom has no Testing Library here and none may be added. `ecommerce-frontend/src/test/dom.tsx`:

```tsx
// Test helpers for rendering components in Vitest's jsdom without extra dependencies.
import { act, ReactNode } from "react";
import { createRoot, Root } from "react-dom/client";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let root: Root | null = null;
let host: HTMLElement | null = null;

/** Renders `ui` into a fresh element in <body>. Call cleanup() after each test. */
export const render = async (ui: ReactNode) => {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(ui));
  return host;
};

export const cleanup = () => {
  act(() => root?.unmount());
  host?.remove();
  root = null;
  host = null;
};

/** Lets pending fetches, timers and state updates finish. */
export const settle = () => act(async () => {
  await new Promise((r) => setTimeout(r, 0));
});

export const click = (el: Element) => act(async () => {
  (el as HTMLElement).click();
});

/** The button whose visible text is exactly `name`. Throws when there is none. */
export const button = (name: string, scope: ParentNode = document) => {
  const found = [...scope.querySelectorAll("button")].find((b) => b.textContent?.trim() === name);
  if (!found) throw new Error(`No button "${name}"`);
  return found;
};

export const queryButton = (name: string, scope: ParentNode = document) =>
  [...scope.querySelectorAll("button")].find((b) => b.textContent?.trim() === name) ?? null;

/** The input inside the <label> whose text starts with `text`. Throws when there is none. */
export const field = (text: string, scope: ParentNode = document) => {
  const label = [...scope.querySelectorAll("label")].find((l) => l.textContent?.trim().startsWith(text));
  const input = label?.querySelector("input");
  if (!input) throw new Error(`No field "${text}"`);
  return input;
};

const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;

/** Types into a controlled input the way React listens for it. */
export const type = (input: HTMLInputElement, value: string) => act(async () => {
  setValue.call(input, value);
  input.dispatchEvent(new Event("input", { bubbles: true }));
});
```

- [ ] **Step 2: Write the failing banner test**

`ecommerce-frontend/src/components/CookieBanner.test.tsx`:

```tsx
// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";
import CookieBanner from "./CookieBanner";
import Footer from "./footer";
import { ALL, NONE, currentChoice, saveConsent } from "../utils/consent";
import { button, cleanup, click, field, queryButton, render } from "../test/dom";

const show = (path = "/") =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Footer />
      <CookieBanner />
    </MemoryRouter>,
  );

const banner = () => document.querySelector(".cookie-banner");

beforeEach(() => localStorage.clear());
afterEach(cleanup);

describe("CookieBanner", () => {
  it("shows a bottom bar with three equal choices on the first visit, without blocking the page", async () => {
    await show();
    expect(banner()).not.toBeNull();
    expect(document.querySelector("dialog")).toBeNull();
    const buttons = [...banner()!.querySelectorAll(".cookie-banner__actions button")];
    expect(buttons.map((b) => b.textContent)).toEqual(["Reject all", "Customise", "Accept all"]);
    expect(new Set(buttons.map((b) => b.className)).size).toBe(1); // same weight: no highlighted "Accept"
  });

  it("Accept all grants every category and closes", async () => {
    await show();
    await click(button("Accept all"));
    expect(currentChoice()).toEqual(ALL);
    expect(banner()).toBeNull();
  });

  it("Reject all records the refusal and closes", async () => {
    await show();
    await click(button("Reject all"));
    expect(currentChoice()).toEqual(NONE);
    expect(banner()).toBeNull();
  });

  it("Customise starts with nothing ticked and saves only what was ticked", async () => {
    await show();
    await click(button("Customise"));
    expect(field("Strictly necessary").disabled).toBe(true);
    for (const name of ["Functional", "Analytics", "Marketing"]) expect(field(name).checked).toBe(false);
    await click(field("Analytics"));
    await click(button("Save choices"));
    expect(currentChoice()).toEqual({ functional: false, analytics: true, marketing: false });
  });

  it("stays away once chosen, and the footer's Cookie settings reopens it with the current choice", async () => {
    saveConsent({ functional: false, analytics: false, marketing: true });
    await show();
    expect(banner()).toBeNull();
    await click(button("Cookie settings"));
    expect(banner()).not.toBeNull();
    expect(field("Marketing").checked).toBe(true);
    expect(field("Analytics").checked).toBe(false);
    expect(document.activeElement?.id).toBe("cookie-banner-title");
    await click(field("Marketing"));
    await click(button("Save choices"));
    expect(currentChoice()?.marketing).toBe(false);
  });

  it("is not shown in the admin area", async () => {
    await show("/admin/analytics");
    expect(banner()).toBeNull();
    expect(queryButton("Accept all")).toBeNull();
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npm test -- src/components/CookieBanner.test.tsx`
Expected: FAIL — `Failed to resolve import "./CookieBanner" from "src/components/CookieBanner.test.tsx"`.

- [ ] **Step 4: Write the banner**

`ecommerce-frontend/src/components/CookieBanner.tsx`:

```tsx
import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { policyPath } from "../legal/policies";
import { brand } from "../tenant/tenant";
import {
  ALL,
  COOKIE_SETTINGS_EVENT,
  ConsentCategory,
  ConsentChoice,
  NONE,
  currentChoice,
  saveConsent,
} from "../utils/consent";
import { surfaceFor } from "../utils/routes";

const OPTIONS: { key: ConsentCategory; label: string; hint: string }[] = [
  { key: "functional", label: "Functional", hint: "remembers preferences such as display settings." },
  { key: "analytics", label: "Analytics", hint: "counts visits and what is viewed so we can improve the shop (Google Analytics)." },
  { key: "marketing", label: "Marketing", hint: "measures our ads with Google and Meta, who may use it to show you our ads elsewhere." },
];

/**
 * Opt-in cookie banner (compliance-consent.md §5, C6): a bottom bar with no overlay, so the shop stays usable.
 * Reject all, Customise and Accept all carry equal weight; nothing is ticked by default.
 */
const CookieBanner = () => {
  const { pathname } = useLocation();
  const [open, setOpen] = useState(() => currentChoice() === null);
  const [customising, setCustomising] = useState(false);
  const [draft, setDraft] = useState<ConsentChoice>(() => currentChoice() ?? NONE);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusOnOpen = useRef(false);

  useEffect(() => {
    const reopen = () => {
      setDraft(currentChoice() ?? NONE);
      setCustomising(true);
      setOpen(true);
      focusOnOpen.current = true;
    };
    window.addEventListener(COOKIE_SETTINGS_EVENT, reopen);
    return () => window.removeEventListener(COOKIE_SETTINGS_EVENT, reopen);
  }, []);

  // Reopened from the footer: move focus here so keyboard and screen-reader users land on it.
  useEffect(() => {
    if (open && focusOnOpen.current) {
      focusOnOpen.current = false;
      headingRef.current?.focus();
    }
  }, [open, customising]);

  if (!open || surfaceFor(pathname) === "admin") return null;

  const decide = (choice: ConsentChoice) => {
    saveConsent(choice);
    setOpen(false);
    setCustomising(false);
  };

  return (
    <section className="cookie-banner" aria-labelledby="cookie-banner-title">
      <h2 id="cookie-banner-title" ref={headingRef} tabIndex={-1}>Cookies on {brand.name}</h2>
      <p>
        We store what the shop needs to work. With your permission we&apos;d also use analytics and marketing
        cookies. You can change your choice at any time under Cookie settings at the bottom of every page.{" "}
        <Link to={policyPath("cookies")}>Cookie notice</Link>
      </p>

      {customising && (
        <fieldset className="cookie-banner__options">
          <legend className="sr-only">Cookie categories</legend>
          <label className="checkbox">
            <input type="checkbox" checked disabled />
            <span><strong>Strictly necessary</strong>: sign-in, cart and this choice. Always on.</span>
          </label>
          {OPTIONS.map(({ key, label, hint }) => (
            <label key={key} className="checkbox">
              <input
                type="checkbox"
                checked={draft[key]}
                onChange={(e) => setDraft((d) => ({ ...d, [key]: e.target.checked }))}
              />
              <span><strong>{label}</strong>: {hint}</span>
            </label>
          ))}
        </fieldset>
      )}

      <div className="cookie-banner__actions">
        <button type="button" onClick={() => decide(NONE)}>Reject all</button>
        {customising ? (
          <button type="button" onClick={() => decide(draft)}>Save choices</button>
        ) : (
          <button type="button" onClick={() => setCustomising(true)}>Customise</button>
        )}
        <button type="button" onClick={() => decide(ALL)}>Accept all</button>
      </div>
    </section>
  );
};

export default CookieBanner;
```

- [ ] **Step 5: Run it — only the footer case still fails**

Run: `npm test -- src/components/CookieBanner.test.tsx`
Expected: `Tests  1 failed | 5 passed (6)`; the failure is `Error: No button "Cookie settings"`.

- [ ] **Step 6: Footer link**

In `ecommerce-frontend/src/components/footer.tsx`, add the import under the policies import:

```tsx
import { openCookieSettings } from "../utils/consent";
```

and inside `<nav aria-label="Policies">`, after the `LEGAL.map(...)` block and before `</nav>`:

```tsx
          <button type="button" className="footer__link" onClick={openCookieSettings}>
            Cookie settings
          </button>
```

- [ ] **Step 7: Run it to verify it passes**

Run: `npm test -- src/components/CookieBanner.test.tsx`
Expected: `Tests  6 passed (6)`.

- [ ] **Step 8: Mount the banner in the app**

In `ecommerce-frontend/src/App.tsx` add the import after `import ConsentPrompt from "./components/consentPrompt";`:

```tsx
import CookieBanner from "./components/CookieBanner";
```

and render it between the consent prompt and the footer:

```tsx
      {user && <ConsentPrompt key={user._id} userId={user._id} />}
      <CookieBanner />
      <Footer />
```

- [ ] **Step 9: Styles**

In `ecommerce-frontend/src/styles/_legal.scss`, inside `.footer { &__groups { … } }` replace the block

```scss
    a,
    span {
      @include text(body-sm);
      color: $color-text-secondary;
    }

    a:hover {
      color: $color-text-primary;
      text-decoration: underline;
    }
```

with

```scss
    a,
    span,
    .footer__link {
      @include text(body-sm);
      color: $color-text-secondary;
    }

    .footer__link {
      padding: 0;
      border: 0;
      background: none;
      cursor: pointer;
      @include focus-ring;
    }

    a:hover,
    .footer__link:hover {
      color: $color-text-primary;
      text-decoration: underline;
    }
```

and insert directly above the `.consent-prompt {` rule:

```scss
// Cookie banner (components/CookieBanner.tsx): a bottom bar above the mobile tab bar, no backdrop.
.cookie-banner {
  position: fixed;
  right: 0;
  bottom: calc(var(--bottom-nav-h, 0px) + env(safe-area-inset-bottom));
  left: 0;
  z-index: 90; // under the header and tab bar (100+), above page content
  @include flex(column, flex-start, stretch, $spacing-sm);
  max-height: 70vh;
  overflow-y: auto;
  padding: $spacing-md var(--gutter);
  border-top: 1px solid $color-border;
  background: $color-bg-secondary;
  color: $color-text-primary;
  box-shadow: $shadow-lg;

  h2 {
    @include text(h3);
    outline: none;
  }

  > p {
    @include text(body-sm);
    color: $color-text-secondary;
    max-width: 70ch;

    a {
      font-weight: 600;
      text-decoration: underline;
    }
  }

  &__options {
    @include flex(column, flex-start, stretch, $spacing-sm);
    border: 0;
    padding: 0;
    margin: 0;
  }

  &__actions {
    @include flex(row, flex-end, center, $spacing-sm);
    flex-wrap: wrap;

    // Equal weight on purpose (dark-pattern guidelines): Reject is as visible as Accept.
    > button {
      @include button(secondary);
    }
  }
}
```

In `ecommerce-frontend/src/styles/_invoice.scss`, in the `@media print` list, add `.cookie-banner,` after `.consent-prompt,`.

- [ ] **Step 10: Lint, build, commit**

Run: `npm run lint && npm run build` (expect success), then:

```bash
git add src/test/dom.tsx src/components/CookieBanner.tsx src/components/CookieBanner.test.tsx \
  src/components/footer.tsx src/App.tsx src/styles/_legal.scss src/styles/_invoice.scss
git commit -m "feat(consent): opt-in cookie banner with Cookie settings in the footer

Bottom bar without overlay; Reject all, Customise and Accept all carry equal weight and
nothing is pre-ticked (compliance-consent §5, C6/C14). Hidden on admin pages and in print.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Cookie notice and Privacy Policy text

**Files:**
- Modify: `ecommerce-frontend/src/legal/policies.ts`
- Test: `ecommerce-frontend/src/legal/policies.test.ts`

**Interfaces:**
- Consumes: Task 1's `CONSENT_VERSION`.
- Produces: `POLICIES.cookies.updated === "2026-10-30"`, `POLICIES.privacy.updated === "2026-10-30"`.

- [ ] **Step 1: Write the failing test**

In `ecommerce-frontend/src/legal/policies.test.ts` replace the import line with

```ts
import { BUSINESS, POLICIES, isFilledIn } from "./policies";
import { CONSENT_VERSION } from "../utils/consent";
```

and append:

```ts
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
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/legal/policies.test.ts`
Expected: `Tests  2 failed | 2 passed (4)` — `expected '2026-10-30' to be '2026-10-16'` and `expected '…' to contain 'Google'`.

- [ ] **Step 3: Update the text**

In `ecommerce-frontend/src/legal/policies.ts`:

1. In `privacy`, change `updated: "2026-10-16"` to `updated: "2026-10-30"`.
2. In `privacy` → "Who we share it with", insert as the second entry of `body` (after the Razorpay/courier/hosting line):

```ts
          "Advertising and analytics partners (Google and Meta), only if you allow analytics or marketing cookies and this shop uses their tools: the pages and products you view, what you add to your cart and your purchases (order number, amount and items), with an identifier stored in your browser. We don't send them your name, email, phone or address.",
```

3. In `privacy` → "Your rights", replace the "Withdraw consent: …" line with:

```ts
          "Withdraw consent: turn marketing emails off at any time, and change your cookie choice under Cookie settings at the bottom of every page. Withdrawing consent doesn't affect anything we did before.",
```

4. Replace the whole `cookies: { … },` entry (from `  cookies: {` up to, not including, `  "admin-aup": {`) with:

```ts
  cookies: {
    slug: "cookies",
    title: "Cookie & Storage Notice",
    updated: "2026-10-30",
    intro:
      "We store what the site needs to work. Analytics and marketing cookies are optional: they are used only if you allow them in the cookie banner, and you can change your choice at any time under Cookie settings at the bottom of every page.",
    sections: [
      {
        heading: "What we store in your browser",
        body: [
          "token: keeps you signed in. Deleted when you sign out, and expires after 24 hours.",
          "cart: your cart, coupon and the delivery address you last entered, so they survive a page reload. The address is removed when you sign out.",
          "consent: your cookie choice and when you made it, so we don't ask on every visit. We ask again after 6 months or when this notice changes.",
        ],
      },
      {
        heading: "Optional cookies, only with your consent",
        body: [
          "Functional: remembers preferences such as display settings. Off until you allow it.",
          "Analytics: if this shop uses Google Analytics, Google sets cookies named _ga and _ga_… to count visits and see which products are viewed. Off until you allow it.",
          "Marketing: if this shop advertises with Google Ads or Meta (Facebook, Instagram), their tags set cookies such as _gcl_au, _fbp and _fbc to measure which ads lead to purchases. Google and Meta may also use this to show you the shop's ads elsewhere. Off until you allow it.",
        ],
      },
      {
        heading: "Third parties",
        body: [
          "On the payment step only, Razorpay's checkout loads and may set its own cookies to process the payment and prevent fraud. See Razorpay's privacy policy.",
          "Google's and Meta's tags load only after you allow analytics or marketing cookies. See Google's and Meta's privacy policies.",
          "Fonts are served from our own servers, so loading a page doesn't contact any third party.",
        ],
      },
      {
        heading: "Your control",
        body: [
          "Open Cookie settings at the bottom of any page to change your choice. When you switch a category off, we stop sending events and delete the cookies listed above that this site can reach. Cookies Google or Meta keep on their own sites are cleared in your browser or your Google or Meta account.",
          "You can clear site data in your browser at any time. You'll be signed out and your cart will be emptied.",
        ],
      },
    ],
  },
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/legal/policies.test.ts`
Expected: `Tests  4 passed (4)`.

- [ ] **Step 5: Commit**

```bash
git add src/legal/policies.ts src/legal/policies.test.ts
git commit -m "docs(legal): cookie notice and privacy policy name Google and Meta, consent-only

The banner version follows the Cookie notice date, so changing the notice re-asks everyone.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Tag loader and shop events (`tracking.ts`)

**Files:**
- Create: `ecommerce-frontend/src/utils/tracking.ts`
- Test: `ecommerce-frontend/src/utils/tracking.test.ts`

**Interfaces:**
- Consumes: Task 1's `currentChoice`, `hasConsent`, `onConsentChange`, `NONE`; existing `regional` (`utils/money.ts`), `surfaceFor` (`utils/routes.ts`), `CartItem` (`types/types.ts`).
- Produces: `MarketingTags`, `TAG_PATTERNS`, `ShopEvent`, `TrackItem`, `TrackData`, `configureTracking(tags?)`, `routeChanged()`, `cartLine(cartItem)`, `track(event, data)`; global typings for `window.dataLayer`, `window.gtag`, `window.fbq`, `window._fbq`.

Behaviour in one place (the test pins each line):
- Nothing happens on `/admin/*`, without tags, or without the matching consent.
- Before any Google tag: `dataLayer`, a `gtag()` that pushes `arguments` (gtag.js ignores arrays), `consent default` all four v2 types `denied` + `wait_for_update: 500`, then `consent update` from the stored choice. One `gtag/js` script serves GA4 and Ads (`config` per ID).
- GTM: `{ "gtm.start", event: "gtm.js" }` then `gtm.js?id=`. Meta: the standard `fbq` queue stub, `init`, `PageView`, `fbevents.js`.
- Events go only to tags that are loaded **and** still consented: `gtag("event", name, {…, send_to: [ids]})`, an extra Ads `conversion` with `send_to: "AW-…/label"` for purchase, a GTM `{ecommerce: null}` + `{event, ecommerce}` pair, and `fbq("track", MetaName, params[, { eventID }])`.
- A purchase without `orderId`, or one already sent in this tab (`sessionStorage` `tracked-orders`, last 20), is dropped.

- [ ] **Step 1: Write the failing test**

`ecommerce-frontend/src/utils/tracking.test.ts`:

```ts
// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { MarketingTags, TrackData } from "./tracking";

let consent: typeof import("./consent");
let tracking: typeof import("./tracking");
let money: typeof import("./money");

const TAGS: MarketingTags = {
  ga4: "G-AB12CD3",
  adsId: "AW-123456789",
  adsPurchaseLabel: "AbC-123_x",
  gtm: "GTM-ABC123",
  metaPixel: "123456789012345",
};
const GA4_JS = "https://www.googletagmanager.com/gtag/js?id=G-AB12CD3";
const ADS_JS = "https://www.googletagmanager.com/gtag/js?id=AW-123456789";
const GTM_JS = "https://www.googletagmanager.com/gtm.js?id=GTM-ABC123";
const META_JS = "https://connect.facebook.net/en_US/fbevents.js";
const ITEM = { id: "p1", name: "Tee", price: 499, quantity: 2, variant: "M / Black" };
const ORDER: TrackData = { orderId: "6720a1b2c3d4e5f601234567", value: 1047.82, currency: "USD", items: [ITEM] };

const isArgs = (a: unknown) => Object.prototype.toString.call(a) === "[object Arguments]";
const scripts = () => [...document.head.querySelectorAll("script")].map((s) => s.src);
/** gtag() calls, in order: gtag.js only reads `arguments` objects from the dataLayer. */
const gtagCalls = () => (window.dataLayer ?? []).filter(isArgs).map((a) => Array.from(a as ArrayLike<unknown>));
const gtagEvents = (name: string) => gtagCalls().filter((c) => c[0] === "event" && c[1] === name);
const gtmPushes = () => (window.dataLayer ?? []).filter((a) => !isArgs(a)) as Record<string, unknown>[];
const fbqCalls = () => (window.fbq?.queue ?? []).map((a) => Array.from(a as ArrayLike<unknown>));
const disabled = (id: string) => (window as unknown as Record<string, unknown>)[`ga-disable-${id}`];

const fresh = async () => {
  vi.resetModules(); // tracking keeps what it loaded in module state, like a real page load
  consent = await import("./consent");
  tracking = await import("./tracking");
  money = await import("./money");
};

const allow = (analytics: boolean, marketing: boolean) =>
  consent.saveConsent({ functional: false, analytics, marketing });

beforeEach(async () => {
  localStorage.clear();
  sessionStorage.clear();
  document.head.innerHTML = "";
  delete window.dataLayer;
  delete window.gtag;
  delete window.fbq;
  delete window._fbq;
  window.history.replaceState(null, "", "/");
  await fresh();
});

describe("without consent", () => {
  it("adds no script and track() does nothing", () => {
    tracking.configureTracking(TAGS);
    tracking.track("view_item", { items: [ITEM], value: 499 });
    tracking.track("purchase", ORDER);
    expect(scripts()).toEqual([]);
    expect(window.dataLayer).toBeUndefined();
    expect(window.fbq).toBeUndefined();
  });

  it("does nothing when the store has no tags configured", () => {
    allow(true, true);
    tracking.configureTracking(undefined);
    tracking.track("view_item", { items: [ITEM], value: 499 });
    expect(scripts()).toEqual([]);
    expect(window.dataLayer).toBeUndefined();
  });
});

describe("loading by category", () => {
  it("analytics consent loads GA4 only", () => {
    allow(true, false);
    tracking.configureTracking(TAGS);
    expect(scripts()).toEqual([GA4_JS]);
    expect(gtagCalls()).toContainEqual(["config", "G-AB12CD3"]);
    expect(gtagCalls()).not.toContainEqual(["config", "AW-123456789"]);
    expect(window.fbq).toBeUndefined();
    expect(gtmPushes()).toEqual([]);
  });

  it("marketing consent loads Google Ads, GTM and the Meta Pixel, not GA4", () => {
    allow(false, true);
    tracking.configureTracking(TAGS);
    expect(scripts().sort()).toEqual([ADS_JS, GTM_JS, META_JS].sort());
    expect(gtagCalls()).toContainEqual(["config", "AW-123456789"]);
    expect(gtagCalls()).not.toContainEqual(["config", "G-AB12CD3"]);
    expect(fbqCalls()).toContainEqual(["init", "123456789012345"]);
    expect(fbqCalls()).toContainEqual(["track", "PageView"]);
    expect(gtmPushes()[0]).toMatchObject({ event: "gtm.js" });
  });

  it("loads the tags once consent is given later, without a reload", () => {
    tracking.configureTracking(TAGS);
    expect(scripts()).toEqual([]);
    allow(true, true);
    expect(scripts()).toContain(GA4_JS);
    expect(scripts()).toContain(META_JS);
  });

  it("never adds a second copy when the config is applied again", () => {
    allow(true, true);
    tracking.configureTracking(TAGS);
    tracking.configureTracking(TAGS);
    expect(scripts().filter((s) => s.includes("googletagmanager.com/gtag/js"))).toHaveLength(1);
    expect(scripts().filter((s) => s === META_JS)).toHaveLength(1);
  });
});

describe("Consent Mode v2", () => {
  it("sets every Google consent type to denied before anything else, then updates from the choice", () => {
    allow(true, false);
    tracking.configureTracking(TAGS);
    const calls = gtagCalls();
    expect(calls[0]).toEqual(["consent", "default", {
      ad_storage: "denied", ad_user_data: "denied", ad_personalization: "denied", analytics_storage: "denied",
      wait_for_update: 500,
    }]);
    expect(calls[1]).toEqual(["consent", "update", {
      ad_storage: "denied", ad_user_data: "denied", ad_personalization: "denied", analytics_storage: "granted",
    }]);
    expect(calls.findIndex((c) => c[0] === "config")).toBeGreaterThan(1);
  });

  it("sends an update when the shopper changes their choice", () => {
    allow(true, false);
    tracking.configureTracking(TAGS);
    allow(true, true);
    expect(gtagCalls().filter((c) => c[0] === "consent").at(-1)).toEqual(["consent", "update", {
      ad_storage: "granted", ad_user_data: "granted", ad_personalization: "granted", analytics_storage: "granted",
    }]);
  });
});

describe("shop events", () => {
  it("maps each event to GA4 and Meta names, in the store's currency by default", () => {
    allow(true, true);
    money.regional.currency = "EUR";
    tracking.configureTracking(TAGS);
    tracking.track("view_item", { items: [ITEM], value: 499 });
    tracking.track("add_to_cart", { items: [ITEM], value: 998 });
    tracking.track("begin_checkout", { items: [ITEM], value: 998 });
    const [viewItem] = gtagEvents("view_item");
    expect(viewItem[2]).toMatchObject({
      currency: "EUR", value: 499, send_to: ["G-AB12CD3", "AW-123456789"],
      items: [{ item_id: "p1", item_name: "Tee", item_variant: "M / Black", price: 499, quantity: 2 }],
    });
    expect(gtagEvents("add_to_cart")).toHaveLength(1);
    expect(gtagEvents("begin_checkout")).toHaveLength(1);
    expect(fbqCalls().filter((c) => c[0] === "track").map((c) => c[1]))
      .toEqual(["PageView", "ViewContent", "AddToCart", "InitiateCheckout"]);
    expect(gtmPushes().map((p) => p.event)).toEqual(["gtm.js", undefined, "view_item", undefined, "add_to_cart",
      undefined, "begin_checkout"]);
  });

  it("sends purchase with the order id as transaction and event id, its value and currency", () => {
    allow(true, true);
    tracking.configureTracking(TAGS);
    tracking.track("purchase", ORDER);
    expect(gtagEvents("purchase")[0][2]).toMatchObject({
      transaction_id: ORDER.orderId, value: 1047.82, currency: "USD", send_to: ["G-AB12CD3", "AW-123456789"],
    });
    expect(gtagEvents("conversion")[0][2]).toEqual({
      send_to: "AW-123456789/AbC-123_x", transaction_id: ORDER.orderId, value: 1047.82, currency: "USD",
    });
    expect(fbqCalls().find((c) => c[1] === "Purchase")).toEqual(["track", "Purchase", {
      content_ids: ["p1"], content_type: "product", contents: [{ id: "p1", quantity: 2 }], num_items: 2,
      value: 1047.82, currency: "USD",
    }, { eventID: ORDER.orderId }]);
    expect(gtmPushes().find((p) => p.event === "purchase")).toMatchObject({
      ecommerce: { transaction_id: ORDER.orderId, value: 1047.82, currency: "USD" },
    });
  });

  it("sends a purchase only once per order, also after a page reload", async () => {
    allow(true, true);
    tracking.configureTracking(TAGS);
    tracking.track("purchase", ORDER);
    tracking.track("purchase", ORDER);
    expect(gtagEvents("purchase")).toHaveLength(1);

    document.head.innerHTML = "";
    delete window.dataLayer;
    delete window.fbq;
    await fresh();
    tracking.configureTracking(TAGS);
    tracking.track("purchase", ORDER);
    expect(gtagEvents("purchase")).toHaveLength(0);
    expect(fbqCalls().filter((c) => c[1] === "Purchase")).toHaveLength(0);
  });

  it("ignores a purchase without an order id", () => {
    allow(true, true);
    tracking.configureTracking(TAGS);
    tracking.track("purchase", { ...ORDER, orderId: undefined });
    expect(gtagEvents("purchase")).toHaveLength(0);
  });
});

describe("ID validation", () => {
  it("builds no script and no call from an ID that is not the expected shape", () => {
    allow(true, true);
    tracking.configureTracking({
      ga4: 'G-AB12"></script><script>alert(1)</script>',
      adsId: "AW-12",
      adsPurchaseLabel: "x y",
      gtm: "gtm-abc123",
      metaPixel: "123456789012345678901",
    });
    tracking.track("purchase", ORDER);
    expect(scripts()).toEqual([]);
    expect(document.documentElement.innerHTML).not.toContain("alert(1)");
    expect(window.fbq).toBeUndefined();
  });

  it("keeps the valid tags when another one is invalid", () => {
    allow(true, true);
    tracking.configureTracking({ ...TAGS, adsId: "AW-<img>", gtm: null, metaPixel: undefined });
    expect(scripts()).toEqual([GA4_JS]);
  });
});

describe("withdrawal and admin pages", () => {
  it("stops every event after consent is withdrawn and switches the Google tags off", () => {
    allow(true, true);
    tracking.configureTracking(TAGS);
    tracking.track("view_item", { items: [ITEM], value: 499 });
    const before = { gtag: gtagEvents("add_to_cart").length, fbq: fbqCalls().length, gtm: gtmPushes().length };

    consent.saveConsent(consent.NONE);
    tracking.track("add_to_cart", { items: [ITEM], value: 998 });
    tracking.track("purchase", ORDER);

    expect(gtagEvents("add_to_cart")).toHaveLength(before.gtag);
    expect(gtagEvents("purchase")).toHaveLength(0);
    expect(fbqCalls().filter((c) => c[0] === "track")).toHaveLength(2); // PageView + ViewContent from before
    expect(fbqCalls()).toContainEqual(["consent", "revoke"]);
    expect(gtmPushes()).toHaveLength(before.gtm);
    expect(disabled("G-AB12CD3")).toBe(true);
    expect(disabled("AW-123456789")).toBe(true);
    expect(gtagCalls().filter((c) => c[0] === "consent").at(-1)?.[2]).toMatchObject({
      analytics_storage: "denied", ad_storage: "denied",
    });
  });

  it("loads nothing and sends nothing on admin pages", () => {
    window.history.replaceState(null, "", "/admin/analytics");
    allow(true, true);
    tracking.configureTracking(TAGS);
    tracking.track("view_item", { items: [ITEM], value: 499 });
    expect(scripts()).toEqual([]);
    expect(window.dataLayer).toBeUndefined();
  });

  it("pauses the loaded tags while an admin page is open and counts store page views", () => {
    allow(true, true);
    tracking.configureTracking(TAGS);
    tracking.routeChanged(); // first render of "/": the PageView sent at load is not repeated
    const pageViews = () => fbqCalls().filter((c) => c[1] === "PageView").length;
    expect(pageViews()).toBe(1);

    window.history.pushState(null, "", "/admin/analytics");
    tracking.routeChanged();
    expect(disabled("G-AB12CD3")).toBe(true);
    expect(pageViews()).toBe(1);

    window.history.pushState(null, "", "/search");
    tracking.routeChanged();
    expect(disabled("G-AB12CD3")).toBe(false);
    expect(pageViews()).toBe(2);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/utils/tracking.test.ts`
Expected: FAIL — `Failed to resolve import "./tracking" from "src/utils/tracking.test.ts"`.

- [ ] **Step 3: Write `tracking.ts`**

`ecommerce-frontend/src/utils/tracking.ts`:

```ts
import type { CartItem } from "../types/types";
import { ConsentChoice, NONE, currentChoice, hasConsent, onConsentChange } from "./consent";
import { regional } from "./money";
import { surfaceFor } from "./routes";

/**
 * Storefront tag loader and shop events (marketing spec §6, phase 1). The store's public tag IDs come from
 * GET /tenant/config → marketing.tags. GA4 loads only with analytics consent; Google Ads, GTM and the Meta
 * Pixel only with marketing consent. Nothing loads or fires on admin pages. Script URLs are fixed hosts plus
 * an ID re-checked against the server's patterns, because the ID ends up in a <script src>.
 */
export type MarketingTags = {
  ga4?: string | null;
  adsId?: string | null;
  adsPurchaseLabel?: string | null;
  gtm?: string | null;
  metaPixel?: string | null;
};

/** The server's patterns (marketing spec §5.1). The admin form uses the same ones. */
export const TAG_PATTERNS: Record<keyof MarketingTags, RegExp> = {
  ga4: /^G-[A-Z0-9]{4,12}$/,
  adsId: /^AW-\d{6,12}$/,
  adsPurchaseLabel: /^[A-Za-z0-9_-]{1,40}$/,
  gtm: /^GTM-[A-Z0-9]{4,10}$/,
  metaPixel: /^\d{10,20}$/,
};

const GTAG_JS = "https://www.googletagmanager.com/gtag/js?id=";
const GTM_JS = "https://www.googletagmanager.com/gtm.js?id=";
const META_JS = "https://connect.facebook.net/en_US/fbevents.js";

export type ShopEvent = "view_item" | "add_to_cart" | "begin_checkout" | "purchase";
const META_EVENT: Record<ShopEvent, string> = {
  view_item: "ViewContent",
  add_to_cart: "AddToCart",
  begin_checkout: "InitiateCheckout",
  purchase: "Purchase",
};

export type TrackItem = { id: string; name: string; price: number; quantity: number; variant?: string };
/** `currency` defaults to the store's; `orderId` is required for purchase (transaction_id and Meta eventID). */
export type TrackData = { items: TrackItem[]; value: number; currency?: string; orderId?: string };

type Fbq = ((...args: unknown[]) => void) & {
  queue?: unknown[];
  callMethod?: (...args: unknown[]) => void;
  push?: Fbq;
  loaded?: boolean;
  version?: string;
  disablePushState?: boolean;
};

declare global {
  interface Window {
    dataLayer?: unknown[];
    gtag?: (...args: unknown[]) => void;
    fbq?: Fbq;
    _fbq?: Fbq;
  }
}

let tags: MarketingTags = {};
const loaded = new Set<"consent" | "gtag.js" | "ga4" | "ads" | "gtm" | "meta">();
let subscribed = false;
let lastPageView = "";
const SENT_KEY = "tracked-orders";
const sentThisVisit = new Set<string>();

const valid = (key: keyof MarketingTags): string | null => {
  const v = tags[key];
  return typeof v === "string" && TAG_PATTERNS[key].test(v) ? v : null;
};
const onAdminPage = () => surfaceFor(window.location.pathname) === "admin";
const gtag = (...args: unknown[]) => window.gtag?.(...args);

const addScript = (src: string) => {
  const s = document.createElement("script");
  s.async = true;
  s.src = src;
  document.head.appendChild(s);
};

const consentMode = (c: ConsentChoice) => {
  const ads = c.marketing ? "granted" : "denied";
  return { ad_storage: ads, ad_user_data: ads, ad_personalization: ads, analytics_storage: c.analytics ? "granted" : "denied" };
};

/** gtag.js's own off switch per tag ID: on while its category is withheld or an admin page is open. */
const syncGoogleSwitches = () => {
  const w = window as unknown as Record<string, unknown>;
  const admin = onAdminPage();
  const ga4 = valid("ga4");
  const ads = valid("adsId");
  if (ga4) w[`ga-disable-${ga4}`] = admin || !hasConsent("analytics");
  if (ads) w[`ga-disable-${ads}`] = admin || !hasConsent("marketing");
};

/** dataLayer + gtag() with Consent Mode v2 defaults (all denied) before any Google tag, then the shopper's choice. */
const startConsentMode = () => {
  if (loaded.has("consent")) return;
  loaded.add("consent");
  window.dataLayer = window.dataLayer ?? [];
  window.gtag = function gtag() {
    // gtag.js only reads `arguments` objects from the dataLayer, not arrays
    // eslint-disable-next-line prefer-rest-params
    window.dataLayer!.push(arguments);
  };
  gtag("consent", "default", { ...consentMode(NONE), wait_for_update: 500 });
  gtag("consent", "update", consentMode(currentChoice() ?? NONE));
};

const loadGtag = (id: string) => {
  if (loaded.has("gtag.js")) return;
  loaded.add("gtag.js");
  gtag("js", new Date());
  addScript(GTAG_JS + id);
};

const startMeta = (pixel: string) => {
  const fbq: Fbq = (...args) => (fbq.callMethod ? fbq.callMethod(...args) : fbq.queue!.push(args));
  Object.assign(fbq, { push: fbq, loaded: true, version: "2.0", queue: [], disablePushState: true });
  window.fbq = window._fbq = fbq;
  fbq("init", pixel);
  fbq("track", "PageView");
  lastPageView = window.location.pathname;
  addScript(META_JS);
};

/** Loads every configured tag the shopper's consent allows and that is not loaded yet. */
const apply = () => {
  if (onAdminPage()) return;
  const analytics = hasConsent("analytics");
  const marketing = hasConsent("marketing");
  const ga4 = analytics ? valid("ga4") : null;
  const ads = marketing ? valid("adsId") : null;
  const gtm = marketing ? valid("gtm") : null;
  const pixel = marketing ? valid("metaPixel") : null;

  if (ga4 || ads || gtm) startConsentMode();
  if (ga4 && !loaded.has("ga4")) {
    loaded.add("ga4");
    loadGtag(ga4);
    gtag("config", ga4);
  }
  if (ads && !loaded.has("ads")) {
    loaded.add("ads");
    loadGtag(ads);
    gtag("config", ads);
  }
  if (gtm && !loaded.has("gtm")) {
    loaded.add("gtm");
    window.dataLayer!.push({ "gtm.start": Date.now(), event: "gtm.js" });
    addScript(GTM_JS + gtm);
  }
  if (pixel && !loaded.has("meta")) {
    loaded.add("meta");
    startMeta(pixel);
  }
  syncGoogleSwitches();
};

const consentChanged = (choice: ConsentChoice) => {
  if (loaded.has("consent")) gtag("consent", "update", consentMode(choice));
  if (loaded.has("meta")) window.fbq?.("consent", choice.marketing ? "grant" : "revoke");
  syncGoogleSwitches();
  apply();
};

/** Sets the store's tags (null/undefined: none configured) and loads what consent allows. Safe to call again. */
export const configureTracking = (next?: MarketingTags | null) => {
  tags = next ?? {};
  if (!subscribed) {
    subscribed = true;
    onConsentChange(consentChanged);
  }
  apply();
};

/** Call on every SPA navigation: pauses Google tags on admin pages and sends Meta's page view on store pages. */
export const routeChanged = () => {
  syncGoogleSwitches();
  if (onAdminPage()) return;
  apply();
  const path = window.location.pathname;
  if (loaded.has("meta") && hasConsent("marketing") && path !== lastPageView) {
    lastPageView = path;
    window.fbq?.("track", "PageView");
  }
};

/** True the first time an order is seen in this tab, also across reloads (sessionStorage). */
const firstPurchase = (orderId: string) => {
  let sent: string[] = [];
  try {
    const v: unknown = JSON.parse(sessionStorage.getItem(SENT_KEY) ?? "[]");
    if (Array.isArray(v)) sent = v.filter((x): x is string => typeof x === "string");
  } catch {
    // storage blocked: the in-memory set still guards this page
  }
  if (sentThisVisit.has(orderId) || sent.includes(orderId)) return false;
  sentThisVisit.add(orderId);
  try {
    sessionStorage.setItem(SENT_KEY, JSON.stringify([...sent, orderId].slice(-20)));
  } catch {
    // ignore
  }
  return true;
};

/** A cart line as an event item. */
export const cartLine = (i: CartItem): TrackItem => ({
  id: i.productId,
  name: i.name,
  price: i.price,
  quantity: i.quantity,
  variant: i.variantLabel,
});

/** Sends a shop event to every loaded tag the shopper still consents to. No-op otherwise. */
export const track = (event: ShopEvent, data: TrackData) => {
  if (onAdminPage()) return;
  apply();
  const marketing = hasConsent("marketing");
  const ga4 = loaded.has("ga4") && hasConsent("analytics") ? valid("ga4") : null;
  const ads = loaded.has("ads") && marketing ? valid("adsId") : null;
  const gtm = loaded.has("gtm") && marketing;
  const meta = loaded.has("meta") && marketing;
  const sendTo = [ga4, ads].filter((id): id is string => !!id);
  if (!sendTo.length && !gtm && !meta) return;
  if (event === "purchase" && (!data.orderId || !firstPurchase(data.orderId))) return;

  const currency = data.currency ?? regional.currency;
  const orderId = data.orderId ? { transaction_id: data.orderId } : {};
  const ecommerce = {
    ...orderId,
    currency,
    value: data.value,
    items: data.items.map((i) => ({
      item_id: i.id, item_name: i.name, item_variant: i.variant, price: i.price, quantity: i.quantity,
    })),
  };

  if (sendTo.length) gtag("event", event, { ...ecommerce, send_to: sendTo });
  const label = valid("adsPurchaseLabel");
  if (event === "purchase" && ads && label) {
    gtag("event", "conversion", { send_to: `${ads}/${label}`, ...orderId, value: data.value, currency });
  }
  if (gtm) {
    window.dataLayer!.push({ ecommerce: null }); // clears the previous event's ecommerce object (GTM guidance)
    window.dataLayer!.push({ event, ecommerce });
  }
  if (meta) {
    const params = {
      content_ids: data.items.map((i) => i.id),
      content_type: "product",
      contents: data.items.map((i) => ({ id: i.id, quantity: i.quantity })),
      num_items: data.items.reduce((n, i) => n + i.quantity, 0),
      value: data.value,
      currency,
    };
    if (data.orderId) window.fbq!("track", META_EVENT[event], params, { eventID: data.orderId });
    else window.fbq!("track", META_EVENT[event], params);
  }
};
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/utils/tracking.test.ts`
Expected: `Tests  17 passed (17)`.

- [ ] **Step 5: Lint and commit**

Run: `npm run lint` — expect no output (the single `prefer-rest-params` disable is required: gtag.js reads `arguments` objects only).

```bash
git add src/utils/tracking.ts src/utils/tracking.test.ts
git commit -m "feat(marketing): consent-gated tag loader for GA4, Google Ads, GTM and Meta Pixel

Consent Mode v2 defaults to denied before any Google tag; IDs are re-validated before they go
into a script URL; purchase is sent once per order with the order id as transaction/event id.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Feed the tags from tenant config and fire the shop events

**Files:**
- Modify: `ecommerce-frontend/src/tenant/tenant.ts`, `ecommerce-frontend/src/App.tsx`, `ecommerce-frontend/src/pages/product.tsx`, `ecommerce-frontend/src/pages/home.tsx`, `ecommerce-frontend/src/pages/search.tsx`, `ecommerce-frontend/src/pages/cart.tsx`, `ecommerce-frontend/src/pages/shipping.tsx`
- Test: `ecommerce-frontend/src/tenant/tenant.test.ts`

**Interfaces:**
- Consumes: Task 4's `configureTracking`, `routeChanged`, `track`, `cartLine`, `MarketingTags`; existing `ConfirmOrderResponse.orderId`, `Checkout.total`/`currency` (`types/api-types.ts`).
- Produces: `TenantConfig.marketing?: { tags: MarketingTags }`. No new network call: the tags arrive with the existing `GET /api/v1/tenant/config` that `main.tsx` already awaits before the first render.

- [ ] **Step 1: Write the failing test**

In `ecommerce-frontend/src/tenant/tenant.test.ts` replace the three import lines at the top (below `// @vitest-environment jsdom`) with:

```ts
import { beforeEach, describe, expect, it, vi } from "vitest";
import { contrastRatio } from "../utils/contrast";
import { configureTracking } from "../utils/tracking";
import { applyTenantConfig, applyTheme } from "./tenant";

vi.mock("../utils/tracking", () => ({ configureTracking: vi.fn() }));
```

and append:

```ts
describe("applyTenantConfig", () => {
  it("hands the store's marketing tags to the tag loader, and none when the config has no marketing key", () => {
    applyTenantConfig({ slug: "s", name: "Shop", theme: {}, marketing: { tags: { ga4: "G-AB12CD3" } } });
    expect(configureTracking).toHaveBeenLastCalledWith({ ga4: "G-AB12CD3" });
    applyTenantConfig({ slug: "s", name: "Shop", theme: {} });
    expect(configureTracking).toHaveBeenLastCalledWith(undefined);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/tenant/tenant.test.ts`
Expected: `Tests  1 failed | 6 passed (7)` — `expected last "spy" call to have been called with [ { ga4: 'G-AB12CD3' } ]`. (`tsc` would also reject the unknown `marketing` key; Vitest does not type-check.)

- [ ] **Step 3: Wire `tenant.ts`**

In `ecommerce-frontend/src/tenant/tenant.ts`:

- add after `import { readableOn } from "../utils/contrast";`:

```ts
import { MarketingTags, configureTracking } from "../utils/tracking";
```

- add as the last member of `type TenantConfig` (after `timeZone?: string;`):

```ts
  /** Present only when the store's marketing feature is on and it saved at least one tag ID. */
  marketing?: { tags: MarketingTags };
```

- add as the last line of `applyTenantConfig`, after `refreshPolicies(brand.name);`:

```ts
  configureTracking(config.marketing?.tags);
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/tenant/tenant.test.ts`
Expected: `Tests  7 passed (7)`.

- [ ] **Step 5: Page views and admin pause on navigation**

In `ecommerce-frontend/src/App.tsx` add `import { routeChanged } from "./utils/tracking";` after the `surfaceFor` import, and in `SurfaceScope`'s effect add `routeChanged();` after the `dataset.surface` line:

```tsx
  useEffect(() => {
    document.documentElement.dataset.surface = surfaceFor(pathname);
    routeChanged();
  }, [pathname]);
```

- [ ] **Step 6: Shop events**

Add `import { cartLine, track } from "../utils/tracking";` to each of `pages/product.tsx`, `pages/home.tsx`, `pages/search.tsx`, `pages/cart.tsx`, `pages/shipping.tsx` (after the last existing import). Then:

`pages/product.tsx`, in `ProductDetail`, insert above `useEffect(() => setImageIndex(0), [images.length, selection]);`:

```tsx
  useEffect(() => {
    track("view_item", { items: [{ id: product._id, name: product.name, price: product.price, quantity: 1 }], value: product.price });
  }, [product]);
```

and in `addHandler` replace the `dispatch(addToCart({ … }));` call with:

```tsx
    const item = {
      productId: product._id,
      variantId: variant?.id,
      variantLabel: variant?.label,
      photo: images[0] ?? product.photo,
      name: product.name,
      price: line.price,
      quantity: inCart + quantity,
      stock: Math.min(line.stock, MAX_PER_ORDER),
      taxRate: product.taxRate,
    };
    dispatch(addToCart(item));
    track("add_to_cart", { items: [cartLine({ ...item, quantity })], value: line.price * quantity });
```

(the cart line holds the new total; the event counts only what was just added).

`pages/home.tsx` and `pages/search.tsx`, in `addToCartHandler`, after `dispatch(addToCart(cartItem));`:

```tsx
    track("add_to_cart", { items: [cartLine(cartItem)], value: cartItem.price * cartItem.quantity });
```

`pages/cart.tsx`, replace `{cartItems.length > 0 && <Link to="/shipping">Checkout</Link>}` with:

```tsx
        {cartItems.length > 0 && (
          <Link to="/shipping" onClick={() => track("begin_checkout", { items: cartItems.map(cartLine), value: total })}>
            Checkout
          </Link>
        )}
```

`pages/shipping.tsx`, in `submitHandler`, between `}).unwrap();` (the `confirmOrder` call) and `dispatch(resetCart());`:

```tsx
      // Here, not on /order/:id (which also shows past orders); tracking sends each order id once.
      track("purchase", { orderId: res.orderId, value: c.total, currency: c.currency, items: cartItems.map(cartLine) });
```

(`cartItems` is still the pre-reset list in this closure.)

- [ ] **Step 7: Full check and commit**

Run: `npm test && npm run lint && npm run build`
Expected: all green; `Test Files  21 passed (21)`.

```bash
git add src/tenant src/App.tsx src/pages/product.tsx src/pages/home.tsx src/pages/search.tsx src/pages/cart.tsx src/pages/shipping.tsx
git commit -m "feat(marketing): load the store's tags from tenant config and send shop events

view_item on the product page, add_to_cart from product, home and search, begin_checkout from
the cart, purchase after the order is confirmed. No extra API call.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Content-Security-Policy for the tag hosts

**Files:**
- Modify: `ecommerce-frontend/nginx.conf.template` (both `Content-Security-Policy` lines — server block and `location /`)
- Test: `ecommerce-frontend/src/utils/csp.test.ts`

**Interfaces:** none (configuration). nginx drops inherited `add_header`s in a `location` that sets its own, which is why there are two identical lines.

- [ ] **Step 1: Write the failing test**

`ecommerce-frontend/src/utils/csp.test.ts`:

```ts
// The storefront tags (utils/tracking.ts) only run if nginx's Content-Security-Policy allows their hosts.
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const template = readFileSync(new URL("../../nginx.conf.template", import.meta.url), "utf8");
const policies = [...template.matchAll(/Content-Security-Policy "([^"]+)"/g)].map((m) => m[1]);
const directive = (csp: string, name: string) =>
  csp.split(";").map((d) => d.trim().split(/\s+/)).find((d) => d[0] === name)?.slice(1) ?? [];

describe("nginx Content-Security-Policy", () => {
  it("is the same on the server block and on the SPA location (nginx drops inherited headers)", () => {
    expect(policies).toHaveLength(2);
    expect(policies[0]).toBe(policies[1]);
  });

  it.each([
    ["script-src", ["https://*.googletagmanager.com", "https://www.googleadservices.com", "https://googleads.g.doubleclick.net",
      "https://www.google.com", "https://connect.facebook.net", "https://checkout.razorpay.com"]],
    ["connect-src", ["https://*.google-analytics.com", "https://*.analytics.google.com", "https://*.googletagmanager.com",
      "https://*.g.doubleclick.net", "https://*.google.com", "https://pagead2.googlesyndication.com",
      "https://www.facebook.com", "https://connect.facebook.net", "https://api.razorpay.com"]],
    ["frame-src", ["https://td.doubleclick.net", "https://www.googletagmanager.com", "https://checkout.razorpay.com"]],
  ])("%s allows the Google, Meta and Razorpay hosts", (name, hosts) => {
    for (const csp of policies) expect(directive(csp, name)).toEqual(expect.arrayContaining(hosts));
  });

  it("still allows no inline or eval script", () => {
    for (const csp of policies) {
      expect(directive(csp, "script-src")).not.toContain("'unsafe-inline'");
      expect(directive(csp, "script-src")).not.toContain("'unsafe-eval'");
    }
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/utils/csp.test.ts`
Expected: `Tests  3 failed | 2 passed (5)` — the `script-src`, `connect-src` and `frame-src` cases.

- [ ] **Step 3: Update both lines**

In `ecommerce-frontend/nginx.conf.template`, in **both** `add_header Content-Security-Policy "…"` lines (28 and 65), replace

```
default-src 'self'; script-src 'self' https://checkout.razorpay.com; frame-src https://api.razorpay.com https://checkout.razorpay.com; connect-src 'self' https://api.razorpay.com https://lumberjack.razorpay.com;
```

with

```
default-src 'self'; script-src 'self' https://checkout.razorpay.com https://*.googletagmanager.com https://www.googleadservices.com https://googleads.g.doubleclick.net https://www.google.com https://connect.facebook.net; frame-src https://api.razorpay.com https://checkout.razorpay.com https://td.doubleclick.net https://www.googletagmanager.com; connect-src 'self' https://api.razorpay.com https://lumberjack.razorpay.com https://*.google-analytics.com https://*.analytics.google.com https://*.googletagmanager.com https://*.g.doubleclick.net https://*.google.com https://pagead2.googlesyndication.com https://www.facebook.com https://connect.facebook.net;
```

leaving the rest of each line (`img-src … form-action 'self'" always;`) unchanged. Then check: `grep -c "connect.facebook.net" nginx.conf.template` → `2`.

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/utils/csp.test.ts`
Expected: `Tests  5 passed (5)`.

- [ ] **Step 5: Commit**

```bash
git add nginx.conf.template src/utils/csp.test.ts
git commit -m "feat(marketing): allow Google and Meta tag hosts in the CSP

Both policy lines stay identical; scripts still get no unsafe-inline or unsafe-eval.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Admin API slice and form rules

**Files:**
- Create: `ecommerce-frontend/src/redux/api/marketingAPI.ts`, `ecommerce-frontend/src/pages/admin/apps/marketingForm.ts`
- Modify: `ecommerce-frontend/src/redux/store.ts`
- Test: `ecommerce-frontend/src/pages/admin/apps/marketingForm.test.ts`

**Interfaces:**
- Consumes: existing `authedBaseQuery` (`redux/api/baseQuery.ts`); Task 4's `TAG_PATTERNS`.
- Produces:
  - `marketingAPI` (`reducerPath: "marketingApi"`), hooks `useMarketingQuery`, `useSaveIntegrationMutation`, `useDisconnectIntegrationMutation`; types `Provider`, `IntegrationStatus`, `Integration`, `MarketingResponse`, `IntegrationResponse`.
  - Endpoints (fixed contract): `GET /api/v1/admin/marketing` → `{success, integrations:[google, meta]}`; `PUT /api/v1/admin/marketing/{provider}` body `{browser:{…}}` → `{success, integration}`, 400 → `{success:false, message:"<field>: <reason>"}`; `DELETE /api/v1/admin/marketing/{provider}` → `{success, integration}` (NOT_CONNECTED, all null). Mutations write the returned card into the cached list (`updateQueryData`) — no tags, no refetch.
  - `PROVIDERS`, `FieldDef`, `ProviderDef`, `Draft`, `draftFrom`, `validate`, `toBrowser`, `fieldError`.

- [ ] **Step 1: Write the API slice and register it**

`ecommerce-frontend/src/redux/api/marketingAPI.ts`:

```ts
import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

export type Provider = "google" | "meta";
export type IntegrationStatus = "NOT_CONNECTED" | "ACTIVE";

/** One provider card. `browser` holds that provider's public tag IDs; an unset ID is null. */
export type Integration = {
  provider: Provider;
  status: IntegrationStatus;
  browser: Record<string, string | null>;
  updatedAt: string | null;
};

export type MarketingResponse = { success: boolean; integrations: Integration[] };
export type IntegrationResponse = { success: boolean; integration: Integration };

/**
 * Admin → Marketing. First load is one GET with every card; save and disconnect each make one call and put
 * the returned card into the cached list instead of refetching it (call budget, marketing spec §7).
 */
export const marketingAPI = createApi({
  reducerPath: "marketingApi",
  baseQuery: authedBaseQuery("admin/marketing"),
  endpoints: (builder) => ({
    marketing: builder.query<MarketingResponse, void>({
      query: () => "",
    }),
    saveIntegration: builder.mutation<IntegrationResponse, { provider: Provider; browser: Record<string, string | null> }>({
      query: ({ provider, browser }) => ({ url: provider, method: "PUT", body: { browser } }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled;
          dispatch(replaceCard(data.integration));
        } catch {
          // the page shows the error from the mutation result
        }
      },
    }),
    disconnectIntegration: builder.mutation<IntegrationResponse, Provider>({
      query: (provider) => ({ url: provider, method: "DELETE" }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled;
          dispatch(replaceCard(data.integration));
        } catch {
          // the page shows the error from the mutation result
        }
      },
    }),
  }),
});

const replaceCard = (integration: Integration) =>
  marketingAPI.util.updateQueryData("marketing", undefined, (draft) => {
    const i = draft.integrations.findIndex((c) => c.provider === integration.provider);
    if (i >= 0) draft.integrations[i] = integration;
  });

export const { useMarketingQuery, useSaveIntegrationMutation, useDisconnectIntegrationMutation } = marketingAPI;
```

In `ecommerce-frontend/src/redux/store.ts`: add `import { marketingAPI } from "./api/marketingAPI";` after the `platformAPI` import; add `[marketingAPI.reducerPath]: marketingAPI.reducer,` after the `storeAPI` reducer line; and change the end of the middleware list from `storeAPI.middleware` to `storeAPI.middleware,` followed by a new line `marketingAPI.middleware`.

Run: `npx tsc -p .` — expect no output (the slice is exercised through the page test in Task 8).

- [ ] **Step 2: Write the failing form test**

`ecommerce-frontend/src/pages/admin/apps/marketingForm.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { PROVIDERS, draftFrom, fieldError, toBrowser, validate } from "./marketingForm";

const google = PROVIDERS.find((p) => p.provider === "google")!.fields;
const meta = PROVIDERS.find((p) => p.provider === "meta")!.fields;
const blank = draftFrom(undefined, google);

describe("marketing form", () => {
  it("lists Google first, then Meta, with the fields the API expects", () => {
    expect(PROVIDERS.map((p) => p.provider)).toEqual(["google", "meta"]);
    expect(google.map((f) => f.key)).toEqual(["ga4MeasurementId", "adsConversionId", "adsPurchaseLabel", "gtmContainerId"]);
    expect(meta.map((f) => f.key)).toEqual(["pixelId"]);
  });

  it("accepts the shapes the server accepts, and empty fields", () => {
    expect(validate({ ga4MeasurementId: "G-AB12CD3", adsConversionId: "AW-123456789", adsPurchaseLabel: "AbC-1_x",
      gtmContainerId: "GTM-ABC123" }, google)).toEqual({});
    expect(validate(blank, google)).toEqual({});
    expect(validate({ pixelId: " 123456789012345 " }, meta)).toEqual({});
  });

  it.each([
    ["ga4MeasurementId", "g-ab12cd3"],
    ["ga4MeasurementId", 'G-AB12"><script>'],
    ["adsConversionId", "AW-12345"],
    ["gtmContainerId", "GTM-abc"],
  ])("rejects %s = %s with a message for that field", (key, value) => {
    expect(validate({ ...blank, [key]: value }, google)[key]).toMatch(/.+/);
  });

  it("rejects a pixel ID that is not 10–20 digits", () => {
    expect(validate({ pixelId: "123456789012345678901" }, meta).pixelId).toMatch(/digits/);
  });

  it("needs the Ads conversion ID before a purchase label", () => {
    expect(validate({ ...blank, adsPurchaseLabel: "AbC-1" }, google).adsPurchaseLabel).toMatch(/conversion ID/);
  });

  it("fills from the saved card and sends trimmed values, empty as null", () => {
    const draft = draftFrom({ provider: "meta", status: "ACTIVE", browser: { pixelId: "123456789012345" }, updatedAt: null }, meta);
    expect(draft).toEqual({ pixelId: "123456789012345" });
    expect(toBrowser({ ...blank, ga4MeasurementId: " G-AB12CD3 " }, google)).toEqual({
      ga4MeasurementId: "G-AB12CD3", adsConversionId: null, adsPurchaseLabel: null, gtmContainerId: null,
    });
  });

  it("reads the server's `<field>: <reason>` message as an error on that field", () => {
    expect(fieldError("ga4MeasurementId: must look like G-XXXXXXX", google))
      .toEqual({ key: "ga4MeasurementId", text: "must look like G-XXXXXXX" });
    expect(fieldError("browser.adsPurchaseLabel: requires adsConversionId", google))
      .toEqual({ key: "adsPurchaseLabel", text: "requires adsConversionId" });
    expect(fieldError("Something went wrong", google)).toEqual({ text: "Something went wrong" });
    expect(fieldError("pixelId: bad", google)).toEqual({ text: "pixelId: bad" });
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npm test -- src/pages/admin/apps/marketingForm.test.ts`
Expected: FAIL — `Failed to resolve import "./marketingForm"`.

- [ ] **Step 4: Write the form rules**

`ecommerce-frontend/src/pages/admin/apps/marketingForm.ts`:

```ts
import type { Integration, Provider } from "../../../redux/api/marketingAPI";
import { TAG_PATTERNS } from "../../../utils/tracking";

export type FieldDef = { key: string; label: string; pattern: RegExp; example: string; error: string };
export type ProviderDef = { provider: Provider; title: string; intro: string; fields: FieldDef[] };
export type Draft = Record<string, string>;

/** One card per provider, Google first (the API returns them in the same order). */
export const PROVIDERS: ProviderDef[] = [
  {
    provider: "google",
    title: "Google",
    intro: "Google Analytics 4 for visits and sales, Google Ads to count purchases from your ads, and an optional Tag Manager container.",
    fields: [
      { key: "ga4MeasurementId", label: "GA4 measurement ID", pattern: TAG_PATTERNS.ga4, example: "G-AB12CD3EF4",
        error: "Use the ID that starts with G- followed by 4–12 capital letters or digits." },
      { key: "adsConversionId", label: "Google Ads conversion ID", pattern: TAG_PATTERNS.adsId, example: "AW-123456789",
        error: "Use AW- followed by 6–12 digits." },
      { key: "adsPurchaseLabel", label: "Google Ads purchase conversion label", pattern: TAG_PATTERNS.adsPurchaseLabel,
        example: "AbC-1dEfGhIj", error: "Use up to 40 letters, digits, _ or -." },
      { key: "gtmContainerId", label: "Tag Manager container ID (advanced)", pattern: TAG_PATTERNS.gtm, example: "GTM-ABC1234",
        error: "Use GTM- followed by 4–10 capital letters or digits." },
    ],
  },
  {
    provider: "meta",
    title: "Meta (Facebook and Instagram)",
    intro: "The Meta Pixel counts views, add-to-carts and purchases from your Facebook and Instagram ads.",
    fields: [
      { key: "pixelId", label: "Meta Pixel ID", pattern: TAG_PATTERNS.metaPixel, example: "123456789012345",
        error: "Use the Pixel ID: 10–20 digits." },
    ],
  },
];

export const draftFrom = (integration: Integration | undefined, fields: FieldDef[]): Draft =>
  Object.fromEntries(fields.map((f) => [f.key, integration?.browser[f.key] ?? ""]));

/** Field errors by key, mirroring the server. An empty field is allowed: that tag is simply not used. */
export const validate = (draft: Draft, fields: FieldDef[]): Record<string, string> => {
  const errors: Record<string, string> = {};
  for (const f of fields) {
    const v = (draft[f.key] ?? "").trim();
    if (v && !f.pattern.test(v)) errors[f.key] = f.error;
  }
  if (draft.adsPurchaseLabel?.trim() && !draft.adsConversionId?.trim() && !errors.adsPurchaseLabel) {
    errors.adsPurchaseLabel = "Enter the Google Ads conversion ID too: the label belongs to it.";
  }
  return errors;
};

/** The request body's `browser`: trimmed values, empty ones as null. */
export const toBrowser = (draft: Draft, fields: FieldDef[]): Record<string, string | null> =>
  Object.fromEntries(fields.map((f) => [f.key, (draft[f.key] ?? "").trim() || null]));

/** A 400's "<field>: <reason>" as an error on that field when it is one of this card's; otherwise the whole message. */
export const fieldError = (message: string, fields: FieldDef[]): { key?: string; text: string } => {
  const m = /^(?:browser\.)?(\w+): (.+)$/.exec(message);
  return m && fields.some((f) => f.key === m[1]) ? { key: m[1], text: m[2] } : { text: message };
};
```

- [ ] **Step 5: Run it to verify it passes**

Run: `npm test -- src/pages/admin/apps/marketingForm.test.ts`
Expected: `Tests  10 passed (10)`.

- [ ] **Step 6: Lint and commit**

Run: `npm run lint` (no output).

```bash
git add src/redux/api/marketingAPI.ts src/redux/store.ts src/pages/admin/apps/marketingForm.ts src/pages/admin/apps/marketingForm.test.ts
git commit -m "feat(marketing): admin marketing API slice and tag ID form rules

Save and disconnect put the returned card into the cached list instead of refetching.
Validation mirrors the server's ID patterns, including label-needs-conversion-ID.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Admin → Marketing page

**Files:**
- Create: `ecommerce-frontend/src/pages/admin/apps/marketing.tsx`
- Test: `ecommerce-frontend/src/pages/admin/apps/marketing.test.tsx`
- Modify: `ecommerce-frontend/src/styles/admin-styles/_admin-ui.scss`

**Interfaces:**
- Consumes: Task 7's hooks and `PROVIDERS`/`draftFrom`/`validate`/`toBrowser`/`fieldError`; Task 2's test helpers; existing `AdminLayout`, `StatusBadge` (renders `NOT_CONNECTED` as "Not connected", `ACTIVE` as "Active" with the existing `.badge--active` colour), `errorMessage`, `formatDateTime`.
- Produces: default export `Marketing`. One `<form class="card form-grid marketing-card">` per provider, `h2` title, status badge, help link to `/admin/help/marketing` (article in Task 9), inputs with `aria-invalid` + `aria-describedby` → `.field-error`, Save (disabled until changed), Disconnect (only when `ACTIVE`, behind `confirm()`).

- [ ] **Step 1: Write the failing page test (includes the call-budget check)**

`ecommerce-frontend/src/pages/admin/apps/marketing.test.tsx`:

```tsx
// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { configureStore } from "@reduxjs/toolkit";
import { Provider } from "react-redux";
import { MemoryRouter } from "react-router-dom";
import { marketingAPI } from "../../../redux/api/marketingAPI";
import { button, cleanup, click, field, queryButton, render, settle, type } from "../../../test/dom";
import Marketing from "./marketing";

// RTK Query builds a Request from the base URL, and Node's Request needs an absolute one.
vi.mock("../../../redux/config", () => ({ server: "http://shop.test" }));

const GOOGLE_EMPTY = {
  provider: "google", status: "NOT_CONNECTED", updatedAt: null,
  browser: { ga4MeasurementId: null, adsConversionId: null, adsPurchaseLabel: null, gtmContainerId: null },
};
const META_ACTIVE = { provider: "meta", status: "ACTIVE", updatedAt: "2026-10-30T10:00:00Z", browser: { pixelId: "123456789012345" } };

type Handler = () => { status: number; body: unknown };
let handlers: Record<string, Handler>;
let calls: { key: string; body?: unknown }[];

const json = ({ status, body }: { status: number; body: unknown }) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  calls = [];
  handlers = {
    "GET /api/v1/admin/marketing": () => ({ status: 200, body: { success: true, integrations: [GOOGLE_EMPTY, META_ACTIVE] } }),
  };
  vi.stubGlobal("fetch", vi.fn(async (req: Request) => {
    const key = `${req.method} ${new URL(req.url).pathname}`;
    calls.push({ key, body: req.method === "PUT" ? await req.clone().json() : undefined });
    const handler = handlers[key];
    return json(handler ? handler() : { status: 404, body: { success: false, message: "Not found" } });
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const show = async () => {
  const store = configureStore({
    reducer: { [marketingAPI.reducerPath]: marketingAPI.reducer },
    middleware: (getDefault) => getDefault().concat(marketingAPI.middleware),
  });
  await render(
    <Provider store={store}>
      <MemoryRouter initialEntries={["/admin/app/marketing"]}>
        <Marketing />
      </MemoryRouter>
    </Provider>,
  );
  await settle();
};

const card = (title: string) => {
  const found = [...document.querySelectorAll<HTMLFormElement>("form.marketing-card")]
    .find((f) => f.querySelector("h2")?.textContent === title);
  if (!found) throw new Error(`No card "${title}"`);
  return found;
};
const google = () => card("Google");
const meta = () => card("Meta (Facebook and Instagram)");
const status = (c: HTMLElement) => c.querySelector(".badge")?.textContent;

describe("Admin → Marketing", () => {
  it("loads both cards with one request", async () => {
    await show();
    expect(calls.map((c) => c.key)).toEqual(["GET /api/v1/admin/marketing"]);
    expect(status(google())).toBe("Not connected");
    expect(status(meta())).toBe("Active");
    expect(field("Meta Pixel ID", meta()).value).toBe("123456789012345");
    expect(field("GA4 measurement ID", google()).value).toBe("");
    for (const c of [google(), meta()]) {
      expect(c.querySelector('a[href="/admin/help/marketing"]')).not.toBeNull();
    }
  });

  it("checks IDs in the browser and sends nothing while one is wrong", async () => {
    await show();
    const ga4 = field("GA4 measurement ID", google());
    await type(ga4, "g-abc");
    await click(button("Save", google()));
    expect(ga4.getAttribute("aria-invalid")).toBe("true");
    const error = document.getElementById(ga4.getAttribute("aria-describedby")!);
    expect(error?.textContent).toContain("starts with G-");
    await type(ga4, "");
    await type(field("Google Ads purchase conversion label", google()), "AbC-1");
    await click(button("Save", google()));
    expect(google().textContent).toContain("Enter the Google Ads conversion ID too");
    expect(calls).toHaveLength(1);
  });

  it("saves with one request and shows the returned card without loading the list again", async () => {
    handlers["PUT /api/v1/admin/marketing/google"] = () => ({ status: 200, body: { success: true, integration: {
      ...GOOGLE_EMPTY, status: "ACTIVE", updatedAt: "2026-10-30T11:00:00Z",
      browser: { ...GOOGLE_EMPTY.browser, ga4MeasurementId: "G-AB12CD3" } } } });
    await show();
    await type(field("GA4 measurement ID", google()), " G-AB12CD3 ");
    await click(button("Save", google()));
    await settle();
    expect(calls).toEqual([
      { key: "GET /api/v1/admin/marketing", body: undefined },
      { key: "PUT /api/v1/admin/marketing/google", body: { browser: {
        ga4MeasurementId: "G-AB12CD3", adsConversionId: null, adsPurchaseLabel: null, gtmContainerId: null } } },
    ]);
    expect(status(google())).toBe("Active");
    expect(field("GA4 measurement ID", google()).value).toBe("G-AB12CD3");
  });

  it("shows the server's field error next to that field", async () => {
    handlers["PUT /api/v1/admin/marketing/google"] = () => ({ status: 400,
      body: { success: false, message: "adsPurchaseLabel: requires adsConversionId" } });
    await show();
    await type(field("GA4 measurement ID", google()), "G-AB12CD3");
    await click(button("Save", google()));
    await settle();
    const label = field("Google Ads purchase conversion label", google());
    expect(label.getAttribute("aria-invalid")).toBe("true");
    expect(document.getElementById(label.getAttribute("aria-describedby")!)?.textContent)
      .toContain("requires adsConversionId");
    expect(status(google())).toBe("Not connected");
  });

  it("shows a server error that names no field at the top of the card", async () => {
    handlers["PUT /api/v1/admin/marketing/meta"] = () => ({ status: 500, body: { success: false, message: "Try again later" } });
    await show();
    await type(field("Meta Pixel ID", meta()), "999999999999");
    await click(button("Save", meta()));
    await settle();
    expect(meta().querySelector('[role="alert"]')?.textContent).toContain("Try again later");
  });

  it("asks before disconnecting, then clears the card with one request", async () => {
    handlers["DELETE /api/v1/admin/marketing/meta"] = () => ({ status: 200, body: { success: true, integration: {
      ...META_ACTIVE, status: "NOT_CONNECTED", updatedAt: null, browser: { pixelId: null } } } });
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    await show();
    expect(queryButton("Disconnect", google())).toBeNull(); // nothing to disconnect yet

    await click(button("Disconnect", meta()));
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(1);

    await click(button("Disconnect", meta()));
    await settle();
    expect(calls.map((c) => c.key)).toEqual(["GET /api/v1/admin/marketing", "DELETE /api/v1/admin/marketing/meta"]);
    expect(status(meta())).toBe("Not connected");
    expect(field("Meta Pixel ID", meta()).value).toBe("");
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/pages/admin/apps/marketing.test.tsx`
Expected: FAIL — `Failed to resolve import "./marketing"`.

- [ ] **Step 3: Write the page**

`ecommerce-frontend/src/pages/admin/apps/marketing.tsx`:

```tsx
import { FormEvent, useEffect, useMemo, useState } from "react";
import toast from "react-hot-toast";
import { LuAlertCircle } from "react-icons/lu";
import { Link } from "react-router-dom";
import AdminLayout from "../../../components/admin/AdminLayout";
import StatusBadge from "../../../components/admin/StatusBadge";
import { errorMessage } from "../../../redux/api/baseQuery";
import {
  Integration,
  useDisconnectIntegrationMutation,
  useMarketingQuery,
  useSaveIntegrationMutation,
} from "../../../redux/api/marketingAPI";
import { formatDateTime } from "../../../utils/features";
import { PROVIDERS, ProviderDef, draftFrom, fieldError, toBrowser, validate } from "./marketingForm";

const HELP = "/admin/help/marketing";

/** One provider: status, its tag IDs with inline checks, Save and Disconnect. */
const ProviderCard = ({ def, integration }: { def: ProviderDef; integration?: Integration }) => {
  const [save, { isLoading: saving }] = useSaveIntegrationMutation();
  const [disconnect, { isLoading: removing }] = useDisconnectIntegrationMutation();
  const saved = useMemo(() => draftFrom(integration, def.fields), [integration, def.fields]);
  const [draft, setDraft] = useState(saved);
  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [server, setServer] = useState<{ key?: string; text: string } | null>(null);

  useEffect(() => setDraft(saved), [saved]);

  const errors = validate(draft, def.fields);
  const errorFor = (key: string) => (checked[key] ? errors[key] : undefined) ?? (server?.key === key ? server.text : undefined);
  const dirty = JSON.stringify(draft) !== JSON.stringify(saved);
  const connected = integration?.status === "ACTIVE";
  const busy = saving || removing;
  const titleId = `marketing-${def.provider}`;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setChecked(Object.fromEntries(def.fields.map((f) => [f.key, true])));
    setServer(null);
    if (Object.keys(errors).length > 0) return;
    const res = await save({ provider: def.provider, browser: toBrowser(draft, def.fields) });
    if ("error" in res) return setServer(fieldError(errorMessage(res.error), def.fields));
    setChecked({});
    toast.success(`${def.title} saved`);
  };

  const remove = async () => {
    if (!confirm(`Disconnect ${def.title}? Its tags stop loading on your store straight away.`)) return;
    setServer(null);
    const res = await disconnect(def.provider);
    if ("error" in res) return setServer({ text: errorMessage(res.error) });
    toast.success(`${def.title} disconnected`);
  };

  return (
    <form className="card form-grid marketing-card" aria-labelledby={titleId} onSubmit={submit} noValidate>
      <div className="span-2 marketing-card__head">
        <h2 id={titleId}>{def.title}</h2>
        <StatusBadge status={integration?.status ?? "NOT_CONNECTED"} />
      </div>
      <p className="span-2 store-settings__hint">
        {def.intro} <Link to={HELP}>Where do I find these IDs?</Link>
      </p>
      {server && !server.key && (
        <p className="span-2 field-error" role="alert">
          <LuAlertCircle aria-hidden /> {server.text}
        </p>
      )}

      {def.fields.map((f) => {
        const id = `${def.provider}-${f.key}`;
        const error = errorFor(f.key);
        return (
          <label key={f.key} htmlFor={id}>
            {f.label}
            <input
              id={id}
              value={draft[f.key]}
              placeholder={f.example}
              maxLength={40}
              spellCheck={false}
              autoComplete="off"
              aria-invalid={!!error}
              aria-describedby={error ? `${id}-error` : undefined}
              onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))}
              onBlur={() => setChecked((c) => ({ ...c, [f.key]: true }))}
            />
            {error && (
              <span id={`${id}-error`} className="field-error">
                <LuAlertCircle aria-hidden /> {error}
              </span>
            )}
          </label>
        );
      })}

      <div className="span-2 store-settings__actions">
        <button type="submit" className="btn btn--primary" disabled={busy || !dirty}>
          {saving ? "Saving…" : "Save"}
        </button>
        {connected && (
          <button type="button" className="btn btn--danger" disabled={busy} onClick={remove}>
            {removing ? "Disconnecting…" : "Disconnect"}
          </button>
        )}
        {integration?.updatedAt && (
          <span className="store-settings__note">Last saved {formatDateTime(integration.updatedAt)}</span>
        )}
      </div>
    </form>
  );
};

/** Admin → Marketing: the store's Google and Meta tag IDs. Shown only when the store's `marketing` feature is on. */
const Marketing = () => {
  const { data, isLoading, isError, error } = useMarketingQuery();

  return (
    <AdminLayout
      title="Marketing"
      description="Measure visits, add-to-carts and sales from your Google and Meta ads. Tags load only for shoppers who accept cookies."
    >
      <div className="store-settings">
        {isLoading && <p className="store-settings__note">Loading…</p>}
        {isError && <p className="store-settings__note" role="alert">{errorMessage(error)}</p>}
        {data &&
          PROVIDERS.map((def) => (
            <ProviderCard key={def.provider} def={def} integration={data.integrations.find((i) => i.provider === def.provider)} />
          ))}
      </div>
    </AdminLayout>
  );
};

export default Marketing;
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/pages/admin/apps/marketing.test.tsx`
Expected: `Tests  6 passed (6)`. The first test proves first load = 1 request; the save and disconnect tests prove each action = 1 request and no second `GET`.

- [ ] **Step 5: Card header style**

In `ecommerce-frontend/src/styles/admin-styles/_admin-ui.scss`, insert directly above the `// ---------- analytics sections ----------` comment:

```scss
// ---------- marketing (pages/admin/apps/marketing.tsx) ----------
// Cards sit in .store-settings, which scrolls; each card is a .form-grid.
.marketing-card__head {
  @include flex(row, space-between, center, $spacing-sm);

  h2 {
    @include text(h4);
    color: $color-text-primary;
  }
}
```

- [ ] **Step 6: Lint, build, commit**

Run: `npm run lint && npm run build` (green).

```bash
git add src/pages/admin/apps/marketing.tsx src/pages/admin/apps/marketing.test.tsx src/styles/admin-styles/_admin-ui.scss
git commit -m "feat(marketing): admin Marketing page with Google and Meta cards

One request on load; save and disconnect one request each. Inline ID checks, the server's field
error under its field, confirmation before disconnecting.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Feature-gated nav entry, route and help article

**Files:**
- Modify: `ecommerce-frontend/src/utils/routes.ts`, `ecommerce-frontend/src/components/admin/AdminSidebar.tsx`, `ecommerce-frontend/src/App.tsx`, `ecommerce-frontend/src/help/adminGuides.ts`
- Test: `ecommerce-frontend/src/utils/routes.test.ts`, `ecommerce-frontend/src/help/adminGuides.test.ts`

**Interfaces:**
- Consumes: existing `features` (`tenant/tenant.ts`, filled from tenant config before the first render), Task 8's page.
- Produces: `adminPageVisible(url, features)`; route `/admin/app/marketing` (only when `features.marketing === true`); guide slug `marketing`; `guideForPath("/admin/app/marketing") === "marketing"`.

- [ ] **Step 1: Write the failing tests**

In `ecommerce-frontend/src/utils/routes.test.ts`, change the import to

```ts
import { adminPageVisible, showsBottomNav, surfaceFor } from "./routes";
```

add inside the first `surfaceFor` test, after `expect(surfaceFor("/admin/analytics")).toBe("admin");`:

```ts
    expect(surfaceFor("/admin/app/marketing")).toBe("admin");
    expect(showsBottomNav("/admin/app/marketing")).toBe(false);
```

and append:

```ts
describe("adminPageVisible", () => {
  it("shows Marketing only while the store's marketing switch is on", () => {
    expect(adminPageVisible("/admin/app/marketing", { marketing: true })).toBe(true);
    expect(adminPageVisible("/admin/app/marketing", { marketing: false })).toBe(false);
    expect(adminPageVisible("/admin/app/marketing", {})).toBe(false); // config not loaded: hidden, the API would 404
  });

  it("always shows pages that have no switch", () => {
    for (const url of ["/admin/app/coupon", "/admin/settings"]) expect(adminPageVisible(url, {})).toBe(true);
  });
});
```

In `ecommerce-frontend/src/help/adminGuides.test.ts`, add `["/admin/app/marketing", "marketing"],` after the `["/admin/app/coupon", "coupons"],` row, and add `"/admin/app/marketing"` to the path list of "opens a guide that exists on every admin page".

- [ ] **Step 2: Run them to verify they fail**

Run: `npm test -- src/utils/routes.test.ts src/help/adminGuides.test.ts`
Expected: `TypeError: adminPageVisible is not a function` (2 tests) and `/admin/app/marketing opens marketing` → `expected 'getting-started' to be 'marketing'`.

- [ ] **Step 3: `adminPageVisible`**

In `ecommerce-frontend/src/utils/routes.ts`, insert above the `/** Which token set …` comment:

```ts
/** Admin pages that exist only while the store's feature switch is on (tenant config `features`). */
const FEATURE_PAGES: Record<string, string> = { "/admin/app/marketing": "marketing" };

/** False for a switched page whose feature is off or not known yet (the API would answer 404). */
export const adminPageVisible = (url: string, features: Record<string, boolean>) => {
  const key = FEATURE_PAGES[url];
  return !key || features[key] === true;
};
```

- [ ] **Step 4: Help article and `guideForPath`**

In `ecommerce-frontend/src/help/adminGuides.ts`:

- in `guideForPath`, after the `/admin/app/coupon` line:

```ts
  if (pathname === "/admin/app/marketing") return "marketing";
```

- in the `getting-started` guide's first paragraph, replace `Coupons for discount codes, Audit log` with `Coupons for discount codes, Marketing for your Google and Meta tags (when your platform has switched it on), Audit log`.
- insert this guide into `GUIDES` directly before the `audit-log` guide:

```ts
  {
    slug: "marketing",
    group: "Sales",
    title: "Connecting Google and Meta",
    summary: "Paste your GA4, Google Ads, Tag Manager and Meta Pixel IDs so visits and sales from your ads are counted.",
    openLabel: { to: "/admin/app/marketing", label: "Open Marketing" },
    body: [
      { type: "p", text: "Marketing appears in the menu once your platform administrator switches it on for your store. Each card holds the public IDs of one company's tags. Leave a field empty and that tag is not used." },
      { type: "steps", title: "Where to find each ID", items: [
        "GA4 measurement ID: in Google Analytics, open Admin → Data streams → your website stream. It starts with G-.",
        "Google Ads conversion ID and purchase label: in Google Ads, open Goals → Conversions → your purchase action → Tag setup → Install the tag yourself. In the send_to value AW-123456789/AbC-1dEf, the part before the slash is the ID and the part after it is the label.",
        "Tag Manager container ID (advanced): the GTM-… ID next to your container's name in Tag Manager. Only needed if you already manage tags there.",
        "Meta Pixel ID: in Meta Events Manager, open Data sources and choose your pixel. The ID is the number shown under its name.",
      ] },
      { type: "steps", title: "Connect and check", items: [
        "Go to Marketing.",
        "Paste the IDs into the card and click Save. A wrong-looking ID is flagged under its field and nothing is saved. The card then shows Active.",
        "Open your store in a private window, accept cookies and view a product. Within a minute it shows in GA4 under Reports → Realtime and in Meta Events Manager under Test events.",
      ] },
      { type: "p", text: "Tags load only for shoppers who accept cookies in the banner: GA4 with Analytics, and Google Ads, Tag Manager and the Meta Pixel with Marketing. Your store sends view item, add to cart, begin checkout and purchase. Each purchase is sent once, with the order's id, total and your store's currency." },
      { type: "warn", text: "Your store's security policy blocks Tag Manager tags that run custom HTML or load scripts from other sites. Use the GA4, Google Ads and Meta fields for those tools instead." },
      { type: "tip", text: "Disconnect removes the IDs: shoppers stop loading that company's tags on their next visit. Every save and disconnect is recorded in the Audit log." },
    ],
    related: ["coupons", "audit-log", "store-settings"],
  },
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm test -- src/utils/routes.test.ts src/help/adminGuides.test.ts`
Expected: all pass (`routes` 4 tests, `adminGuides` 19 tests).

- [ ] **Step 6: Sidebar entry**

In `ecommerce-frontend/src/components/admin/AdminSidebar.tsx`:

- add `LuMegaphone,` to the `react-icons/lu` import (alphabetically after `LuHistory,`);
- replace `import { brand } from "../../tenant/tenant";` with

```tsx
import { brand, features } from "../../tenant/tenant";
import { adminPageVisible } from "../../utils/routes";
```

- add to `NAV` after the Coupons entry:

```tsx
  { url: "/admin/app/marketing", text: "Marketing", Icon: LuMegaphone },
```

- in the "Manage" list replace `{NAV.map(({ url, text, Icon }) => (` with

```tsx
          {NAV.filter(({ url }) => adminPageVisible(url, features)).map(({ url, text, Icon }) => (
```

- [ ] **Step 7: Route**

In `ecommerce-frontend/src/App.tsx`:

- replace `import { surfaceFor } from "./utils/routes";` with

```tsx
import { adminPageVisible, surfaceFor } from "./utils/routes";
import { features } from "./tenant/tenant";
```

- after `const Coupon = lazy(() => import("./pages/admin/apps/coupon"));` add

```tsx
const Marketing = lazy(() => import("./pages/admin/apps/marketing"));
```

- after `<Route path="/admin/app/coupon" element={<Coupon />} />` add

```tsx
            {/* Feature off: no route, so the address falls through to "/" like any unknown page. */}
            {adminPageVisible("/admin/app/marketing", features) && (
              <Route path="/admin/app/marketing" element={<Marketing />} />
            )}
```

- [ ] **Step 8: Full check and commit**

Run: `npm test && npm run lint && npm run build`
Expected: `Test Files  24 passed (24)`, `Tests  153 passed (153)`; lint silent; build succeeds.

```bash
git add src/utils/routes.ts src/utils/routes.test.ts src/components/admin/AdminSidebar.tsx src/App.tsx \
  src/help/adminGuides.ts src/help/adminGuides.test.ts
git commit -m "feat(marketing): Marketing in the admin menu when the store's feature is on, with a help guide

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Verification and browser pass

**Files:** none unless a check fails (then fix test-first in the task that owns the file, and commit there).

- [ ] **Step 1: Everything green**

Run (from `ecommerce-frontend/`): `npm test && npm run lint && npm run build`
Expected: `Test Files  24 passed (24)`, `Tests  153 passed (153)` (93 baseline + 60 new); lint prints nothing; build `✓ built`. Report the counts.

- [ ] **Step 2: Storefront, no backend marketing needed**

Run the stack the usual local way (separate `MONGO_DB` from the docker stack — see the repo's run notes; never read secrets from `.env`), then `npm run dev`. With the Playwright MCP browser:

1. Clear site data, open `/`. The banner shows at the bottom; the page behind it scrolls and is clickable. Network: no request to `googletagmanager.com`, `google-analytics.com`, `doubleclick.net` or `facebook.net`. Note the number of `/api/` requests on this first load — it must equal the same page on `dev` (tags add no call).
2. Click Customise → all three optional boxes unticked → Save choices. Reload: no banner. Footer → Cookie settings reopens it with focus on its heading. Keyboard only: Tab reaches all buttons and boxes.
3. At 390 px wide, the banner sits above the bottom tab bar and its buttons wrap.
4. `/admin/analytics` (signed in as the store admin — ask the user to sign in; do not use stored passwords): no banner.

- [ ] **Step 3: With the backend (only if `feat/marketing` already has backend Tasks 0.1, 1.2 and 1.3)**

1. Platform admin switches `marketing` on for the local store; reload `/admin`. Sidebar shows Marketing; Help on that page opens "Connecting Google and Meta". With the switch off, the entry is gone and `/admin/app/marketing` redirects to `/`.
2. Open Admin → Marketing. Network: exactly one `GET /api/v1/admin/marketing` for the page. Paste `G-AB12CD3` and save: one `PUT`, card turns Active, no second `GET`. Enter `AW-1`: inline error, no request. Disconnect → confirm → one `DELETE`.
3. Save test IDs (`G-TEST12345`, `AW-123456789` + label `AbC-1`, Meta `1234567890123`). On the storefront in a fresh profile: before consent no tag host is contacted; Reject all → still none; Cookie settings → Analytics only → `gtag/js?id=G-TEST12345` loads and nothing from `facebook.net`; then Accept all → Ads, Meta and (if set) GTM load without a reload and the browser console shows no CSP violation for these hosts in `npm run build && npm run preview` behind the nginx image (or the docker frontend).
4. Place a test order with the fake gateway: exactly one `purchase` in `window.dataLayer` and one `Purchase` in `fbq.queue`, both carrying the order id; reload `/order/<id>`: no second purchase.

If Step 3 cannot run because the backend is not there yet, say so in the report instead of skipping silently.

- [ ] **Step 4: Report**

Report: test counts, lint/build status, what Step 2/3 showed (with request counts), anything skipped and why. No commit unless a fix was needed.

---

## Open risks (for the reviewer)

- **GTM under a strict CSP:** containers with Custom HTML tags or templates loading other hosts are blocked (no `'unsafe-inline'`). The help article warns owners; widening the CSP per store is not possible with one nginx template.
- **Google Ads pings to country domains** (e.g. `www.google.co.in`) via `fetch` are not in `connect-src`; image pings are allowed (`img-src https:`). Watch the console in Task 10 Step 3 and add the domain if conversions drop.
- **After withdrawal, gtag.js stays in the page** until reload; events stop because `track()` checks consent and `ga-disable-<ID>` is set, but `ga-disable` for an `AW-` ID is not documented by Google. Consent Mode `update: denied` still applies to Ads.
- **React StrictMode in dev** runs effects twice, so `view_item` fires twice in `npm run dev` only (not in builds).
- **Feature switch changes need a reload:** `features` is read when the app renders, like the existing variants switch.
- **Backend field names must match exactly**: admin `browser` keys `ga4MeasurementId, adsConversionId, adsPurchaseLabel, gtmContainerId, pixelId`; tenant config `marketing.tags` keys `ga4, adsId, adsPurchaseLabel, gtm, metaPixel`.
- **Privacy version bump** (re-prompting signed-in users for the changed Privacy Policy) is not done here; see "Changes from the spec".
