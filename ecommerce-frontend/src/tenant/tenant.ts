import { server } from "../redux/config";
import { BUSINESS, refreshPolicies } from "../legal/policies";
import { regional } from "../utils/money";
import { commerce } from "../utils/pricing";

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
  for (const [token, value] of Object.entries(config.theme ?? {})) {
    document.documentElement.style.setProperty(token, value);
  }
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
