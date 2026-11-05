const UNITS = ["B", "KB", "MB", "GB", "TB"];

export const formatBytes = (n: number) => {
  let value = n;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${Number(value.toFixed(1))} ${UNITS[unit]}`;
};

export const usagePercent = (used: number, limit: number) =>
  limit <= 0 ? 0 : Math.min(100, Math.round((used / limit) * 100));

export type FeatureChoice = "default" | "on" | "off";

export const featureChoice = (features: Record<string, boolean> | undefined, key: string): FeatureChoice =>
  features?.[key] === undefined ? "default" : features[key] ? "on" : "off";

/** "default" leaves the key out, so the store follows the platform default. */
export const featuresPayload = (choices: Record<string, FeatureChoice>) =>
  Object.fromEntries(Object.entries(choices).filter(([, c]) => c !== "default").map(([k, c]) => [k, c === "on"]));

export type SizeUnit = "MB" | "GB";
const UNIT_BYTES: Record<SizeUnit, number> = { MB: 1024 ** 2, GB: 1024 ** 3 };

export const bytesFromSize = (value: string, unit: SizeUnit) =>
  value.trim() === "" ? null : Math.round(Number(value) * UNIT_BYTES[unit]);

/** GB when it is a tidy number of GB (one decimal), else MB. */
export const sizeFromBytes = (bytes: number | null | undefined): { value: string; unit: SizeUnit } => {
  if (bytes === null || bytes === undefined) return { value: "", unit: "GB" };
  const gb = bytes / UNIT_BYTES.GB;
  if (gb >= 1 && Number(gb.toFixed(1)) === gb) return { value: String(gb), unit: "GB" };
  return { value: String(Number((bytes / UNIT_BYTES.MB).toFixed(1))), unit: "MB" };
};

export const nullableInt = (value: string) => (value.trim() === "" ? null : Math.trunc(Number(value)));

export const addDomain = (domains: string[], raw: string) => {
  const host = raw.trim().toLowerCase();
  return host === "" || domains.includes(host) ? domains : [...domains, host];
};

export const ACCENT = "--accent";
