# UI refresh: store "Studio shelf", admin "Ink console"

Date: 2026-10-20 · Branch: `feat/ui-refresh` (off `dev` 2d7fc2a) · Status: awaiting review

## 1. Goal

Make the storefront and the admin panel look deliberate and read well, with each surface getting its own direction:

- **Store, "Studio shelf":** the product photos carry the colour. The chrome stays ink and warm grey, and the one gradient sits in the home banner, built from the store's own accent so every store's theme carries through.
- **Admin, "Ink console":** a dark petrol sidebar, a light and calm work area, and one peacock-blue action colour. Green, amber and red mean status and nothing else. Charts use a checked palette.

Success means:
1. Every text and background colour pair in both token sets meets WCAG AA: 4.5:1 for body text, 3:1 for large text and control outlines. A unit test enforces this.
2. A store's colours never change the admin panel.
3. On the store, photos fill their cards, names aren't cut off after one line, and the header, content and footer share one 1200px column.
4. The admin dashboard has one leading figure, and the sidebar is usable at 1100–1280px without the drawer.
5. No all-caps labels anywhere.
6. Lint, `tsc`, `vitest` and the build all pass, with before and after screenshots at 1366px and 390px.

Out of scope: dark mode (a later step), the platform operator console (`/platform`), new fonts (Inter and Bricolage Grotesque are already bundled), backend changes, and checkout logic.

## 2. What exists today (measured)

| Issue | Where | Measured |
|---|---|---|
| White on coral badge / cart count | `.product-card__tag`, `.header__count` | 3.68:1 (needs 4.5) |
| Muted text on `--surface-2` | both surfaces | 4.40 store / 4.34 admin |
| Input borders (`--border-strong`) | `@mixin field` | 1.49 / 1.48 (needs 3:1) |
| Store theme overrides admin tokens | `tenant.ts` sets theme on `<html>` inline style, beating `[data-surface="admin"]` | any store accent recolours admin |
| Photos letterboxed | `_home.scss` `.product-card__media img { object-fit: contain }` + padding | mixed aspect ratios in grid |
| Uppercase labels | `@mixin overline`, hero `.eyebrow` | sidebar, filters, KPI labels |
| Chart colours copied by hand | `components/admin/chartTheme.ts` | indigo and sky hard to tell apart |

## 3. Tokens

All values live in `src/styles/_tokens.scss`. Component files keep reading the existing Sass aliases (`$color-*`), so most of the restyle is a token change and needs no markup changes.

### 3.1 Store (`:root`, `[data-surface="store"]`)

| Token | Value | Use / contrast |
|---|---|---|
| `--bg` | `#f5f5f3` | studio backdrop |
| `--surface` | `#ffffff` | cards, header |
| `--surface-2` | `#ececea` | photo mat, chips, skeletons |
| `--border` | `#e2e0dc` | dividers (decorative) |
| `--border-strong` | `#8a837d` | control outlines, 3.73:1 on white |
| `--text` | `#1c1917` | 16:1 on bg |
| `--text-2` | `#57534e` | 6.99:1 on bg, 6.45 on surface-2 |
| `--text-muted` | `#6b6560` | 4.86:1 on surface-2, 5.74 on white |
| `--accent` | `#e4572e` | brand dot, range thumb, large marks only |
| `--accent-strong` (new) | `#c2410c` | badge/count backgrounds, 5.18:1 with white |
| `--accent-text` | `#c2410c` | links, small accent text |
| `--accent-subtle` | `#fff1ec` | tint |
| `--primary` / `--primary-hover` | `#1c1917` / `#3a3532` | main CTA is ink |
| `--hero-from` / `--hero-to` (new) | `color-mix(in srgb, var(--accent) 16%, var(--surface))` / `var(--bg)` | the single gradient |

Type: Bricolage Grotesque (optical sizing) for headings, Inter for text. The current scale stays. Prices use equal-width digits, and the h1 uses `font-variation-settings: "opsz" 96` at hero size.

### 3.2 Admin (`[data-surface="admin"]`)

| Token | Value | Use / contrast |
|---|---|---|
| `--bg` | `#f4f6f6` | work area |
| `--surface` | `#ffffff` | cards, tables |
| `--surface-2` | `#eaeeee` | table header, hover |
| `--border` | `#dbe1e1` | dividers |
| `--border-strong` | `#7d8a8c` | control outlines, 3.57:1 |
| `--text` | `#102227` | |
| `--text-2` | `#44565a` | 7.7:1 on white, 6.59 on surface-2 |
| `--text-muted` | `#5b6b6e` | 4.76:1 on surface-2 |
| `--accent` / `--primary` | `#0b6a88` peacock | white on it 6.12:1; on bg 5.64:1 |
| `--primary-hover` | `#0b5f7a` | |
| `--accent-subtle` | `#e6f2f6` | accent text on it 6.27:1 |
| `--nav-bg` (new) | `#0f2430` | sidebar |
| `--nav-text` / `--nav-muted` (new) | `#c9d6d8` / `#8fa3a6` | 10.7 / 6.05:1 on nav |
| `--nav-active` (new) | `#67c6e0` | active bar + icon, 8.16:1 |

Status colours (`--success`, `--warning`, `--danger`, `--info` `#1d4ed8`) stay as they are and are used only for status. `--info` is royal blue, clearly apart from peacock.

### 3.3 Store themes apply only to the storefront

`applyTenantConfig` stops writing to `<html>` inline style. It writes one `<style id="tenant-theme">` containing `:root, [data-surface="store"] { … }`, which has the same specificity as the store defaults and comes later in the page, so it wins there and nowhere else. Keys and values are checked again on the client (`^--[a-z0-9-]{1,40}$`; values contain no `;{}<>`), and bad entries are dropped. The backend already validates these, so this is defence in depth.

If a store sets `--accent` without setting `--accent-strong`, the client derives an `--accent-strong` by darkening the accent until white text on it reaches 4.5:1. This is a pure function, `readableOn(hex, "#fff", 4.5)`, so a store's light brand colour can't produce unreadable badges.

## 4. Store changes

1. **Product card (`productCard.tsx`, `_home.scss`):** the media area becomes `aspect-ratio: 4/5` with `object-fit: cover` and no padding, on a `--surface-2` mat. Names are clamped to two lines. When `mrp > price` the card shows the price, the struck-out MRP and the % off, using `discountPercent` from `utils/variants.ts` (the helper the product page already uses). Tags use `--accent-strong`. The add button is 44px, keeps its existing `aria-label`, and `:focus-visible` shows on the card link.
2. **Home (`home.tsx`):** the banner is a light panel on the `--hero-from → --hero-to` gradient. On the left are the store name as the h1, one line of copy and a "Shop all products" button. On the right are up to three photos from the latest products, overlapped like a shelf. While loading, the banner shows the mat with no photos, and the static `hero.webp` is no longer used. The uppercase kicker is removed. Under the banner sits a row of category chips (`useCategoriesQuery`, linking to `/search?category=`), hidden when there are no categories. When the grid's last row is short, a "View all products" tile fills the gap.
3. **Product page (`product.tsx`, `_product.scss`):** the page uses the shared 1200px column (the existing gallery and thumbnails stay). A delivery and returns summary built from `commerce` settings (shipping fee, free-shipping threshold) sits under the buy box. On phones (≤640px) the add-to-cart bar sticks to the bottom, above the bottom menu.
4. **Search (`search.tsx`, `_search.scss`):** sort becomes a chip group (Newest, Price low to high, Price high to low), with "Sort: None" removed. Price gets labelled min and max inputs beside the range slider. Active filters show as removable chips with a "Clear all" link. On phones (≤900px) the filters open in a bottom panel with a "Show N results" button.
5. **Cart and checkout (`cart.tsx`, `shipping.tsx`):** a three-step indicator (Cart, Address, Payment) shows the current step with `aria-current="step"`. The order summary stays in view on desktop.
6. **Footer (`footer.tsx`):** three groups (Shop, Help, Policies) plus the store's legal name, address and email from `BUSINESS`, then the © line.
7. **Labels:** `@mixin overline` becomes sentence case at `--fs-label` and weight 600, with no tracking. Every caller (filters, sidebar headings, KPI labels) follows.

## 5. Admin changes

1. **Tokens and theme leak:** as in 3.2 and 3.3.
2. **Sidebar (`AdminSidebar.tsx`, `app.scss`):** `--nav-bg` background. At the top sit the store name and a "View store" link. Groups are "Manage" and "Support" in sentence case. The active item gets a 3px `--nav-active` left bar, `--nav-text` in bold and a white 8% tint. Between 1100 and 1280px the sidebar collapses to a 4.5rem icon rail with tooltips and visible `aria-label`s. Below 1100px the drawer stays, and the floating menu button gets the accent colour so it's easy to find.
3. **Page header (new `components/admin/PageHeader.tsx`):** `title`, an optional one-line `description` and `actions` on the right. Detail pages add a breadcrumb (`nav aria-label="Breadcrumb"`). It's used on every admin page that currently hand-rolls a toolbar heading.
4. **Tables (`DataTable.tsx`, `_admin-ui.scss`):** the header row stays in view while scrolling, on `--surface-2`. Rows highlight on hover, and the focused row gets an outline. Numeric columns are right-aligned with equal-width digits, through an `align: "right"` column option. Empty tables get one line plus the main action where the page has one.
5. **Dashboard (`analytics.tsx`):** the first figure (revenue) spans two columns and shows its value at `--fs-h1`; the others use `--fs-h2`. Changes show an arrow icon, the signed percentage and the comparison words, so colour isn't the only signal.
6. **Charts (`chartTheme.ts`):** the series palette is rebuilt with the dataviz skill's validator (lead series = peacock `#0b6a88`, then colours distinguishable under deuteranopia and protanopia). Gridlines use `--border`, and ticks use `--text-muted`. A unit test checks that `chartColors.accent` and the status colours match the tokens in `_tokens.scss`, so the two copies can't drift apart.
7. **Forms (`ProductForm.tsx`, settings, coupon):** labels above fields, help text in `--text-muted`, and errors in `--danger` with an icon and `aria-describedby`. Outlines use `--border-strong` and focus rings use `--ring`.
8. **Shape and shadow:** cards stay flat with only a border. Shadows are reserved for floating layers (menus, drawer, toasts). Corners: 6px on controls, 10px on cards, 14px on dialogs.

## 6. Testing

TDD where there is logic. Pure style changes are checked with screenshots.

| Test | File | What it proves |
|---|---|---|
| Contrast pairs | `src/styles/tokens.test.ts` | parses both token blocks in `_tokens.scss` and asserts every pair in §3.1/§3.2 meets its threshold |
| Theme scoping | `src/tenant/tenant.test.ts` | `applyTenantConfig` writes a store-scoped `<style>`, never `<html>` inline; drops bad keys/values; replaces rather than appends on a second call |
| Readable accent | `src/tenant/contrast.test.ts` | `readableOn` returns the colour unchanged when it already passes and a darker shade that passes when it doesn't; bad hex throws |
| Chart/token sync | `components/admin/chartTheme.test.ts` | chart status and accent colours equal the tokens |
| Discount | `src/utils/variants.test.ts` (new) | `discountPercent` gives the rounded % for mrp > price and nothing when mrp ≤ price or missing, which is the rule the card now shares |

Tests that touch the DOM (`tenant.test.ts`) need `jsdom` as a dev dependency and `// @vitest-environment jsdom` in those files only.

Visual check: Playwright screenshots of home, search, product and cart at 1366px and 390px before and after. A keyboard pass over the header, product card, filters and sidebar. Checking the admin screens needs an admin session in the browser, so I'll ask you to sign in at that point because I don't read passwords from `.env`.

## 7. Build order (one commit each)

1. Tests and tokens: contrast test, new token values, `overline` change (shared).
2. Store-theme scoping and `readableOn` (shared, fixes the admin leak).
3. Store: product card, then home, then product page, then search, then cart steps, then footer.
4. Admin: sidebar and rail, then PageHeader, then tables, then dashboard and charts, then forms.
5. Docs: an ADR in `docs/foundations/architecture.md` for "tenant theme is scoped to the storefront", and `docs/CHALLENGES_AND_LIMITATIONS.md` gets a line on dark mode not existing yet.

## 8. Risks

- **Conflicts:** `feat/analytics` and other branches also edit `_admin-ui.scss` and `analytics.tsx`. Keep admin commits small, and rebase on `dev` before merging.
- **Stores with existing themes** may have set `--accent` only. The derived `--accent-strong` covers badges, but a store accent used as `--primary` is outside this work (stores don't set it today).
- **Native range input** styling differs across browsers. The min and max number inputs are the accessible fallback.

## 9. Corrections made while planning (2026-10-20)

These came out of reading the code and measuring the real data. The plan follows these, not the earlier text.

1. **Product photos (§4.1):** a square tile with the photo contained on a blurred, enlarged copy of itself, instead of 4:5 `cover`. The seeded photos are 3:2, 16:9, 4:5, 2:3 and 1:1, and a 4:5 crop would cut off more than half of a 16:9 product. The tile is still filled edge to edge, now with the photo's own colour.
2. **Store theme selector (§3.3):** `[data-surface="store"]` only. A `:root` rule injected after the stylesheet has the same specificity as `[data-surface="admin"]` and would win, so it would still leak. `data-surface` is set in `main.tsx` before the first render, so store pages never fall back to `:root` while a theme loads.
3. **Derived shades (§3.3):** a store accent also gets a derived `--accent-text` (4.5:1 on the store backdrop), not only `--accent-strong`. Otherwise a blue store would keep orange links.
4. **Chart lead colour (§5.6):** series 1 is `#0079b0`. Peacock `#0b6a88` fails the validator's chroma floor (it reads as grey at chart size); the UI accent stays `#0b6a88`. Status colours no longer stand in for series 5 and 6.
5. **Price filter (§4.4):** a labelled "Max price" number box beside the slider. The API takes only a maximum, so there is no minimum to show. The phone panel's button shows the real total (`total` is in the search response).
6. **Home "View all" (§4.2):** the home grid uses fixed 4/3/2 columns, so the tile can span exactly the empty cells of the last row (`fillSpan`). It takes a full row when the cards fill their rows exactly.
7. **Admin forms (§5.7):** labels already sit above fields and errors come from the server as toasts. The work is limited to replacing the product form's browser `alert()` with an inline error linked to the field.
8. **Admin tables (§5.4):** the sticky header and row focus outline already exist. Only right-aligned numbers and empty-state actions are new.
9. **Admin radii (§5.8):** 6px controls, 10px cards. Dialogs share the card radius rather than a third value.
