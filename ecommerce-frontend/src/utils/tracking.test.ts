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
