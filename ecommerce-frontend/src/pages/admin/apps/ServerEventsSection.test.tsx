// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { configureStore } from "@reduxjs/toolkit";
import { Provider } from "react-redux";
import { MemoryRouter } from "react-router-dom";
import { marketingAPI } from "../../../redux/api/marketingAPI";
import { button, cleanup, click, field, render, settle, type } from "../../../test/dom";
import Marketing from "./marketing";

vi.mock("../../../redux/config", () => ({ server: "http://shop.test" }));

const OFF = { enabled: false, hasSecret: false, testEventCode: null, available: true };
const delivery = (i: number) => ({ eventId: `o${i}`, orderNumber: `ORD-${i}`, eventName: "purchase", status: "SENT",
  attempts: 1, createdAt: "2026-10-31T10:00:00Z", updatedAt: "2026-10-31T10:00:30Z", nextAttemptAt: null, error: null });
const GOOGLE = { provider: "google", status: "NOT_CONNECTED", updatedAt: null, server: OFF, lastError: null,
  lastSuccessAt: null, deliveries: [],
  browser: { ga4MeasurementId: null, adsConversionId: null, adsPurchaseLabel: null, gtmContainerId: null } };
const META = { provider: "meta", status: "ACTIVE", updatedAt: "2026-10-31T10:00:00Z", browser: { pixelId: "123456789012345" },
  server: OFF, lastError: null, lastSuccessAt: null, deliveries: [] as unknown[] };
const SECRET = "fake-meta-token-aaaaaaaa";

type Reply = { status: number; body: unknown };
let handlers: Record<string, () => Reply>;
let calls: { key: string; search: string; body?: unknown }[];
let cards: unknown[];

beforeEach(() => {
  calls = [];
  cards = [GOOGLE, META];
  handlers = { "GET /api/v1/admin/marketing": () => ({ status: 200, body: { success: true, integrations: cards } }) };
  vi.stubGlobal("fetch", vi.fn(async (req: Request) => {
    const url = new URL(req.url);
    const key = `${req.method} ${url.pathname}`;
    calls.push({ key, search: url.search, body: req.method === "PUT" ? await req.clone().json() : undefined });
    const r = handlers[key]?.() ?? { status: 404, body: { success: false, message: "Not found" } };
    return new Response(JSON.stringify(r.body), { status: r.status, headers: { "Content-Type": "application/json" } });
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
    middleware: (d) => d().concat(marketingAPI.middleware),
  });
  await render(<Provider store={store}><MemoryRouter><Marketing /></MemoryRouter></Provider>);
  await settle();
  return store;
};
const card = (title: string) => [...document.querySelectorAll<HTMLElement>("form.marketing-card")]
  .find((f) => f.querySelector("h2")?.textContent === title)!;
const meta = () => card("Meta (Facebook and Instagram)").querySelector<HTMLElement>("fieldset.server-events")!;
const google = () => card("Google").querySelector<HTMLElement>("fieldset.server-events")!;

describe("server events on Admin → Marketing", () => {
  it("still loads the page with one request and hides the section for phase-1 cards", async () => {
    cards = [{ ...GOOGLE, server: undefined }, { ...META, server: undefined }];
    await show();
    expect(calls.map((c) => c.key)).toEqual(["GET /api/v1/admin/marketing"]);
    expect(document.querySelector("fieldset.server-events")).toBeNull();
  });

  it("explains when the platform cannot store secrets, and asks for the ID first", async () => {
    cards = [GOOGLE, { ...META, server: { ...OFF, available: false } }];
    await show();
    expect(meta().textContent).toContain("isn't available");
    expect(meta().querySelector("input")).toBeNull();
    expect(google().textContent).toContain("Save your GA4 measurement ID first");
  });

  it("needs a secret to switch on and sends nothing until then", async () => {
    await show();
    await click(field("Also send purchases from the server", meta()));
    await click(button("Save server settings", meta()));
    expect(meta().textContent).toContain("Paste the secret");
    expect(calls).toHaveLength(1);
  });

  it("saves with one PUT of only the server settings, then forgets the typed secret", async () => {
    handlers["PUT /api/v1/admin/marketing/meta"] = () => ({ status: 200, body: { success: true,
      integration: { ...META, server: { enabled: true, hasSecret: true, testEventCode: "TEST123", available: true } } } });
    const store = await show();
    await click(field("Also send purchases from the server", meta()));
    await type(field("Conversions API access token", meta()), SECRET);
    await type(field("Test event code", meta()), "TEST123");
    await click(button("Save server settings", meta()));
    await settle();
    expect(calls.slice(1)).toEqual([{ key: "PUT /api/v1/admin/marketing/meta", search: "",
      body: { server: { enabled: true, secret: SECRET, testEventCode: "TEST123" } } }]);
    const secret = field("Conversions API access token", meta());
    expect(secret.value).toBe("");
    expect(secret.type).toBe("password");
    expect(secret.placeholder).toContain("Saved");
    expect(JSON.stringify(store.getState())).not.toContain(SECRET); // write-only: not kept in the mutation cache
  });

  it("shows the server's field error under the secret", async () => {
    handlers["PUT /api/v1/admin/marketing/meta"] = () => ({ status: 400,
      body: { success: false, message: "server.secret: use 16-512 letters, digits, _ or -" } });
    await show();
    await type(field("Conversions API access token", meta()), SECRET);
    await click(button("Save server settings", meta()));
    await settle();
    const secret = field("Conversions API access token", meta());
    expect(secret.getAttribute("aria-invalid")).toBe("true");
    expect(document.getElementById(secret.getAttribute("aria-describedby")!)?.textContent).toContain("16-512");
  });

  it("sends a test event with one POST and shows the verdict", async () => {
    cards = [GOOGLE, { ...META, server: { enabled: true, hasSecret: true, testEventCode: "TEST123", available: true } }];
    handlers["POST /api/v1/admin/marketing/meta/test"] = () => ({ status: 200, body: { success: true,
      test: { ok: false, code: "AUTH_FAILED", message: "Meta rejected the saved secret. Create a new one and save it here." } } });
    await show();
    await click(button("Send test event", meta()));
    await settle();
    expect(calls.map((c) => c.key)).toEqual(["GET /api/v1/admin/marketing", "POST /api/v1/admin/marketing/meta/test"]);
    expect(meta().querySelector('[role="status"]')?.textContent).toContain("Meta rejected the saved secret");
  });

  it("shows why a failing card is in error", async () => {
    cards = [GOOGLE, { ...META, status: "ERROR", server: { ...OFF, enabled: true, hasSecret: true },
      lastError: { at: "2026-10-31T10:05:00Z", code: "GAVE_UP", message: "Meta could not be reached after 6 attempts, so this purchase was not sent." } }];
    await show();
    expect(card("Meta (Facebook and Instagram)").querySelector(".badge")?.textContent).toBe("Error");
    expect(meta().querySelector('[role="alert"]')?.textContent).toContain("after 6 attempts");
    expect(button("Disconnect", card("Meta (Facebook and Instagram)"))).toBeTruthy();
  });

  it("lists recent deliveries and loads more with one request", async () => {
    cards = [GOOGLE, { ...META, deliveries: Array.from({ length: 10 }, (_, i) => delivery(i)) }];
    handlers["GET /api/v1/admin/marketing/meta/deliveries"] = () => ({ status: 200, body: { success: true,
      deliveries: Array.from({ length: 20 }, (_, i) => delivery(i)), page: 1, totalPage: 2 } });
    await show();
    expect(meta().querySelectorAll("tbody tr")).toHaveLength(10);
    expect(meta().textContent).toContain("Sent");
    await click(button("Show more", meta()));
    await settle();
    expect(calls.at(-1)).toMatchObject({ key: "GET /api/v1/admin/marketing/meta/deliveries", search: "?page=1" });
    expect(meta().querySelectorAll("tbody tr")).toHaveLength(20);
    expect(button("Show more", meta())).toBeTruthy(); // page 2 still to come
  });
});
