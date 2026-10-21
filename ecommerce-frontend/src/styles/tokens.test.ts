import { describe, expect, it } from "vitest";
import { contrastRatio } from "../utils/contrast";
import { ADMIN, SHARED, STORE } from "./readTokens";

type Pair = [fg: string, bg: string, min: number];
const TEXT = 4.5; // body text
const UI = 3; // control outlines, focus rings

/** Pairs that fall short, with their ratio; a token name that is missing throws inside parseHex. */
const failing = (tokens: Record<string, string>, pairs: Pair[]) =>
  pairs
    .map(([fg, bg, min]) => ({ pair: `${fg} on ${bg}`, min, ratio: contrastRatio(tokens[fg] ?? fg, tokens[bg] ?? bg) }))
    .filter((r) => r.ratio < r.min)
    .map((r) => `${r.pair}: ${r.ratio.toFixed(2)} < ${r.min}`);

const textOnSurfaces = (fg: string): Pair[] => ["--bg", "--surface", "--surface-2"].map((bg) => [fg, bg, TEXT]);

const common: Pair[] = [
  ...textOnSurfaces("--text"),
  ...textOnSurfaces("--text-2"),
  ...textOnSurfaces("--text-muted"),
  ["--accent-text", "--bg", TEXT],
  ["--accent-text", "--surface", TEXT],
  ["--accent-text", "--accent-subtle", TEXT],
  ["--on-primary", "--primary", TEXT],
  ["--on-primary", "--primary-hover", TEXT],
  ["--on-accent", "--accent-strong", TEXT],
  ["--border-strong", "--surface", UI],
  ["--border-strong", "--bg", UI],
  ["--accent", "--bg", UI], // focus outline colour
];

describe("design tokens meet WCAG AA", () => {
  it("store", () => {
    expect(failing(STORE(), common)).toEqual([]);
  });

  it("admin, including the dark sidebar", () => {
    const nav: Pair[] = [
      ["--nav-text", "--nav-bg", TEXT],
      ["--nav-muted", "--nav-bg", TEXT],
      ["--nav-active", "--nav-bg", TEXT],
    ];
    expect(failing(ADMIN(), [...common, ...nav])).toEqual([]);
  });

  it("status colours on their own tints", () => {
    const pairs: Pair[] = ["success", "warning", "danger", "info"].map((s) => [`--${s}`, `--${s}-subtle`, TEXT]);
    expect(failing(SHARED(), pairs)).toEqual([]);
  });
});
