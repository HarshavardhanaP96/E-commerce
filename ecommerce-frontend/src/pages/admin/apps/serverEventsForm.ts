import type { DeliveryStatus, Integration, Provider, ServerChange } from "../../../redux/api/marketingAPI";

/** The server's patterns (contracts.md → Marketing server events). */
export const SECRET_PATTERN = /^[A-Za-z0-9_-]{16,512}$/;
export const TEST_CODE_PATTERN = /^[A-Za-z0-9]{1,32}$/;

export type ServerDef = { idKey: string; idLabel: string; secretLabel: string; testCode: boolean; consent: string; intro: string };
export const SERVER: Record<Provider, ServerDef> = {
  google: {
    idKey: "ga4MeasurementId", idLabel: "GA4 measurement ID", secretLabel: "Measurement Protocol API secret",
    testCode: false, consent: "analytics",
    intro: "Also send each paid order to GA4 from your server, so sales count even when a browser blocks the tag. GA4 keeps one purchase per order.",
  },
  meta: {
    idKey: "pixelId", idLabel: "Meta Pixel ID", secretLabel: "Conversions API access token",
    testCode: true, consent: "marketing",
    intro: "Also send each paid order to Meta from your server, so sales count even when a browser blocks the Pixel. Meta counts a purchase once when both arrive.",
  },
};

export type ServerDraft = { enabled: boolean; secret: string; testEventCode: string };

export const serverDraftFrom = (integration?: Integration): ServerDraft => ({
  enabled: integration?.server?.enabled ?? false,
  secret: "",
  testEventCode: integration?.server?.testEventCode ?? "",
});

export const validateServer = (draft: ServerDraft, def: ServerDef, hasSecret: boolean): Record<string, string> => {
  const errors: Record<string, string> = {};
  const secret = draft.secret.trim();
  if (secret && !SECRET_PATTERN.test(secret)) errors.secret = "Paste the whole secret: 16–512 letters, digits, _ or -.";
  else if (draft.enabled && !secret && !hasSecret) errors.secret = "Paste the secret to turn on server events.";
  const code = draft.testEventCode.trim();
  if (def.testCode && code && !TEST_CODE_PATTERN.test(code)) {
    errors.testEventCode = "Use the code from Events Manager → Test events: letters and digits only.";
  }
  return errors;
};

export const toServerChange = (draft: ServerDraft, def: ServerDef): ServerChange => {
  const change: ServerChange = { enabled: draft.enabled };
  if (draft.secret.trim()) change.secret = draft.secret.trim();
  if (def.testCode) change.testEventCode = draft.testEventCode.trim() || null;
  return change;
};

/** "server.<field>: <reason>" goes under that field; "server: <reason>" and anything else at the top. */
export const serverFieldError = (message: string): { key?: "secret" | "testEventCode"; text: string } => {
  const m = /^server\.(secret|testEventCode): (.+)$/.exec(message);
  if (m) return { key: m[1] as "secret" | "testEventCode", text: m[2] };
  const text = message.replace(/^server: /, "");
  return { text: text.charAt(0).toUpperCase() + text.slice(1) };
};

export const DELIVERY_LABEL: Record<DeliveryStatus, string> = {
  PENDING: "Waiting to send", SENDING: "Sending", SENT: "Sent", FAILED: "Failed", SKIPPED: "Not sent",
};
