// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { configureStore } from "@reduxjs/toolkit";
import { Provider } from "react-redux";
import { MemoryRouter } from "react-router-dom";
import { marketingAPI } from "../../../redux/api/marketingAPI";
import { button, cleanup, click, field, queryButton, render, settle } from "../../../test/dom";
import Marketing from "./marketing";

// RTK Query builds a Request from the base URL, and Node's Request needs an absolute one.
vi.mock("../../../redux/config", () => ({ server: "http://shop.test" }));

const OLD_URL = `https://shop.test/api/v1/feeds/${"a".repeat(43)}/products.xml`;
const NEW_URL = `https://shop.test/api/v1/feeds/${"b".repeat(43)}/products.xml`;
const CARDS = [
  { provider: "google", status: "NOT_CONNECTED", updatedAt: null,
    browser: { ga4MeasurementId: null, adsConversionId: null, adsPurchaseLabel: null, gtmContainerId: null } },
  { provider: "meta", status: "NOT_CONNECTED", updatedAt: null, browser: { pixelId: null } },
];

type Reply = { status: number; body: unknown };
let feed: { enabled: boolean; url: string | null } | undefined;
let handlers: Record<string, () => Reply>;
let calls: { key: string; body?: unknown }[];

const json = ({ status, body }: Reply) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  calls = [];
  feed = { enabled: false, url: null };
  handlers = {
    "GET /api/v1/admin/marketing": () => ({
      status: 200, body: { success: true, integrations: CARDS, ...(feed ? { feed } : {}) },
    }),
  };
  vi.stubGlobal("fetch", vi.fn(async (req: Request) => {
    const key = `${req.method} ${new URL(req.url).pathname}`;
    const text = req.method === "GET" ? "" : await req.clone().text();
    calls.push({ key, body: text ? JSON.parse(text) : undefined });
    const handler = handlers[key];
    return json(handler ? handler() : { status: 404, body: { success: false, message: "Not found" } });
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  Reflect.deleteProperty(navigator, "clipboard");
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

const card = () => document.querySelector<HTMLElement>('section[aria-labelledby="marketing-feed"]');
const feedCard = () => {
  const found = card();
  if (!found) throw new Error("No feed card");
  return found;
};
const toggle = () => field("Publish my products in a feed", feedCard());
const badge = () => feedCard().querySelector(".badge")?.textContent;

describe("Product feed card", () => {
  it("shows the feed off, from the Marketing page's one request", async () => {
    await show();
    expect(calls.map((c) => c.key)).toEqual(["GET /api/v1/admin/marketing"]);
    expect(toggle().checked).toBe(false);
    expect(badge()).toBe("Inactive");
    expect(queryButton("Copy", feedCard())).toBeNull();
    expect(feedCard().querySelector('a[href="/admin/help/product-feed"]')).not.toBeNull();
  });

  it("turns the feed on with one request and shows the address it returns", async () => {
    handlers["PUT /api/v1/admin/marketing/feed"] = () => ({
      status: 200, body: { success: true, feed: { enabled: true, url: OLD_URL } },
    });
    await show();
    await click(toggle());
    await settle();
    expect(calls).toEqual([
      { key: "GET /api/v1/admin/marketing", body: undefined },
      { key: "PUT /api/v1/admin/marketing/feed", body: { enabled: true } },
    ]);
    expect(toggle().checked).toBe(true);
    expect(badge()).toBe("Active");
    expect(field("Feed address", feedCard()).value).toBe(OLD_URL);
    expect(feedCard().textContent).toContain("Merchant Center");
    expect(feedCard().textContent).toContain("Commerce Manager");
  });

  it("turns the feed off with one request", async () => {
    feed = { enabled: true, url: OLD_URL };
    handlers["PUT /api/v1/admin/marketing/feed"] = () => ({
      status: 200, body: { success: true, feed: { enabled: false, url: null } },
    });
    await show();
    await click(toggle());
    await settle();
    expect(calls[1]).toEqual({ key: "PUT /api/v1/admin/marketing/feed", body: { enabled: false } });
    expect(calls).toHaveLength(2);
    expect(toggle().checked).toBe(false);
    expect(() => field("Feed address", feedCard())).toThrow();
  });

  it("copies the address without calling the server", async () => {
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    feed = { enabled: true, url: OLD_URL };
    await show();
    await click(button("Copy", feedCard()));
    await settle();
    expect(writeText).toHaveBeenCalledWith(OLD_URL);
    expect(calls).toHaveLength(1);
  });

  it("asks before making a new address, then shows it without reloading the page", async () => {
    feed = { enabled: true, url: OLD_URL };
    handlers["POST /api/v1/admin/marketing/feed/rotate"] = () => ({
      status: 200, body: { success: true, feed: { enabled: true, url: NEW_URL } },
    });
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    await show();

    await click(button("New URL", feedCard()));
    expect(confirm.mock.calls[0][0]).toContain("stops working");
    expect(calls).toHaveLength(1);

    await click(button("New URL", feedCard()));
    await settle();
    expect(calls.map((c) => c.key)).toEqual([
      "GET /api/v1/admin/marketing", "POST /api/v1/admin/marketing/feed/rotate",
    ]);
    expect(field("Feed address", feedCard()).value).toBe(NEW_URL);
  });

  it("shows the server's message when a change fails and keeps the address", async () => {
    feed = { enabled: true, url: OLD_URL };
    handlers["POST /api/v1/admin/marketing/feed/rotate"] = () => ({
      status: 400, body: { success: false, message: "feed: enable it first" },
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    await show();
    await click(button("New URL", feedCard()));
    await settle();
    expect(feedCard().querySelector('[role="alert"]')?.textContent).toContain("feed: enable it first");
    expect(field("Feed address", feedCard()).value).toBe(OLD_URL);
  });

  it("is left out when the server sends no feed", async () => {
    feed = undefined;
    await show();
    expect(card()).toBeNull();
  });
});
