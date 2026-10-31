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
