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
