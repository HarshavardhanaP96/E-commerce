import { server } from "../redux/config";
import { BUSINESS, refreshPolicies } from "../legal/policies";
import { regional } from "../utils/money";
import { commerce } from "../utils/pricing";
import { readableOn } from "../utils/contrast";

export type StorefrontConfig = {
  logoUrl?: string | null;
  legalName?: string | null;
  address?: string | null;
  email?: string | null;
  phone?: string | null;
  grievanceOfficer?: string | null;
  grievanceEmail?: string | null;
};

export type TenantConfig = {
  slug: string;
  name: string;
  theme: Record<string, string>;
  locale?: string;
  currency?: string;
  storefront?: StorefrontConfig;
  /** Effective feature switches for this store (its own setting, else the platform default). */
  features?: Record<string, boolean>;
  /** The store's shop rules in force (its own setting, else the platform default). */
  commerce?: Partial<typeof commerce>;
  timeZone?: string;
};

/** The store this browser tab is showing. Set once, before the app renders. */
export const brand = { name: "6Pack", logoUrl: "" };

/** The store's feature switches (its own setting, else the platform default). Unknown until the config loads: on. */
export const features: Record<string, boolean> = {};

export const featureOn = (key: string) => features[key] ?? true;

const THEME_KEY = /^--[a-z0-9-]{1,40}$/;
const THEME_VALUE = /^[^;{}<>]{1,60}$/;
const HEX = /^#[0-9a-f]{6}$/i;

/** Store themes recolour the storefront only; the admin keeps its own tokens (ADR-026). */
export const STORE_THEME_SELECTOR = '[data-surface="store"]';

/**
 * Writes the store's design tokens into one <style id="tenant-theme">. Same specificity as the store
 * defaults in _tokens.scss and later in the page, so it wins there and nowhere else. Keys and values are
 * re-checked here (the API validates too). A light accent gets readable darker shades for badges and text.
 */
export const applyTheme = (theme: Record<string, string>) => {
  const tokens = Object.entries(theme).filter(
    ([key, value]) => THEME_KEY.test(key) && typeof value === "string" && THEME_VALUE.test(value)
  );
  const accent = theme["--accent"];
  if (accent && HEX.test(accent)) {
    const has = (key: string) => tokens.some(([k]) => k === key);
    if (!has("--accent-strong")) tokens.push(["--accent-strong", readableOn(accent, "#ffffff", 4.5)]);
    if (!has("--accent-text")) tokens.push(["--accent-text", readableOn(accent, "#f5f5f3", 4.5)]);
  }

  let style = document.getElementById("tenant-theme");
  if (tokens.length === 0) return style?.remove();
  if (!style) {
    style = document.createElement("style");
    style.id = "tenant-theme";
    document.head.appendChild(style);
  }
  style.textContent = `${STORE_THEME_SELECTOR} {\n${tokens.map(([k, v]) => `  ${k}: ${v};`).join("\n")}\n}`;
};

/** Applies the store's name, logo, design tokens and business details to the page. */
export const applyTenantConfig = (config: TenantConfig) => {
  brand.name = config.name || brand.name;
  brand.logoUrl = config.storefront?.logoUrl ?? "";
  Object.assign(features, config.features ?? {});
  Object.assign(commerce, config.commerce ?? {});
  if (config.currency) regional.currency = config.currency;
  if (config.locale) regional.locale = config.locale;
  if (config.timeZone) regional.timeZone = config.timeZone;
  document.title = brand.name;
  applyTheme(config.theme ?? {});
  const s = config.storefront ?? {};
  const details: Partial<typeof BUSINESS> = {
    legalName: s.legalName ?? undefined,
    address: s.address ?? undefined,
    email: s.email ?? undefined,
    phone: s.phone ?? undefined,
    grievanceOfficer: s.grievanceOfficer ?? undefined,
    grievanceEmail: s.grievanceEmail ?? undefined,
  };
  for (const [key, value] of Object.entries(details)) {
    if (value) (BUSINESS as Record<string, string>)[key] = value;
  }
  refreshPolicies(brand.name);
};

/** Fetches the config for this domain. Failing quietly keeps the built-in look rather than a blank page. */
export const loadTenantConfig = async (): Promise<void> => {
  try {
    const res = await fetch(`${server}/api/v1/tenant/config`);
    if (!res.ok) return;
    const body = (await res.json()) as { tenant: TenantConfig };
    applyTenantConfig(body.tenant);
  } catch {
    // offline or API down: render with the defaults
  }
};
