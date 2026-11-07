/** WCAG 2.x contrast maths for #rrggbb colours. */
const HEX = /^#([0-9a-f]{6})$/i;

export const parseHex = (hex: string): [number, number, number] => {
  const m = HEX.exec(hex.trim());
  if (!m) throw new Error(`Not a #rrggbb colour: "${hex}"`);
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
};

const channel = (v: number) => {
  const s = v / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
};

export const luminance = (hex: string) => {
  const [r, g, b] = parseHex(hex).map(channel);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};

export const contrastRatio = (a: string, b: string) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

const toHexString = (rgb: number[]) => `#${rgb.map((v) => Math.round(v).toString(16).padStart(2, "0")).join("")}`;

/**
 * `hex` if it already reaches `min` against `bg`, else the first 5% step toward black that does.
 * Meant for light backgrounds (white text on a badge, accent text on the page).
 */
export const readableOn = (hex: string, bg: string, min: number): string => {
  const rgb = parseHex(hex);
  for (let step = 0; step <= 20; step++) {
    const shade = toHexString(rgb.map((v) => v * (1 - step * 0.05)));
    if (contrastRatio(shade, bg) >= min) return shade;
  }
  return "#000000";
};

const SHORT_HEX = /^#([0-9a-f])([0-9a-f])([0-9a-f])$/i;
const RGB = /^rgba?\(\s*(\d{1,3})[\s,]+(\d{1,3})[\s,]+(\d{1,3})(?:[\s,/]+[\d.]+%?)?\s*\)$/i;
const HSL = /^hsla?\(\s*([\d.]+)(?:deg)?[\s,]+([\d.]+)%[\s,]+([\d.]+)%(?:[\s,/]+[\d.]+%?)?\s*\)$/i;

/** A store colour as #rrggbb, from #rgb, #rrggbb, rgb()/rgba() or hsl()/hsla(); null if it can't be read (e.g. a name). */
export const toHex = (value: string): string | null => {
  const v = value.trim();
  if (HEX.test(v)) return v.toLowerCase();
  const short = SHORT_HEX.exec(v);
  if (short) return `#${short.slice(1).map((c) => c + c).join("")}`.toLowerCase();
  const rgb = RGB.exec(v);
  if (rgb) {
    const parts = rgb.slice(1, 4).map(Number);
    return parts.every((n) => n <= 255) ? toHexString(parts) : null;
  }
  const hsl = HSL.exec(v);
  if (hsl) {
    const [h, s, l] = [Number(hsl[1]) % 360, Number(hsl[2]) / 100, Number(hsl[3]) / 100];
    if (s > 1 || l > 1) return null;
    const a = s * Math.min(l, 1 - l);
    const f = (n: number) => {
      const k = (n + h / 30) % 12;
      return 255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1)));
    };
    return toHexString([f(0), f(8), f(4)]);
  }
  return null;
};
