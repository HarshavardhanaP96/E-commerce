// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { contrastRatio } from "../utils/contrast";
import { configureTracking } from "../utils/tracking";
import { applyTenantConfig, applyTheme } from "./tenant";

vi.mock("../utils/tracking", () => ({ configureTracking: vi.fn() }));

const sheet = () => document.getElementById("tenant-theme")?.textContent ?? "";
const token = (name: string) => new RegExp(`${name}: (#[0-9a-f]{6});`).exec(sheet())?.[1];

beforeEach(() => {
  document.head.innerHTML = "";
  document.documentElement.removeAttribute("style");
});

describe("applyTheme", () => {
  it("scopes the store's tokens to the storefront and never writes <html> inline style", () => {
    applyTheme({ "--accent": "#2563eb" });
    expect(sheet()).toMatch(/^\[data-surface="store"\] \{/);
    expect(sheet()).not.toContain(":root");
    expect(sheet()).toContain("--accent: #2563eb;");
    expect(document.documentElement.getAttribute("style")).toBeNull();
  });

  it("derives a readable --accent-strong and --accent-text from a light accent", () => {
    applyTheme({ "--accent": "#ffeb3b" });
    expect(contrastRatio("#ffffff", token("--accent-strong")!)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(token("--accent-text")!, "#f5f5f3")).toBeGreaterThanOrEqual(4.5);
  });

  it("keeps values the store set itself", () => {
    applyTheme({ "--accent": "#ffeb3b", "--accent-strong": "#000000", "--accent-text": "#111111" });
    expect(token("--accent-strong")).toBe("#000000");
    expect(token("--accent-text")).toBe("#111111");
  });

  it("drops keys and values that could break out of the rule", () => {
    applyTheme({
      "--ok": "#123456",
      color: "red",
      "--x": "red; } body { display: none",
      "--y": "</style><script>",
      "--Upper": "#000000",
    });
    expect(sheet()).toContain("--ok: #123456;");
    for (const bad of ["color: red", "display", "<", "--Upper"]) expect(sheet()).not.toContain(bad);
  });

  it("does not derive from an accent that is not #rrggbb", () => {
    applyTheme({ "--accent": "rgb(10, 20, 30)" });
    expect(sheet()).toContain("--accent: rgb(10, 20, 30);");
    expect(sheet()).not.toContain("--accent-strong");
  });

  it("replaces the previous theme, and an empty theme removes it", () => {
    applyTheme({ "--accent": "#111111" });
    applyTheme({ "--accent": "#222222" });
    expect(document.querySelectorAll("#tenant-theme")).toHaveLength(1);
    expect(sheet()).not.toContain("#111111");
    applyTheme({});
    expect(document.getElementById("tenant-theme")).toBeNull();
  });
});

describe("applyTenantConfig", () => {
  it("hands the store's marketing tags to the tag loader, and none when the config has no marketing key", () => {
    applyTenantConfig({ slug: "s", name: "Shop", theme: {}, marketing: { tags: { ga4: "G-AB12CD3" } } });
    expect(configureTracking).toHaveBeenLastCalledWith({ ga4: "G-AB12CD3" });
    applyTenantConfig({ slug: "s", name: "Shop", theme: {} });
    expect(configureTracking).toHaveBeenLastCalledWith(undefined);
  });
});
