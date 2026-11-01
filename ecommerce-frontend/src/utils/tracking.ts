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
