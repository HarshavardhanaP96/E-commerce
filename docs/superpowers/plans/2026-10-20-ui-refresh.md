# UI Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the storefront ("Studio shelf") and the admin panel ("Ink console") so both meet WCAG AA, look deliberate, and keep a store's own colours out of the admin panel.

**Architecture:** Most of the restyle is a token change in `src/styles/_tokens.scss`, since every component reads CSS custom properties through the Sass aliases in `_mixin.scss`. Small pure helpers carry the logic so it can be unit-tested: `utils/contrast.ts`, `fillSpan`, `cardPricing`, `deliveryLine`, `activeFilters`, `checkoutSteps` and `isFilledIn`. A store's theme moves from `<html>` inline style into a store-scoped `<style>` element.

**Tech Stack:** React 18 + TypeScript, Sass, Vite 5, Vitest 3.2.4 (+ jsdom 25.0.1 for one DOM test), react-router 6, Chart.js 4, react-icons (`lu`).

**Spec:** `docs/superpowers/specs/2026-10-20-ui-refresh-design.md` (approved; §9 lists the corrections this plan made).

**Worktree:** `../6Pack-ui`, branch `feat/ui-refresh` off `dev` 2d7fc2a. All paths below are relative to `ecommerce-frontend/` unless they start with `docs/`.

## Global Constraints

- Every text/background pair in both token sets ≥ 4.5:1; control outlines and focus indicators ≥ 3:1 (spec §1.1).
- A store's theme never changes admin tokens (spec §1.2).
- No all-caps labels: `text-transform: uppercase` must not appear in `src/styles` after Task 1 (spec §1.5).
- Never hardcode a hex in a component `.scss` file except inside `_tokens.scss`; Chart.js colours live only in `components/admin/chartTheme.ts`.
- No new fonts, no backend changes, no dark mode, `/platform` untouched (spec §1, out of scope).
- Copy: sentence case, active verbs, an action keeps its name through the flow ("Add to cart" → toast "Added to cart").
- Each task ends with `npm test`, `npm run lint` and `npx tsc --noEmit` all green before its commit.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Store theme with a light brand colour** (e.g. `--accent: #ffeb3b`): badges and accent links must still be readable. Pinned by the `tenant.test.ts` cases in Task 2.
2. **Unfilled business details:** the footer must not print `[Registered address]`-style placeholders when a store hasn't filled them in. Pinned by `policies.test.ts` in Task 8.
3. **Odd product counts on the home grid** (0, 1, exactly a full row): no orphan card, and the "View all" tile never appears without products. Pinned by `fillSpan` tests in Task 4.
4. **Admin route opened directly** (hard refresh on `/admin/analytics` for a store with a theme): no flash of store colours, because `data-surface` is set before the first render. Pinned by `surfaceFor` tests in Task 2 plus a manual hard-refresh check in Task 15.
5. **Keyboard users:** the icon rail, filter sheet and category chips must be reachable and named. Checked in the Task 15 keyboard pass (no component test harness exists).

---

### Task 1: Colour tokens and contrast fixes (shared)

**Files:**
- Create: `src/utils/contrast.ts`
- Create: `src/styles/readTokens.ts`
- Create: `src/utils/contrast.test.ts`
- Create: `src/styles/tokens.test.ts`
- Modify: `src/styles/_tokens.scss` (store and admin blocks)
- Modify: `src/styles/admin-styles/_mixin.scss` (`@mixin overline`)
- Modify: `src/styles/_home.scss` (`.eyebrow`, `.product-card__tag`)
- Modify: `src/styles/app.scss` (`.header__count`)
- Modify: `index.html` (`theme-color`)

**Interfaces:**
- Produces: `parseHex(hex: string): [number, number, number]` (throws on anything but `#rrggbb`), `luminance(hex: string): number`, `contrastRatio(a: string, b: string): number` from `src/utils/contrast.ts`.
- Produces: `tokenBlock(selector: string): Record<string, string>`, `SHARED()`, `STORE()`, `ADMIN()` from `src/styles/readTokens.ts` (test helper; reads `_tokens.scss?raw`).
- Produces new tokens on both surfaces: `--accent-strong`, `--on-accent`; store also `--hero-from`, `--hero-to`; admin also `--nav-bg`, `--nav-text`, `--nav-muted`, `--nav-active`.

- [ ] **Step 0: Install dependencies in the worktree**

```bash
cd ../6Pack-ui/ecommerce-frontend && npm ci
```
Expected: installs without errors; `npm test` runs the existing suites green.

- [ ] **Step 1: Write the failing contrast-maths test**

`src/utils/contrast.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { contrastRatio, parseHex } from "./contrast";

describe("contrast maths", () => {
  it("gives 21:1 for black on white and 1:1 for a colour on itself, in either order", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 5);
    expect(contrastRatio("#ffffff", "#000000")).toBeCloseTo(21, 5);
    expect(contrastRatio("#e4572e", "#e4572e")).toBeCloseTo(1, 5);
  });

  it("matches a known pair: white on coral #e4572e is 3.68:1", () => {
    expect(contrastRatio("#ffffff", "#e4572e")).toBeCloseTo(3.68, 2);
  });

  it("accepts upper case and rejects anything that is not #rrggbb", () => {
    expect(parseHex("#E4572E")).toEqual([228, 87, 46]);
    for (const bad of ["red", "#fff", "", "#12345g", "--text"]) {
      expect(() => parseHex(bad)).toThrow(/#rrggbb/);
    }
  });
});
```

- [ ] **Step 2: Run it and see it fail**

Run: `npx vitest run src/utils/contrast.test.ts`
Expected: FAIL, "Failed to resolve import ./contrast".

- [ ] **Step 3: Implement `src/utils/contrast.ts`**

```ts
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
```

- [ ] **Step 4: Run it and see it pass**

Run: `npx vitest run src/utils/contrast.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the token reader and the failing token-pair test**

`src/styles/readTokens.ts`:
```ts
// Test helper: reads the custom properties declared in _tokens.scss, so tests check the real values.
import SOURCE from "./_tokens.scss?raw";

/** The `--name: value` pairs in the block whose selector is exactly `selector` (starting a line). */
export const tokenBlock = (selector: string): Record<string, string> => {
  const start = SOURCE.indexOf(`\n${selector} {`);
  if (start < 0) throw new Error(`No token block for ${selector}`);
  const body = SOURCE.slice(start, SOURCE.indexOf("\n}", start));
  return Object.fromEntries([...body.matchAll(/(--[a-z0-9-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
};

export const SHARED = () => tokenBlock(":root");
export const STORE = () => tokenBlock(':root,\n[data-surface="store"]');
export const ADMIN = () => tokenBlock('[data-surface="admin"]');
```

`src/styles/tokens.test.ts`:
```ts
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
```

- [ ] **Step 6: Run it and see it fail for the right reason**

Run: `npx vitest run src/styles/tokens.test.ts`
Expected: FAIL. The store and admin cases throw `Not a #rrggbb colour: "--on-accent"` (the token doesn't exist yet). After you add only the new tokens, the remaining failures are `--text-muted on --surface-2` and `--border-strong on --surface`. The status case passes.

- [ ] **Step 7: Replace the store and admin blocks in `src/styles/_tokens.scss`**

Replace everything from `// ---------- STORE: warm, editorial, generous ----------` down to the closing `}` of the admin block with:

```scss
// ---------- STORE: "Studio shelf". Photos carry the colour; chrome is ink and warm grey ----------
:root,
[data-surface="store"] {
  --bg: #f5f5f3; // studio backdrop
  --surface: #ffffff;
  --surface-2: #ececea; // photo mat, chips, skeletons
  --border: #e2e0dc; // decorative dividers only
  --border-strong: #8a837d; // control outlines: 3.73:1 on white

  --text: #1c1917;
  --text-2: #57534e; // 6.99:1 on bg
  --text-muted: #6b6560; // 4.86:1 on surface-2

  --accent: #e4572e; // brand dot, range thumb, focus ring: large marks only
  --accent-hover: #c2410c;
  --accent-strong: #c2410c; // badge / count backgrounds: white on it 5.18:1
  --on-accent: #ffffff;
  --accent-text: #c2410c; // links and small accent text
  --accent-subtle: #fff1ec;

  --primary: #1c1917; // main CTA is ink
  --primary-hover: #3a3532;
  --on-primary: #ffffff;
  --ring: rgba(228, 87, 46, 0.35);

  // The one gradient: the home banner, tinted from the store's own accent.
  --hero-from: color-mix(in srgb, var(--accent) 16%, var(--surface));
  --hero-to: var(--bg);

  --font-display: "Bricolage Grotesque Variable", "Bricolage Grotesque", var(--font-sans);
  --weight-heading: 700;
  --tracking-heading: -0.02em;

  // type scale (16px base, fluid headings)
  --fs-caption: 0.8125rem; // 13
  --fs-body-sm: 0.875rem; // 14
  --fs-body: 1rem; // 16
  --fs-label: 0.9375rem; // 15
  --fs-h4: 1.25rem; // 20
  --fs-h3: 1.5rem; // 24
  --fs-h2: clamp(1.75rem, 1.35rem + 1.2vw, 2.25rem); // 28 → 36
  --fs-h1: clamp(2.25rem, 1.4rem + 3vw, 3.5rem); // 36 → 56

  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 20px;

  --shadow-sm: 0 1px 2px rgba(28, 25, 23, 0.06);
  --shadow-md: 0 6px 20px -4px rgba(28, 25, 23, 0.1);
  --shadow-lg: 0 18px 40px -12px rgba(28, 25, 23, 0.18);

  --control-h: 2.75rem;
  --header-h: 4rem;
  // Page gutter: header, content and footer all share the 1200px column edge.
  --gutter: max(1.5rem, calc((100vw - 1200px) / 2 + 1.5rem));
}

// ---------- ADMIN: "Ink console". Petrol sidebar, calm work area, one peacock action colour ----------
[data-surface="admin"] {
  --bg: #f4f6f6;
  --surface: #ffffff;
  --surface-2: #eaeeee;
  --border: #dbe1e1;
  --border-strong: #7d8a8c; // control outlines: 3.57:1 on white

  --text: #102227;
  --text-2: #44565a;
  --text-muted: #5b6b6e; // 4.76:1 on surface-2

  --accent: #0b6a88; // peacock
  --accent-hover: #0b5f7a;
  --accent-strong: #0b6a88;
  --on-accent: #ffffff;
  --accent-text: #0b5f7a;
  --accent-subtle: #e6f2f6;

  --primary: #0b6a88;
  --primary-hover: #0b5f7a;
  --on-primary: #ffffff;
  --ring: rgba(11, 106, 136, 0.35);

  --nav-bg: #0f2430;
  --nav-text: #c9d6d8; // 10.7:1 on nav
  --nav-muted: #8fa3a6; // 6.05:1
  --nav-active: #67c6e0; // active bar and icon, 8.16:1

  --font-display: var(--font-sans);
  --weight-heading: 600;
  --tracking-heading: -0.01em;

  // type scale (14px body, fixed)
  --fs-caption: 0.75rem; // 12
  --fs-body-sm: 0.8125rem; // 13
  --fs-body: 0.875rem; // 14
  --fs-label: 0.8125rem; // 13
  --fs-h4: 1rem; // 16
  --fs-h3: 1.125rem; // 18
  --fs-h2: 1.375rem; // 22
  --fs-h1: 1.75rem; // 28

  --radius-sm: 6px; // controls
  --radius-md: 8px;
  --radius-lg: 10px; // cards

  // Flat cards; shadows only on floating layers (menus, drawer, toasts).
  --shadow-sm: none;
  --shadow-md: 0 4px 12px rgba(15, 36, 48, 0.1);
  --shadow-lg: 0 12px 32px rgba(15, 36, 48, 0.16);

  --control-h: 2.25rem;
  --header-h: 3.5rem;
  --gutter: 1.5rem;
}
```

- [ ] **Step 8: Remove uppercase labels and fix the coral badges**

In `src/styles/admin-styles/_mixin.scss`, replace the `overline` mixin and its comment:
```scss
// Small label above a group (sidebar sections, filter names, KPI labels). Sentence case, never tracked.
@mixin overline {
  font-size: var(--fs-label);
  line-height: 1.3;
  font-weight: 600;
  letter-spacing: 0;
  color: $color-text-secondary;
}
```

In `src/styles/_home.scss`:
- `.eyebrow { … color: $color-accent-primary; }` becomes `color: $color-accent-text;` (coral on the backdrop is 3.53:1, too low for small text).
- In `.product-card__tag`, `background: $color-accent-primary; color: #fff;` becomes `background: var(--accent-strong); color: var(--on-accent);`.

In `src/styles/app.scss`, `.header__count`: `background: $color-accent-primary; color: #fff;` becomes `background: var(--accent-strong); color: var(--on-accent);`.

In `index.html`: `<meta name="theme-color" content="#fafaf9" />` becomes `content="#f5f5f3"`.

Then check that nothing is still uppercased:
```bash
grep -rn "uppercase" src/styles
```
Expected: no output. If the search filter `b` rule still has `text-transform: none`, leave it; it's harmless.

- [ ] **Step 9: Run the tests and see them pass**

Run: `npm test && npm run lint && npx tsc --noEmit`
Expected: all green, including `tokens.test.ts` (3) and `contrast.test.ts` (3).

- [ ] **Step 10: Commit**

```bash
git add index.html src/utils/contrast.ts src/utils/contrast.test.ts src/styles
git commit -m "style(ui): AA colour tokens for store and admin, sentence-case labels

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Store theme applies only to the storefront

**Files:**
- Modify: `src/utils/contrast.ts` (add `readableOn`)
- Modify: `src/utils/contrast.test.ts`
- Modify: `src/utils/routes.ts` (add `surfaceFor`)
- Create: `src/utils/routes.test.ts`
- Modify: `src/tenant/tenant.ts` (add `applyTheme`, use it in `applyTenantConfig`)
- Create: `src/tenant/tenant.test.ts`
- Modify: `src/main.tsx`, `src/App.tsx` (`SurfaceScope`)
- Modify: `package.json` (dev dependency `jsdom@25.0.1`)

**Interfaces:**
- Consumes: `contrastRatio`, `parseHex` (Task 1).
- Produces: `readableOn(hex: string, bg: string, min: number): string` (lower-case `#rrggbb`), `surfaceFor(pathname: string): "store" | "admin"`, `applyTheme(theme: Record<string, string>): void`, `STORE_THEME_SELECTOR = '[data-surface="store"]'`.

- [ ] **Step 1: Write the failing `readableOn` and `surfaceFor` tests**

Append to `src/utils/contrast.test.ts`:
```ts
import { readableOn, luminance } from "./contrast";

describe("readableOn", () => {
  it("returns the colour itself (lower-cased) when it already passes", () => {
    expect(readableOn("#1C1917", "#ffffff", 4.5)).toBe("#1c1917");
  });

  it("darkens a colour that fails until it passes", () => {
    for (const hex of ["#e4572e", "#ffeb3b", "#22c55e"]) {
      const out = readableOn(hex, "#ffffff", 4.5);
      expect(contrastRatio(out, "#ffffff")).toBeGreaterThanOrEqual(4.5);
      expect(luminance(out)).toBeLessThan(luminance(hex));
    }
  });

  it("rejects a value that is not #rrggbb", () => {
    expect(() => readableOn("tomato", "#ffffff", 4.5)).toThrow(/#rrggbb/);
  });
});
```
(Merge the new imports into the file's existing import line from `./contrast`.)

`src/utils/routes.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { surfaceFor } from "./routes";

describe("surfaceFor", () => {
  it("is admin for /admin and anything under it", () => {
    expect(surfaceFor("/admin")).toBe("admin");
    expect(surfaceFor("/admin/analytics")).toBe("admin");
  });

  it("is store everywhere else, including look-alike paths", () => {
    for (const p of ["/", "/search", "/administrator", "/platform"]) expect(surfaceFor(p)).toBe("store");
  });
});
```

- [ ] **Step 2: Run them and see them fail**

Run: `npx vitest run src/utils`
Expected: FAIL, `readableOn` and `surfaceFor` are not exported.

- [ ] **Step 3: Implement**

Append to `src/utils/contrast.ts`:
```ts
const toHex = (rgb: number[]) => `#${rgb.map((v) => Math.round(v).toString(16).padStart(2, "0")).join("")}`;

/**
 * `hex` if it already reaches `min` against `bg`, else the first 5% step toward black that does.
 * Meant for light backgrounds (white text on a badge, accent text on the page).
 */
export const readableOn = (hex: string, bg: string, min: number): string => {
  const rgb = parseHex(hex);
  for (let step = 0; step <= 20; step++) {
    const shade = toHex(rgb.map((v) => v * (1 - step * 0.05)));
    if (contrastRatio(shade, bg) >= min) return shade;
  }
  return "#000000";
};
```

Add to `src/utils/routes.ts`:
```ts
/** Which token set (see _tokens.scss) a path uses. Only /admin and its children are admin. */
export const surfaceFor = (pathname: string): "store" | "admin" =>
  pathname === "/admin" || pathname.startsWith("/admin/") ? "admin" : "store";
```

- [ ] **Step 4: Run them and see them pass**

Run: `npx vitest run src/utils`
Expected: PASS.

- [ ] **Step 5: Add jsdom and write the failing theme test**

```bash
npm i -D jsdom@25.0.1
```

`src/tenant/tenant.test.ts`:
```ts
// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { contrastRatio } from "../utils/contrast";
import { applyTheme } from "./tenant";

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
```

- [ ] **Step 6: Run it and see it fail**

Run: `npx vitest run src/tenant`
Expected: FAIL, `applyTheme` is not exported.

- [ ] **Step 7: Implement `applyTheme` in `src/tenant/tenant.ts`**

Add the import `import { readableOn } from "../utils/contrast";` and, above `applyTenantConfig`:
```ts
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
```
In `applyTenantConfig`, replace
```ts
  for (const [token, value] of Object.entries(config.theme ?? {})) {
    document.documentElement.style.setProperty(token, value);
  }
```
with
```ts
  applyTheme(config.theme ?? {});
```

- [ ] **Step 8: Set the surface before the first render**

`src/main.tsx`: import `surfaceFor` from `./utils/routes.ts` and, before `loadTenantConfig()`, add:
```ts
// Tag the surface before anything paints, so a hard refresh on /admin never shows store colours.
document.documentElement.dataset.surface = surfaceFor(window.location.pathname);
```
`src/App.tsx`, `SurfaceScope`: replace the inline ternary with `surfaceFor(pathname)` (import from `./utils/routes`).

- [ ] **Step 9: Run everything and see it pass**

Run: `npm test && npm run lint && npx tsc --noEmit`
Expected: all green.

- [ ] **Step 10: Commit**

```bash
git add package.json package-lock.json src/utils src/tenant src/main.tsx src/App.tsx
git commit -m "fix(ui): store themes recolour the storefront only, with readable accent shades

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Product card

**Files:**
- Modify: `src/utils/variants.ts` (add `cardPricing`)
- Create: `src/utils/variants.test.ts`
- Modify: `src/components/productCard.tsx`
- Modify: `src/styles/_home.scss` (`.product-card` block and its ≤480px overrides)

**Interfaces:**
- Consumes: `discountPercent(price, mrp)`, `hasVariants(p)`, `priceRange(p)` (existing in `utils/variants.ts`).
- Produces: `cardPricing(p: Product): { price: number; from: boolean; mrp: number | null; off: number }`.

- [ ] **Step 1: Write the failing test**

`src/utils/variants.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { Product } from "../types/types";
import { cardPricing, discountPercent } from "./variants";

const base = { _id: "p1", name: "Shoe", photo: "", category: "shoes", stock: 5, price: 4999 } as Product;

describe("discountPercent", () => {
  it("rounds the saving against MRP", () => {
    expect(discountPercent(4999, 6499)).toBe(23);
  });
  it("is 0 when there is no saving or no MRP", () => {
    expect(discountPercent(4999, 4999)).toBe(0);
    expect(discountPercent(4999, 3000)).toBe(0);
    expect(discountPercent(4999, null)).toBe(0);
    expect(discountPercent(4999)).toBe(0);
  });
});

describe("cardPricing", () => {
  it("shows MRP and % off for a plain product sold below MRP", () => {
    expect(cardPricing({ ...base, mrp: 6499 })).toEqual({ price: 4999, from: false, mrp: 6499, off: 23 });
  });
  it("hides MRP when it is missing or not above the price", () => {
    expect(cardPricing({ ...base, mrp: null })).toEqual({ price: 4999, from: false, mrp: null, off: 0 });
    expect(cardPricing({ ...base, mrp: 4999 })).toEqual({ price: 4999, from: false, mrp: null, off: 0 });
  });
  it("shows 'from' the lowest variant price and no MRP when variants differ in price", () => {
    const p = {
      ...base,
      mrp: 9999,
      variants: { axes: [], items: [
        { id: "a", sku: "A", label: "M", options: [], price: 1500, stock: 2, status: "ACTIVE" },
        { id: "b", sku: "B", label: "L", options: [], price: 1200, stock: 2, status: "ACTIVE" },
      ] },
    } as unknown as Product;
    expect(cardPricing(p)).toEqual({ price: 1200, from: true, mrp: null, off: 0 });
  });
});
```
(`priceRange` only counts variants with `status: "ACTIVE"`; `hasVariants` only needs a non-empty `items`.)

- [ ] **Step 2: Run it and see it fail**

Run: `npx vitest run src/utils/variants.test.ts`
Expected: the `discountPercent` cases PASS (existing behaviour), and the `cardPricing` cases FAIL with "cardPricing is not a function".

- [ ] **Step 3: Implement `cardPricing` in `src/utils/variants.ts`**

```ts
/** What a product card shows: the price (lowest variant's, marked "from" when variants differ), and MRP/% off for plain products. */
export const cardPricing = (p: Product) => {
  if (hasVariants(p)) {
    const { min, max } = priceRange(p);
    return { price: min, from: min !== max, mrp: null, off: 0 };
  }
  const off = discountPercent(p.price, p.mrp);
  return { price: p.price, from: false, mrp: off > 0 ? (p.mrp as number) : null, off };
};
```

- [ ] **Step 4: Run it and see it pass**

Run: `npx vitest run src/utils/variants.test.ts`
Expected: PASS.

- [ ] **Step 5: Update `src/components/productCard.tsx`**

- Replace `const range = priceRange(product);` with `const pricing = cardPricing(product);`. Drop the `priceRange` import and import `cardPricing`.
- Inside `.product-card__media`, before the main `<img>`, add the backdrop (same URL, so the browser reuses the download):
```tsx
<img className="product-card__backdrop" src={transformImage(photo)} alt="" aria-hidden loading="lazy" />
```
- Replace the price paragraph with:
```tsx
<p className="product-card__price">
  {pricing.from && <small>From </small>}
  {formatMoney(pricing.price)}
  {pricing.mrp && (
    <>
      <s aria-label={`MRP ${formatMoney(pricing.mrp)}`}>{formatMoney(pricing.mrp)}</s>
      <span className="product-card__off">{pricing.off}% off</span>
    </>
  )}
</p>
```

- [ ] **Step 6: Restyle the card in `src/styles/_home.scss`**

Replace the `&__media` block and the `h3` rule inside `&__body`, and add `&__off` plus the `s` style, as below. Remove `mix-blend-mode` and the image padding. The ≤480px `.product-card__media img { padding: 0.75rem; }` override goes away.
```scss
  // Every photo is shown whole: contained on a blurred, enlarged copy of itself, so the tile is always
  // filled with the photo's own colour (seeded photos are 3:2, 16:9, 4:5, 2:3 and 1:1; cover would crop products).
  &__media {
    position: relative;
    aspect-ratio: 1;
    background: $color-bg-tertiary;
    overflow: hidden;
    isolation: isolate;

    img:not(.product-card__backdrop) {
      position: relative;
      width: 100%;
      height: 100%;
      object-fit: contain;
      transition: transform 400ms var(--ease);
    }
  }

  &__backdrop {
    position: absolute;
    inset: 0;
    z-index: -1;
    width: 100%;
    height: 100%;
    object-fit: cover;
    filter: blur(24px) saturate(1.2);
    transform: scale(1.25);
    opacity: 0.85;
  }
```
`&__body h3`: replace `white-space: nowrap; overflow: hidden; text-overflow: ellipsis;` with:
```scss
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      min-height: calc(2 * 1.55em); // two lines, so prices line up across a row
      color: $color-text-primary;
```
Add after `&__price`:
```scss
  &__price s {
    margin-left: $spacing-sm;
    @include text(body-sm);
    color: $color-text-muted;
  }

  &__off {
    margin-left: $spacing-xs;
    @include text(body-sm);
    font-weight: 600;
    color: $color-success;
  }
```
`&__add`: `@include square(2.5rem)` becomes `@include square(2.75rem)` (44px target). In the ≤480px block it becomes `@include square(2.75rem)` too. The `:hover:not(:disabled)` background `$color-accent-primary` becomes `var(--primary-hover)`, because white on coral fails.
In the `:hover` rule of `.product-card`, change `img { transform: scale(1.04); }` to `img:not(.product-card__backdrop) { transform: scale(1.04); }`.

- [ ] **Step 7: Check in the browser**

Run `npm run dev` (it proxies to the backend on :8080) and open `http://localhost:5173/search` at 1366px and 390px. Expected: no letterboxed grey bands, the shoe and speaker fully visible, two-line names, MRP struck through on "Air Runner Red" with "23% off".

- [ ] **Step 8: Run checks and commit**

Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/utils/variants.ts src/utils/variants.test.ts src/components/productCard.tsx src/styles/_home.scss
git commit -m "feat(store): product cards show whole photos, two-line names, MRP and % off

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Home: shelf banner, category chips, "View all" tile

**Files:**
- Create: `src/utils/grid.ts`, `src/utils/grid.test.ts`
- Modify: `src/pages/home.tsx`
- Modify: `src/pages/search.tsx` (read `?category=` on load)
- Modify: `src/styles/_home.scss` (`.hero`, new `.category-chips`, `.product-grid--home`, `.view-all-tile`)
- Delete: `src/assets/hero.webp` (only if `grep -rn "hero.webp" src` shows no other user)

**Interfaces:**
- Produces: `fillSpan(count: number, columns: number): number`.
- Consumes: `useLatestProductsQuery`, `useCategoriesQuery` (existing; categories are `string[]`).

- [ ] **Step 1: Write the failing test**

`src/utils/grid.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { fillSpan } from "./grid";

describe("fillSpan: columns the 'View all' tile spans after `count` cards", () => {
  it("fills the rest of a short last row", () => {
    expect(fillSpan(5, 4)).toBe(3);
    expect(fillSpan(5, 3)).toBe(1);
    expect(fillSpan(1, 2)).toBe(1);
  });
  it("takes a whole row of its own when the cards fill their rows exactly", () => {
    expect(fillSpan(8, 4)).toBe(4);
    expect(fillSpan(6, 3)).toBe(3);
  });
  it("rejects a column count below 1", () => {
    expect(() => fillSpan(3, 0)).toThrow();
  });
});
```

- [ ] **Step 2: Run it and see it fail**

Run: `npx vitest run src/utils/grid.test.ts`. Expected: FAIL (module missing).

- [ ] **Step 3: Implement `src/utils/grid.ts`**

```ts
/** How many columns a trailing tile spans so the grid's last row is never left short. */
export const fillSpan = (count: number, columns: number) => {
  if (columns < 1) throw new Error("columns must be at least 1");
  const rest = count % columns;
  return rest === 0 ? columns : columns - rest;
};
```

- [ ] **Step 4: Run it and see it pass**

Run: `npx vitest run src/utils/grid.test.ts`. Expected: PASS.

- [ ] **Step 5: Rewrite the banner and grid in `src/pages/home.tsx`**

Remove the `hero` import. Fix the toast copy while here: "Out of Stock" becomes "Out of stock", and "Added to Cart" becomes "Added to cart". Replace the JSX from `<section className="hero">` to the end of the product grid with:
```tsx
const products = data?.products ?? [];
const shelf = products.filter((p) => p.photo).slice(0, 3);
const spans = {
  "--span-2": fillSpan(products.length, 2),
  "--span-3": fillSpan(products.length, 3),
  "--span-4": fillSpan(products.length, 4),
} as CSSProperties;
```
```tsx
<section className="hero" aria-labelledby="hero-title">
  <div className="hero__copy">
    <h1 id="hero-title">{brand.name}</h1>
    <p>New products every week, picked for everyday use.</p>
    <Link to="/search" className="hero__cta">
      Shop all products <LuArrowRight aria-hidden />
    </Link>
  </div>
  <div className="hero__shelf" aria-hidden>
    {shelf.map((p) => (
      <img key={p._id} src={transformImage(p.photo)} alt="" />
    ))}
  </div>
</section>

{categories && categories.categories.length > 0 && (
  <nav className="category-chips" aria-label="Shop by category">
    {categories.categories.map((c) => (
      <Link key={c} className="chip" to={`/search?category=${encodeURIComponent(c)}`}>
        {c.charAt(0).toUpperCase() + c.slice(1)}
      </Link>
    ))}
  </nav>
)}

<header className="section-head">
  <h2>Latest products</h2>
</header>

<section className="product-grid product-grid--home" style={spans} aria-label="Latest products">
  {isLoading ? (
    Array.from({ length: 4 }, (_, i) => <div key={i} className="product-card product-card--skeleton" />)
  ) : products.length ? (
    <>
      {products.map((i) => <ProductCard key={i._id} product={i} handler={addToCartHandler} />)}
      <Link to="/search" className="view-all-tile">
        View all products <LuArrowRight aria-hidden />
      </Link>
    </>
  ) : (
    <div className="empty-state">
      <LuPackageOpen />
      <p>No products yet. Check back soon.</p>
    </div>
  )}
</section>
```
Add `const { data: categories } = useCategoriesQuery();`. Add the imports `CSSProperties` (react), `useCategoriesQuery`, `fillSpan` and `transformImage` (`../utils/features`). The old `<main className="product-grid">` was nested inside the app's content area. It becomes a `<section>`, and the header's "View all" link is replaced by the tile.

- [ ] **Step 6: Let search start from `?category=`**

In `src/pages/search.tsx`: `import { useSearchParams } from "react-router-dom";`, then
```ts
const [params] = useSearchParams();
const [category, setCategory] = useState(params.get("category") ?? "");
```

- [ ] **Step 7: Styles in `src/styles/_home.scss`**

Replace the whole `.hero` block (and the `.hero` part of the ≤860px media query) with:
```scss
.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: $spacing-xl;
  min-height: 24rem;
  margin-bottom: $spacing-xl;
  padding: $spacing-2xl $spacing-3xl;
  border-radius: $radius-lg;
  background: linear-gradient(135deg, var(--hero-from), var(--hero-to) 70%);

  &__copy {
    @include flex(column, center, flex-start, $spacing-md);

    h1 {
      @include text(h1);
      font-variation-settings: "opsz" 96;
      max-width: 12ch;
    }

    > p {
      font-size: 1.125rem;
      color: $color-text-secondary;
      max-width: 36ch;
    }
  }

  &__cta {
    @include button(primary);
    margin-top: $spacing-sm;
    height: 3rem;
    padding: 0 1.5rem;

    svg {
      transition: transform $transition-fast;
    }

    &:hover svg {
      transform: translateX(3px);
    }
  }

  // Up to three of the newest product photos, overlapped like items on a shelf.
  &__shelf {
    position: relative;
    height: 20rem;

    img {
      position: absolute;
      width: 13rem;
      aspect-ratio: 4 / 5;
      object-fit: cover;
      border: 4px solid var(--surface);
      border-radius: $radius-md;
      box-shadow: $shadow-lg;
    }

    img:nth-child(1) { left: 0; top: 2.5rem; transform: rotate(-4deg); }
    img:nth-child(2) { left: 30%; top: 0; z-index: 1; }
    img:nth-child(3) { right: 0; top: 3.5rem; transform: rotate(5deg); }
  }
}

.category-chips {
  @include flex(row, flex-start, center, $spacing-sm);
  flex-wrap: wrap;
  margin-bottom: $spacing-2xl;

  .chip {
    display: inline-flex;
    align-items: center;
    color: $color-text-primary;
    text-decoration: none;
  }
}

.product-grid--home {
  grid-template-columns: repeat(2, minmax(0, 1fr));

  .view-all-tile { grid-column: span var(--span-2); }

  @media (min-width: 720px) {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    .view-all-tile { grid-column: span var(--span-3); }
  }

  @media (min-width: 1024px) {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    .view-all-tile { grid-column: span var(--span-4); }
  }
}

.view-all-tile {
  @include flex(row, center, center, $spacing-sm);
  min-height: 8rem;
  border: 1px dashed $color-border-hover;
  border-radius: $radius-lg;
  @include text(label);
  color: $color-text-primary;

  &:hover {
    background: $color-bg-secondary;
    color: $color-accent-text;
  }
}
```
In the ≤860px media query, replace the old `.hero` rules with:
```scss
  .hero {
    grid-template-columns: 1fr;
    min-height: 0;
    padding: $spacing-xl $spacing-lg;

    &__shelf {
      height: 12rem;

      img { width: 8.5rem; }
    }
  }
```

- [ ] **Step 8: Check in the browser, then run checks and commit**

At 1366px: the banner is light with the coral tint, shows three overlapped product photos, the chips sit below it, and the grid has no orphan card. At 390px: the banner stacks and the photos stay inside it. Clicking a chip opens search filtered to that category.
Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add -A src
git commit -m "feat(store): home banner shows the newest products, category chips, no orphan cards

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Product page: shared column, delivery line, sticky buy bar

**Files:**
- Modify: `src/utils/pricing.ts` (add `deliveryLine`)
- Modify: `src/utils/pricing.test.ts`
- Modify: `src/pages/product.tsx`
- Modify: `src/styles/_product.scss`

**Interfaces:**
- Consumes: `commerce` (`{ shippingFee, freeShippingAbove, … }` in `utils/pricing.ts`), `formatMoney` (`utils/features`). Free shipping applies when `subtotal > freeShippingAbove` (strictly greater; see `cartTotals`).
- Produces: `deliveryLine(rules: { shippingFee: number; freeShippingAbove: number }, money: (n: number) => string): string`.

- [ ] **Step 1: Write the failing test** (append to `src/utils/pricing.test.ts`; add `deliveryLine` to its import)

```ts
describe("deliveryLine", () => {
  const money = (n: number) => `₹${n}`;
  it("says delivery is free when there is no fee", () => {
    expect(deliveryLine({ shippingFee: 0, freeShippingAbove: 1000 }, money)).toBe("Free delivery on every order.");
  });
  it("names the fee and the threshold, matching the strictly-over rule", () => {
    expect(deliveryLine({ shippingFee: 200, freeShippingAbove: 1000 }, money))
      .toBe("Free delivery on orders over ₹1000, otherwise ₹200.");
  });
});
```

- [ ] **Step 2: Run it and see it fail.** Run: `npx vitest run src/utils/pricing.test.ts`. Expected: FAIL, `deliveryLine` is not exported.

- [ ] **Step 3: Implement** (in `src/utils/pricing.ts`)

```ts
/** One line for the product page, using the same rule as cartTotals (free when the subtotal is over the threshold). */
export const deliveryLine = (
  rules: { shippingFee: number; freeShippingAbove: number },
  money: (n: number) => string
) =>
  rules.shippingFee === 0
    ? "Free delivery on every order."
    : `Free delivery on orders over ${money(rules.freeShippingAbove)}, otherwise ${money(rules.shippingFee)}.`;
```

- [ ] **Step 4: Run it and see it pass.** Run: `npx vitest run src/utils/pricing.test.ts`. Expected: PASS.

- [ ] **Step 5: Page changes**

`src/pages/product.tsx`: after the `product-info__incart` paragraph, add
```tsx
<div className="product-info__delivery">
  <LuTruck aria-hidden />
  <p>
    {deliveryLine(commerce, formatMoney)}{" "}
    <Link to={policyPath("refunds")}>Returns and refunds</Link>
  </p>
</div>
```
Add the imports: `LuTruck` (react-icons/lu), `commerce` and `deliveryLine` (`../utils/pricing`), and `policyPath` (`../legal/policies`).

`src/styles/_product.scss`:
- `.product-page`: replace `max-width: 1200px; margin: 0 auto; padding: 1.5rem var(--gutter) 3rem;` with `padding: 1.5rem var(--gutter) 3rem;`. The gutter already centres the 1200px column, and the extra max-width was shrinking it to about 986px.
- Add:
```scss
.product-info__delivery {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
  padding: 0.875rem 1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface);
  font-size: var(--fs-body-sm);
  color: var(--text-2);

  svg {
    flex: none;
    margin-top: 0.15rem;
    font-size: 1.15rem;
    color: var(--text);
  }
}

// Phones: keep quantity + "Add to cart" in reach, just above the bottom menu.
@media (max-width: 640px) {
  .product-info__buy {
    position: sticky;
    bottom: calc(var(--bottom-nav-h, 0px) + env(safe-area-inset-bottom) + 0.5rem);
    z-index: 5;
    padding: 0.5rem;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: color-mix(in srgb, var(--surface) 92%, transparent);
    backdrop-filter: blur(10px);
    box-shadow: var(--shadow-md);
  }
}
```

- [ ] **Step 6: Browser check, checks, commit**

At 1366px the "All products" link and the photo's left edge line up with the header logo. At 390px the buy bar stays visible above the bottom menu while scrolling the details.
Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/utils/pricing.ts src/utils/pricing.test.ts src/pages/product.tsx src/styles/_product.scss
git commit -m "feat(store): product page uses the shared column, shows delivery terms, sticky buy bar on phones

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Search: sort chips, price input, active filters, phone filter panel

**Files:**
- Create: `src/utils/filters.ts`, `src/utils/filters.test.ts`
- Modify: `src/pages/search.tsx`
- Modify: `src/styles/_search.scss`

**Interfaces:**
- Produces: `MAX_PRICE = 100000`, `SORTS: { value: "" | "asc" | "dsc"; label: string }[]`, and `activeFilters(state: { search: string; category: string; maxPrice: number; picked: Record<string, string> }, money: (n: number) => string): { key: string; label: string }[]`. Keys: `"search"`, `"category"`, `"price"`, and `"option:<name>"`.
- Consumes: `SearchProductsResponse.total` (exists in `types/api-types.ts`).

- [ ] **Step 1: Write the failing test**

`src/utils/filters.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { MAX_PRICE, activeFilters } from "./filters";

const money = (n: number) => `₹${n}`;
const none = { search: "", category: "", maxPrice: MAX_PRICE, picked: {} };

describe("activeFilters", () => {
  it("is empty when nothing narrows the list", () => {
    expect(activeFilters(none, money)).toEqual([]);
  });

  it("lists each active filter in a stable order with a readable label", () => {
    expect(
      activeFilters({ search: "shoe", category: "footwear", maxPrice: 5000, picked: { Size: "M", Colour: "Black" } }, money)
    ).toEqual([
      { key: "search", label: "“shoe”" },
      { key: "category", label: "Footwear" },
      { key: "price", label: "Up to ₹5000" },
      { key: "option:Size", label: "Size: M" },
      { key: "option:Colour", label: "Colour: Black" },
    ]);
  });

  it("ignores whitespace-only search text", () => {
    expect(activeFilters({ ...none, search: "   " }, money)).toEqual([]);
  });
});
```

- [ ] **Step 2: Run it and see it fail.** Run: `npx vitest run src/utils/filters.test.ts`. Expected: FAIL (module missing).

- [ ] **Step 3: Implement `src/utils/filters.ts`**

```ts
export const MAX_PRICE = 100000;

/** "" is the server's default order: newest first. */
export const SORTS = [
  { value: "", label: "Newest" },
  { value: "asc", label: "Price: low to high" },
  { value: "dsc", label: "Price: high to low" },
] as const;

type FilterState = { search: string; category: string; maxPrice: number; picked: Record<string, string> };

/** The filters currently narrowing the list, as removable chips. */
export const activeFilters = (s: FilterState, money: (n: number) => string) => {
  const out: { key: string; label: string }[] = [];
  if (s.search.trim()) out.push({ key: "search", label: `“${s.search.trim()}”` });
  if (s.category) out.push({ key: "category", label: s.category.charAt(0).toUpperCase() + s.category.slice(1) });
  if (s.maxPrice < MAX_PRICE) out.push({ key: "price", label: `Up to ${money(s.maxPrice)}` });
  for (const [name, value] of Object.entries(s.picked)) out.push({ key: `option:${name}`, label: `${name}: ${value}` });
  return out;
};
```

- [ ] **Step 4: Run it and see it pass.** Run: `npx vitest run src/utils/filters.test.ts`. Expected: PASS.

- [ ] **Step 5: Page changes in `src/pages/search.tsx`**

- Replace the literal `100000` (state default and slider `max`) with `MAX_PRICE`.
- Replace the sort `<select>` block with:
```tsx
<div>
  <h4 id="sort-label">Sort</h4>
  <div className="chips" role="radiogroup" aria-labelledby="sort-label">
    {SORTS.map((s) => (
      <button key={s.value} type="button" role="radio" aria-checked={sort === s.value}
              className={`chip${sort === s.value ? " is-on" : ""}`} onClick={() => setSort(s.value)}>
        {s.label}
      </button>
    ))}
  </div>
</div>
```
- Replace the price block with a labelled slider plus number input:
```tsx
<div>
  <h4><label htmlFor="max-price">Max price</label></h4>
  <div className="search-page__price">
    <input type="range" min={100} max={MAX_PRICE} step={100} value={maxPrice} aria-label="Max price"
           onChange={(e) => setMaxPrice(Number(e.target.value))} />
    <input id="max-price" type="number" inputMode="numeric" min={100} max={MAX_PRICE} value={maxPrice}
           onChange={(e) => setMaxPrice(Math.min(MAX_PRICE, Math.max(100, Number(e.target.value) || 100)))} />
  </div>
</div>
```
- Under the search box, add the active-filter chips:
```tsx
{chips.length > 0 && (
  <div className="active-filters" aria-label="Active filters">
    {chips.map((f) => (
      <button key={f.key} type="button" className="chip chip--removable" onClick={() => clearFilter(f.key)}
              aria-label={`Remove filter ${f.label}`}>
        {f.label} <LuX aria-hidden />
      </button>
    ))}
    <button type="button" className="link-button" onClick={clearAll}>Clear all</button>
  </div>
)}
```
with, in the component body:
```ts
const chips = activeFilters({ search, category, maxPrice, picked }, formatMoney);
const clearFilter = (key: string) => {
  if (key === "search") { setSearchInput(""); setSearch(""); }
  else if (key === "category") setCategory("");
  else if (key === "price") setMaxPrice(MAX_PRICE);
  else setPicked(({ [key.slice("option:".length)]: _, ...rest }) => rest);
};
const clearAll = () => { setSearchInput(""); setSearch(""); setCategory(""); setMaxPrice(MAX_PRICE); setPicked({}); setSort(""); };
```
(If lint flags the unused `_`, use `const next = { ...p }; delete next[name]; return next;` instead.)
- Header count: `{searchedData && <span>{searchedData.total} products</span>}`.
- Phone filter panel: wrap the `<aside>` contents in `<dialog ref={filtersRef} className="search-filters" aria-label="Filters">…</dialog>`. Copy the pattern from `bottomNav.tsx`: a `filtersOpen` state, plus a `useEffect` that calls `showModal()`/`close()`. Add a "Filters" button (`className="btn search-page__filters-btn"`, icon `LuSlidersHorizontal`) above the grid. In the dialog footer, add `<button className="btn btn--primary" onClick={() => setFiltersOpen(false)}>Show {searchedData?.total ?? 0} results</button>`. Above 900px, CSS shows the dialog contents inline as the sidebar and hides the button (step 6), so desktop behaviour is unchanged. Close the dialog on the `cancel` event so Escape keeps state in sync: `onClose={() => setFiltersOpen(false)}`.

- [ ] **Step 6: Styles in `src/styles/_search.scss`**

```scss
.search-page__price {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 6.5rem;
  gap: $spacing-sm;
  align-items: center;

  input[type="range"] { width: 100%; accent-color: var(--accent); }
  input[type="number"] { @include field; padding: 0 0.5rem; @include tabular; }
}

.active-filters {
  @include flex(row, flex-start, center, $spacing-sm);
  flex-wrap: wrap;
  margin: $spacing-md 0;

  .chip--removable {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    height: 2.25rem;
  }
}

.link-button {
  border: 0;
  background: none;
  color: $color-accent-text;
  font: inherit;
  @include text(body-sm);
  font-weight: 600;
  text-decoration: underline;
  cursor: pointer;
}

.search-page__filters-btn { display: none; }

// Desktop: the dialog is just the sidebar's content box, always shown.
@media (min-width: 901px) {
  .search-filters {
    position: static;
    display: contents;
    border: 0;
    padding: 0;
    background: none;

    &__footer { display: none; }
  }
}

@media (max-width: 900px) {
  .search-page { grid-template-columns: minmax(0, 1fr); }
  .search-page > aside { position: static; }
  .search-page__filters-btn { display: inline-flex; margin-bottom: $spacing-md; }

  .search-filters {
    width: 100%;
    max-width: 100%;
    max-height: 85dvh;
    margin: auto 0 0;
    padding: $spacing-lg $spacing-md calc(#{$spacing-md} + env(safe-area-inset-bottom));
    border: 0;
    border-radius: $radius-lg $radius-lg 0 0;
    background: $color-bg-secondary;
    overflow-y: auto;

    &::backdrop { background: rgba(28, 25, 23, 0.45); }
    &:not([open]) { display: none; }

    &__footer {
      position: sticky;
      bottom: 0;
      padding-top: $spacing-md;
      background: $color-bg-secondary;

      .btn { width: 100%; }
    }
  }
}
```
Remove the now-unused `> select` sort styling only if no other select remains in the aside. The category select stays, so keep it.

- [ ] **Step 7: Browser and keyboard check, checks, commit**

At 1366px: sort chips work, typing 5000 in the price box updates the slider and a chip "Up to ₹5,000" appears, and "Clear all" resets everything. At 390px: "Filters" opens a bottom panel, Escape closes it, and "Show N results" closes it.
Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/utils/filters.ts src/utils/filters.test.ts src/pages/search.tsx src/styles/_search.scss
git commit -m "feat(store): sort chips, typed max price, removable filter chips, phone filter panel

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Checkout steps and a summary that stays in view

**Files:**
- Create: `src/utils/checkout.ts`, `src/utils/checkout.test.ts`
- Create: `src/components/checkoutSteps.tsx`
- Modify: `src/pages/cart.tsx`, `src/pages/shipping.tsx`
- Modify: `src/styles/_cart.scss`

**Interfaces:**
- Produces: `type CheckoutStep = "cart" | "address" | "payment"`, `checkoutSteps(current: CheckoutStep): { key: CheckoutStep; label: string; state: "done" | "current" | "upcoming" }[]`, and `<CheckoutSteps current={…} />`.

- [ ] **Step 1: Write the failing test**

`src/utils/checkout.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { checkoutSteps } from "./checkout";

describe("checkoutSteps", () => {
  it("marks earlier steps done and later ones upcoming", () => {
    expect(checkoutSteps("address").map((s) => [s.label, s.state])).toEqual([
      ["Cart", "done"],
      ["Address", "current"],
      ["Payment", "upcoming"],
    ]);
  });
  it("has nothing done on the first step", () => {
    expect(checkoutSteps("cart").map((s) => s.state)).toEqual(["current", "upcoming", "upcoming"]);
  });
});
```

- [ ] **Step 2: Run it and see it fail.** Run: `npx vitest run src/utils/checkout.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement**

`src/utils/checkout.ts`:
```ts
export type CheckoutStep = "cart" | "address" | "payment";

const STEPS: { key: CheckoutStep; label: string }[] = [
  { key: "cart", label: "Cart" },
  { key: "address", label: "Address" },
  { key: "payment", label: "Payment" },
];

export const checkoutSteps = (current: CheckoutStep) => {
  const at = STEPS.findIndex((s) => s.key === current);
  return STEPS.map((s, i) => ({ ...s, state: i < at ? "done" : i === at ? "current" : "upcoming" } as const));
};
```
`src/components/checkoutSteps.tsx`:
```tsx
import { LuCheck } from "react-icons/lu";
import { CheckoutStep, checkoutSteps } from "../utils/checkout";

/** Cart → Address → Payment. Payment is Razorpay's own window, opened from the address page. */
const CheckoutSteps = ({ current }: { current: CheckoutStep }) => (
  <ol className="checkout-steps" aria-label="Checkout progress">
    {checkoutSteps(current).map((s, i) => (
      <li key={s.key} className={`checkout-steps__step is-${s.state}`} aria-current={s.state === "current" ? "step" : undefined}>
        <span aria-hidden>{s.state === "done" ? <LuCheck /> : i + 1}</span>
        {s.label}
      </li>
    ))}
  </ol>
);

export default CheckoutSteps;
```

- [ ] **Step 4: Run it and see it pass.** Run: `npx vitest run src/utils/checkout.test.ts`. Expected: PASS.

- [ ] **Step 5: Use it, and make the summary stay in view**

`src/pages/cart.tsx`: render `<CheckoutSteps current="cart" />` directly above the `<h1>`, only when `cartItems.length > 0`. `src/pages/shipping.tsx`: render `<CheckoutSteps current="address" />` above `<h1>Shipping address</h1>`.
`src/styles/_cart.scss`: add
```scss
.checkout-steps {
  grid-column: 1 / -1;
  display: flex;
  gap: $spacing-lg;
  margin-bottom: $spacing-lg;
  list-style: none;
  @include text(body-sm);
  color: $color-text-muted;

  &__step {
    @include flex(row, flex-start, center, $spacing-sm);

    > span {
      @include flex;
      @include square(1.75rem);
      border: 1px solid $color-border-hover;
      border-radius: 50%;
      @include tabular;
      font-weight: 600;
    }

    &.is-current { color: $color-text-primary; font-weight: 600; }
    &.is-current > span { border-color: var(--primary); background: var(--primary); color: var(--on-primary); }
    &.is-done > span { border-color: $color-success; color: $color-success; }
  }
}

@media (min-width: 901px) {
  .cart > aside {
    position: sticky;
    top: calc(var(--header-h) + #{$spacing-lg});
    align-self: start;
  }
}
```
If `.cart` uses `grid-template-areas`, put `.checkout-steps` into the same area as the `h1` instead of `grid-column: 1 / -1`. Read `_cart.scss` lines 1–40 first and match it.

- [ ] **Step 6: Browser check, checks, commit**

Cart shows step 1 current. Shipping shows Cart ✓, Address current. The summary stays in view while scrolling a long cart at 1366px.
Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/utils/checkout.ts src/utils/checkout.test.ts src/components/checkoutSteps.tsx src/pages/cart.tsx src/pages/shipping.tsx src/styles/_cart.scss
git commit -m "feat(store): checkout step indicator and an order summary that stays in view

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Footer grouped, with the store's details

**Files:**
- Modify: `src/legal/policies.ts` (add `isFilledIn`)
- Create: `src/legal/policies.test.ts`
- Modify: `src/components/footer.tsx`
- Modify: `src/styles/_legal.scss` (`.footer`)

**Interfaces:**
- Produces: `isFilledIn(value: string | undefined | null): boolean`. False for empty strings and for `[placeholder]` values like the `BUSINESS` defaults.

- [ ] **Step 1: Write the failing test**

`src/legal/policies.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { BUSINESS, isFilledIn } from "./policies";

describe("isFilledIn", () => {
  it("is false for the built-in [placeholders] and empty values", () => {
    expect(isFilledIn(BUSINESS.address)).toBe(false); // "[Registered address]" until a store fills it in
    for (const v of ["", "   ", undefined, null, "[support@your-domain]"]) expect(isFilledIn(v)).toBe(false);
  });
  it("is true for real text, including text that merely contains brackets", () => {
    expect(isFilledIn("12 MG Road, Bengaluru")).toBe(true);
    expect(isFilledIn("Shop [Main branch] Ltd")).toBe(true);
  });
});
```

- [ ] **Step 2: Run it and see it fail.** Run: `npx vitest run src/legal`. Expected: FAIL.

- [ ] **Step 3: Implement** (in `src/legal/policies.ts`)

```ts
/** True when a business detail has been filled in (not empty, not one of the "[…]" placeholders above). */
export const isFilledIn = (value: string | undefined | null) => {
  const v = value?.trim() ?? "";
  return v !== "" && !/^\[.*\]$/.test(v);
};
```

- [ ] **Step 4: Run it and see it pass.** Run: `npx vitest run src/legal`. Expected: PASS.

- [ ] **Step 5: Footer markup and styles**

`src/components/footer.tsx`, the body of `<footer className="footer">`:
```tsx
<div className="footer__groups">
  <nav aria-label="Shop">
    <h2>Shop</h2>
    <Link to="/search">All products</Link>
    <Link to="/cart">Cart</Link>
    <Link to="/orders">Your orders</Link>
  </nav>
  <nav aria-label="Help">
    <h2>Help</h2>
    <Link to="/help">Help Centre</Link>
    <Link to={policyPath("contact")}>{POLICIES.contact.title}</Link>
    <Link to={policyPath("shipping")}>{POLICIES.shipping.title}</Link>
    <Link to={policyPath("refunds")}>{POLICIES.refunds.title}</Link>
  </nav>
  <nav aria-label="Policies">
    <h2>Policies</h2>
    {(["terms", "privacy", "cookies"] as const).map((slug) => (
      <Link key={slug} to={policyPath(slug)}>{POLICIES[slug].title}</Link>
    ))}
  </nav>
  {business.length > 0 && (
    <address>
      <h2>{isFilledIn(BUSINESS.legalName) ? BUSINESS.legalName : brand.name}</h2>
      {business.map((line) => <span key={line}>{line}</span>)}
    </address>
  )}
</div>
<small>© {new Date().getFullYear()} {brand.name}. Payments secured by Razorpay.</small>
```
with `const business = [BUSINESS.address, BUSINESS.email, BUSINESS.phone].filter(isFilledIn);` and imports for `BUSINESS` and `isFilledIn`. Then check that every slug in `POLICY_LINKS` still appears exactly once:
```bash
grep -n "POLICY_LINKS" -r src
```
If nothing else uses `POLICY_LINKS`, drop it from the footer import (keep the export).

`src/styles/_legal.scss`, replace the `.footer` block:
```scss
.footer {
  margin-top: auto; // sticky footer: #root is a flex column
  padding: $spacing-2xl var(--gutter) $spacing-xl;
  border-top: 1px solid $color-border;
  background: $color-bg-secondary;

  &__groups {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: $spacing-xl;
    margin-bottom: $spacing-xl;

    nav,
    address {
      @include flex(column, flex-start, flex-start, $spacing-sm);
      font-style: normal;
    }

    h2 {
      @include text(label);
      font-family: $font-primary;
      letter-spacing: 0;
      color: $color-text-primary;
      margin-bottom: $spacing-xs;
    }

    a,
    span {
      @include text(body-sm);
      color: $color-text-secondary;
    }

    a:hover {
      color: $color-text-primary;
      text-decoration: underline;
    }
  }

  > small {
    display: block;
    @include text(caption);
    font-weight: 400;
    color: $color-text-muted;
  }

  @media (max-width: 720px) {
    &__groups { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  }
}
```

- [ ] **Step 6: Browser check, checks, commit**

The footer shows three link groups. With the seeded store (no business details) there is no address column and no `[…]` text.
Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/legal src/components/footer.tsx src/styles/_legal.scss
git commit -m "feat(store): footer grouped into shop, help and policies, with the store's details when filled in

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Admin sidebar: dark, clear active state, icon rail at 1100–1280px

**Files:**
- Modify: `src/components/admin/AdminSidebar.tsx`
- Modify: `src/styles/app.scss` (`.admin-container`, `.admin-sidebar`, `#hamburger`)

No unit test (pure markup and CSS). Checked in the browser and the keyboard pass in Task 15.

- [ ] **Step 1: Markup**

In `AdminSidebar.tsx`:
- Import `brand` from `../../tenant/tenant` and `Link` from react-router-dom.
- After `.admin-sidebar__top`, add:
```tsx
<div className="admin-sidebar__store">
  <strong>{brand.name}</strong>
  <Link to="/">View store</Link>
</div>
```
- Wrap each link's text in a span, and give the link a `title` so the rail shows a tooltip:
```tsx
<NavLink to={url} title={text}>
  <Icon aria-hidden />
  <span className="admin-sidebar__label">{text}</span>
</NavLink>
```

- [ ] **Step 2: Styles in `src/styles/app.scss`**

In `.admin-sidebar`, change `background: $color-bg-secondary; border-right: 1px solid $color-border;` to `background: var(--nav-bg); border-right: 0; color: var(--nav-text);`. Then:
- `h5` stays on `@include overline;` and adds `color: var(--nav-muted);`.
- `a`: `color: var(--nav-text)`, `svg { color: var(--nav-muted) }`, `&:hover { background: rgba(255, 255, 255, 0.06); color: #fff; }`. Add `position: relative;`.
- `a.active`:
```scss
    &.active {
      background: rgba(255, 255, 255, 0.08);
      color: #fff;
      font-weight: 600;

      svg { color: var(--nav-active); }

      &::before {
        content: "";
        position: absolute;
        left: -0.75rem;
        top: 0.375rem;
        bottom: 0.375rem;
        width: 3px;
        border-radius: 0 3px 3px 0;
        background: var(--nav-active);
      }
    }
```
- `a:focus-visible { outline: 2px solid var(--nav-active); outline-offset: 2px; }`.
- Add:
```scss
  &__store {
    @include flex(column, flex-start, flex-start, 0.125rem);
    padding: 0 0.75rem $spacing-md;
    margin-bottom: $spacing-sm;
    border-bottom: 1px solid rgba(255, 255, 255, 0.1);

    strong { @include text(h4); color: #fff; }
    a { height: auto; padding: 0; @include text(body-sm); color: var(--nav-active); }
    a:hover { background: none; text-decoration: underline; }
  }
```
- In the drawer (≤1100px) block, the `&__top strong` text colour becomes `#fff`, and the close `.icon-btn` gets `color: var(--nav-text)`.
- Floating `#hamburger`: `background: var(--primary); color: var(--on-primary); border-color: var(--primary);`.
- Rail:
```scss
@media (min-width: 1101px) and (max-width: 1280px) {
  .admin-container { grid-template-columns: 4.5rem minmax(0, 1fr); }

  .admin-sidebar {
    padding-inline: 0.75rem;

    h5,
    &__store,
    &__label {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }

    a { justify-content: center; padding: 0; }
  }
}
```
The labels stay in the DOM, visually hidden, so screen readers still announce each link's name.

- [ ] **Step 3: Browser check, checks, commit**

Check at 1366px (full sidebar), 1200px (icon rail with tooltips) and 1000px (drawer from the peacock button). Tab through: the focus ring is visible on the dark background. (This needs an admin session; see Task 15, step 2.)
Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/components/admin/AdminSidebar.tsx src/styles/app.scss
git commit -m "style(admin): petrol sidebar with a clear current page, icon rail at 1100–1280px

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Admin page headers and breadcrumbs

**Files:**
- Create: `src/components/admin/PageHeader.tsx`
- Modify: `src/components/admin/AdminLayout.tsx`
- Modify: `src/components/admin/DetailLayout.tsx` and its 4 callers in `src/pages/admin/management/`
- Modify: `src/pages/admin/{products,category,customers,transaction,audit,settings,helpcenter,analytics}.tsx`, `src/pages/admin/apps/coupon.tsx`
- Modify: `src/styles/admin-styles/_admin-ui.scss` (`.page-header`, `.breadcrumb`, `.detail-page__header`)

**Interfaces:**
- Produces: `<PageHeader title: string; description?: string; actions?: ReactNode />` (renders `<header className="page-header">` with an `h1`). `AdminLayout` gets optional `title`, `description` and `actions`; when `title` is set it renders `PageHeader` before `children`. `DetailLayout` gets a required `parent: { to: string; label: string }`.

- [ ] **Step 1: Components**

`src/components/admin/PageHeader.tsx`:
```tsx
import { ReactNode } from "react";

type Props = { title: string; description?: string; actions?: ReactNode };

/** The top of every admin page: what this page is, one line on what it's for, and its main actions. */
const PageHeader = ({ title, description, actions }: Props) => (
  <header className="page-header">
    <div>
      <h1>{title}</h1>
      {description && <p>{description}</p>}
    </div>
    {actions && <div className="page-header__actions">{actions}</div>}
  </header>
);

export default PageHeader;
```
`src/components/admin/AdminLayout.tsx`:
```tsx
import { ReactNode } from "react";
import AdminSidebar from "./AdminSidebar";
import PageHeader from "./PageHeader";

type Props = { children: ReactNode; title?: string; description?: string; actions?: ReactNode };

const AdminLayout = ({ children, title, description, actions }: Props) => (
  <div className="admin-container">
    <AdminSidebar />
    <main className="admin-main">
      {title && <PageHeader title={title} description={description} actions={actions} />}
      {children}
    </main>
  </div>
);

export default AdminLayout;
```
`DetailLayout.tsx`: add `parent: { to: string; label: string }` to `Props`, import `Link`, and replace the `h2` with:
```tsx
<div className="detail-page__title">
  <nav aria-label="Breadcrumb" className="breadcrumb">
    <Link to={parent.to}>{parent.label}</Link>
    <span aria-hidden>/</span>
  </nav>
  <h1>{title}</h1>
  {subtitle && <small>{subtitle}</small>}
</div>
```
Callers: `productmanagement.tsx` and `newproduct.tsx` pass `parent={{ to: "/admin/product", label: "Products" }}`; `customermanagement.tsx` passes `{ to: "/admin/customer", label: "Customers" }`; `transactionmanagement.tsx` passes `{ to: "/admin/transaction", label: "Transactions" }`.

- [ ] **Step 2: Titles on every list page** (exact copy)

| Page file | `title` | `description` |
|---|---|---|
| `products.tsx` | Products | Add, edit and restock what your store sells. |
| `category.tsx` | Categories | Group products so customers can browse them. |
| `customers.tsx` | Customers | People with an account in your store. |
| `transaction.tsx` | Transactions | Every order and where it is in delivery. |
| `apps/coupon.tsx` | Coupons | Discount codes customers can use at checkout. |
| `audit.tsx` | Audit log | Who changed what in your store, and when. |
| `settings.tsx` | Store settings | Shop rules, invoice details and regional settings for this store. |
| `helpcenter.tsx` (index view only) | Help & guides | *(none)* |
| `analytics.tsx` | Analytics | *(none)*; pass the existing filters `<div className="analytics__filters">…</div>` as `actions` |

Pass them as `<AdminLayout title="…" description="…">` on every `<AdminLayout>` in the file (`analytics.tsx` renders it in 3 branches: loading, error and loaded). In `settings.tsx`, `helpcenter.tsx` (index) and `analytics.tsx`, delete the old `h2` heading (for analytics, the whole `analytics__toolbar` header, since its filters move into `actions`). `helpcenter.tsx`'s `<h2>{guide.title}</h2>` inside an article becomes an `h1`, because that view has no PageHeader.

- [ ] **Step 3: Styles** (`_admin-ui.scss`, after the `// ---------- layout ----------` block)

```scss
.page-header {
  flex: none;
  @include flex(row, space-between, flex-end, $spacing-md);
  flex-wrap: wrap;
  margin-bottom: $spacing-lg;

  h1 { @include text(h2); }

  p {
    margin-top: $spacing-xs;
    @include text(body);
    color: $color-text-secondary;
    max-width: 60ch;
  }

  &__actions {
    @include flex(row, flex-end, center, $spacing-sm);
    flex-wrap: wrap;
  }
}

.breadcrumb {
  @include flex(row, flex-start, center, $spacing-xs);
  @include text(body-sm);
  color: $color-text-muted;

  a { color: $color-text-secondary; }
  a:hover { color: $color-accent-text; text-decoration: underline; }
}
```
In `.detail-page__title`, any `h2` selector becomes `h1` (keep its current size: `@include text(h3)` or whatever the block uses now).

- [ ] **Step 4: Check that each admin page has exactly one h1**

```bash
grep -rn "<h1" src/pages/admin src/components/admin | wc -l
grep -rn "<h2>" src/pages/admin/settings.tsx src/pages/admin/analytics.tsx
```
Expected: the second command prints nothing.

- [ ] **Step 5: Checks and commit**

Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/components/admin src/pages/admin src/styles/admin-styles/_admin-ui.scss
git commit -m "feat(admin): page headers with a purpose line on every page, breadcrumbs on detail pages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Admin tables: right-aligned numbers, empty states with an action

**Files:**
- Modify: `src/components/admin/DataTable.tsx`
- Modify: `src/pages/admin/{products,transaction}.tsx`, `src/pages/admin/apps/coupon.tsx`
- Modify: `src/styles/admin-styles/_admin-ui.scss`

The header row is already sticky and keyboard focus on rows already exists (`tr.clickable:focus-visible`), so neither changes.

**Interfaces:**
- Produces: column option `align?: "right"` (read as `(column as any).align`, which the file's eslint header already allows), and the `DataTable` prop `emptyAction?: ReactNode`.

- [ ] **Step 1: DataTable**

- In the `th` and `td` renderers, add `className={column.align === "right" ? "num" : undefined}` (for cells, `cell.column.align`).
- Empty row:
```tsx
<td colSpan={columns.length} className="data-table__empty">
  <p>{emptyText}</p>
  {emptyAction}
</td>
```
- Add `emptyAction` to the props type with the doc comment `/** Shown under the empty text, e.g. the page's "Add product" button. */`.

- [ ] **Step 2: Mark numeric columns**

Add `align: "right"` to: products Price and Stock; transaction Items, Discount and Amount; coupon Min order and the `usedCount` column. Pass `emptyAction` where the page already has an add action. Products gets its existing "Add product" element, also passed as `actions` (`emptyText="No products yet."`), and coupons gets its create button if it renders one in `actions`.

- [ ] **Step 3: Styles** (inside `.data-table`)

```scss
  th.num,
  td.num {
    text-align: right;
    @include tabular;

    span { justify-content: flex-end; }
  }

  tbody tr:hover td { background: $color-bg-primary; }

  &__empty p { margin-bottom: $spacing-md; }
```

- [ ] **Step 4: Checks and commit**

Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/components/admin/DataTable.tsx src/pages/admin src/styles/admin-styles/_admin-ui.scss
git commit -m "style(admin): right-aligned numeric columns, empty tables offer the next action

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Dashboard figures and charts

**Files:**
- Modify: `src/components/admin/chartTheme.ts`
- Create: `src/components/admin/chartTheme.test.ts`
- Modify: `src/components/admin/Charts.tsx`
- Modify: `src/components/admin/AnalyticsSections.tsx` (stop using status colours as series)
- Modify: `src/pages/admin/analytics.tsx` (`Widget`, `kpi-row`, `c.accent` use)
- Modify: `src/styles/admin-styles/_admin-ui.scss` (`.kpi`, `.kpi-row`)

**Interfaces:**
- Consumes: `ADMIN()`, `SHARED()` from `src/styles/readTokens.ts` (Task 1).
- Produces: `chartColors` = `{ series: string[6], success, warning, danger, info, ink, muted, grid, fill(hex) }`. `accent` is removed; use `series[0]`.

- [ ] **Step 1: Write the failing test**

`src/components/admin/chartTheme.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { ADMIN, SHARED } from "../../styles/readTokens";
import { chartColors } from "./chartTheme";

describe("chart colours follow the design tokens", () => {
  it("status colours equal the status tokens", () => {
    const t = SHARED();
    expect([chartColors.success, chartColors.warning, chartColors.danger, chartColors.info])
      .toEqual([t["--success"], t["--warning"], t["--danger"], t["--info"]]);
  });

  it("text and gridlines equal the admin tokens", () => {
    const t = ADMIN();
    expect([chartColors.ink, chartColors.muted, chartColors.grid]).toEqual([t["--text"], t["--text-muted"], t["--border"]]);
  });

  it("series colours are the validated set and never reuse a status colour", () => {
    expect(chartColors.series).toEqual(["#0079b0", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7"]);
    const status = [chartColors.success, chartColors.warning, chartColors.danger, chartColors.info];
    expect(chartColors.series.filter((s) => status.includes(s))).toEqual([]);
  });
});
```

- [ ] **Step 2: Run it and see it fail.** Run: `npx vitest run src/components/admin/chartTheme.test.ts`. Expected: FAIL (old hexes; `ink`/`muted`/`grid` missing).

- [ ] **Step 3: Implement `chartTheme.ts`**

```ts
/**
 * Chart palette. Chart.js can't read CSS variables, so these mirror _tokens.scss; chartTheme.test.ts
 * fails if they drift. Series order is fixed so a series keeps its colour across charts.
 * Series checked with the dataviz validator (light, white surface): lightness, chroma, CVD and
 * normal-vision separation all pass; slots 3-5 are under 3:1 on white, so charts keep legends/labels.
 */
export const chartColors = {
  series: ["#0079b0", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7"],
  success: "#047857",
  warning: "#b45309",
  danger: "#b91c1c",
  info: "#1d4ed8",
  ink: "#102227",
  muted: "#5b6b6e",
  grid: "#dbe1e1",
  /** Translucent fill for area charts. */
  fill: (hex: string) => `${hex}1f`,
};
```

- [ ] **Step 4: Run it and see it pass.** Run: `npx vitest run src/components/admin/chartTheme.test.ts`. Expected: PASS.

- [ ] **Step 5: Use the tokens in charts**

- `Charts.tsx`: import `chartColors`. `ChartJS.defaults.color = chartColors.ink;`, `legend.labels.color = chartColors.ink`, the value-axis `grid: { color: chartColors.grid }`, and the category-axis and `axisTitle` colour `chartColors.muted`. Remove the slate hex comments.
- `analytics.tsx` line ~262: `c.fill(c.accent)` / `c.accent` become `c.fill(c.series[0])` / `c.series[0]`.
- `AnalyticsSections.tsx` line ~214: `const palette = [c.series[0], c.series[1], c.series[2], c.series[3], c.series[4], c.series[5]];` (status colours no longer stand in for series 5 and 6).
- Run `npx tsc --noEmit`. It must report no remaining `c.accent` / `chartColors.accent` users.

- [ ] **Step 6: Lead figure and change wording**

`Widget` in `analytics.tsx`:
```tsx
const Widget = ({ label, value, change, against, lead }: {
  label: string; value: string; change?: number | null; against: string; lead?: boolean;
}) => (
  <article className={`kpi${lead ? " kpi--lead" : ""}`}>
    <span>{label}</span>
    <strong>{value}</strong>
    {change != null && (
      <small className={change >= 0 ? "green" : "red"}>
        {change >= 0 ? <LuTrendingUp aria-hidden /> : <LuTrendingDown aria-hidden />}
        <span className="sr-only">{change >= 0 ? "Up" : "Down"}</span>
        {change > 0 ? "+" : ""}{change}% <em>{against}</em>
      </small>
    )}
  </article>
);
```
Pass `lead` on the revenue widget only. Styles:
```scss
.kpi-row {
  grid-template-columns: 2fr repeat(3, minmax(0, 1fr));
}

.kpi {
  > strong { @include text(h2); @include tabular; }

  &--lead > strong { @include text(h1); }
}
```
(These replace the matching declarations in the existing `.kpi-row` and `.kpi > strong` rules; keep the responsive overrides in the `// ---------- responsive ----------` block. If one of them sets `.kpi-row` to 2 columns, add `.kpi--lead { grid-column: 1 / -1; }` there.)

- [ ] **Step 7: Checks and commit**

Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/components/admin src/pages/admin/analytics.tsx src/styles/admin-styles/_admin-ui.scss
git commit -m "feat(admin): revenue leads the dashboard; chart colours checked and tied to tokens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Admin forms: inline picture errors and linked help text

**Files:**
- Modify: `src/components/admin/ProductForm.tsx`
- Modify: `src/styles/admin-styles/_admin-ui.scss` (`.form-grid`)

Labels already sit above their fields (`.form-grid label` is a column flex). Outlines and focus rings already come from `--border-strong` and `--ring` via `@mixin field` (fixed in Task 1). Server errors stay as toasts. This task removes the one browser `alert()` and links hint text to its field.

- [ ] **Step 1: ProductForm**

- Add `const [pictureError, setPictureError] = useState("");`.
- In `onPictures`, replace `return alert(result.error);` with `return setPictureError(result.error);`, and call `setPictureError("")` on success before `onChange`.
- On the pictures `<input type="file">`, add `aria-describedby="pictures-hint pictures-error"` and `aria-invalid={!!pictureError}`. Wherever `{hint}` is rendered, give that element `id="pictures-hint" className="field-help"`. Right after it, add:
```tsx
{pictureError && (
  <p id="pictures-error" className="field-error" role="alert">
    <LuAlertCircle aria-hidden /> {pictureError}
  </p>
)}
```
(import `LuAlertCircle` from `react-icons/lu`).

- [ ] **Step 2: Styles** (top level in `_admin-ui.scss`, after `.form-grid`)

```scss
.field-help {
  @include text(body-sm);
  color: $color-text-muted;
}

.field-error {
  @include flex(row, flex-start, center, 0.375rem);
  @include text(body-sm);
  font-weight: 600;
  color: $color-error;
}

[aria-invalid="true"] {
  border-color: $color-error !important;
}
```

- [ ] **Step 3: Check, run checks, commit**

On the new-product page, pick 9 pictures. An inline red message with an icon appears under the hint, with no browser alert. Pick a valid set and the message disappears.
Run: `npm test && npm run lint && npx tsc --noEmit`
```bash
git add src/components/admin/ProductForm.tsx src/styles/admin-styles/_admin-ui.scss
git commit -m "fix(admin): picture errors show inline next to the field instead of a browser alert

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Docs

**Files:**
- Modify: `docs/foundations/architecture.md` (decisions log, newest first, line ~96)
- Modify: `docs/CHALLENGES_AND_LIMITATIONS.md` (§1 known problems, §2 limitations)

- [ ] **Step 1: ADR-026** (insert above the ADR-025 line)

```markdown
- 2026-10-20: ADR-026 **A store's theme recolours its storefront only.** `applyTenantConfig` writes the
  theme into one `<style id="tenant-theme">` scoped to `[data-surface="store"]` instead of `<html>` inline
  style, which overrode the admin tokens too. `data-surface` is set in `main.tsx` before the first render.
  A store accent without its own `--accent-strong`/`--accent-text` gets darker shades that reach 4.5:1
  (`utils/contrast.ts`). Token contrast is enforced by `styles/tokens.test.ts`. Still active.
```

- [ ] **Step 2: Limitations and problems**

In §2 (limitations by design), add:
```markdown
- **No dark mode** on the store or admin; `color-scheme` is fixed to light. Token sets are ready for a
  `[data-theme="dark"]` block, which needs its own contrast-tested values.
- **Chart series 3–5 sit under 3:1 on white** (aqua, yellow, pink). Charts keep legends and labels so colour is never the only cue.
```
In §1 (known problems), add:
```markdown
- **Two dual-axis charts** (Revenue/Orders on the dashboard, refunds in Payments) put two scales on one chart, which is easy to misread. Split each into two charts or index them to a common base.
```

- [ ] **Step 3: Commit**

```bash
git add docs/foundations/architecture.md docs/CHALLENGES_AND_LIMITATIONS.md
git commit -m "docs: ADR-026 store theme scoped to the storefront; record UI limits

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Verification

- [ ] **Step 1: Full checks**

Run: `npm test && npm run lint && npx tsc --noEmit && npm run build`
Expected: all green; the build prints no Sass deprecation errors (warnings that already existed are fine).

- [ ] **Step 2: Screenshots, before and after**

Start `npm run dev` against the docker backend on :8080. Using Playwright at 1366×850 and 390×844, capture `/`, `/search`, `/product/<first id>` and `/cart` (with one item), plus `/admin/analytics`, `/admin/product`, `/admin/product/<id>` and `/admin/settings`. The store "before" shots are `.playwright-mcp/ui-home.png`, `ui-search.png`, `ui-product.png` and `ui-home-m.png` in the main checkout. For admin, ask the user to sign in in the Playwright browser; never read `ADMIN_PASSWORD` from `.env`. Take the admin "before" shots on `dev` first (`git stash` is not needed: run the `../6Pack-dev` frontend), then "after" here.

- [ ] **Step 3: Store theme scope, by hand**

On `/`, inject a store theme the way `applyTheme` writes it (Playwright `browser_evaluate`):
`document.head.append(Object.assign(document.createElement("style"), { id: "tenant-theme", textContent: '[data-surface="store"] { --accent: #7c3aed; }' }))`.
The store accent turns violet. For the hard-refresh check, set the theme on the store in `/platform` instead, because an injected style doesn't survive a reload. Navigate to `/admin/analytics`, and the sidebar and buttons stay peacock. Hard-refresh on `/admin/analytics`: no violet flash.

- [ ] **Step 4: Keyboard pass**

Tab through: the header, a product card (link, then add button), the category chips, the search sort chips, the price box, the active-filter chips, the phone filter panel (opens, traps focus, Escape closes), the admin icon rail at 1200px (each link announces its name), and table rows (Enter opens). The focus ring is visible each time.

- [ ] **Step 5: Request review**

Use superpowers:requesting-code-review on the branch diff against `dev`. Then report the results, with screenshots, to the user.
