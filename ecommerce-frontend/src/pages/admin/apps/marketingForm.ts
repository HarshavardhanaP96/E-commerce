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
