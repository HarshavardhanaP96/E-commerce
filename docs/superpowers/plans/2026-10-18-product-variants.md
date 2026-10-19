# Product Variants (Size, Colour and Other Options) — Plan

**Goal:** Let one product be sold in several variants (size, colour, and any other option such as material, storage or pack size), each with its own SKU, stock and optionally price, while products without variants keep working exactly as they do today.

**Status:** Phases 1-4 and the search-filter part of Phase 5 are built on `feat/product-variants` (see "as built" sections below). The rest of Phase 5 is deliberately left out (see its list). Tier 2 item from `docs/superpowers/specs/2026-10-17-richer-entities-design.md` §4 ("variants (size/colour) with per-variant stock and price"), which §4 flagged as an open decision because `PriceCalculator`, stock handling and `OrderItem` all change. This plan resolves that decision.

## What the code does today (why this is a big change)

| Area | Today | Consequence for variants |
|---|---|---|
| Stock | One `stock` int per product. `ProductCatalog.reserveStock(Map<productId, qty>)` does a conditional `$inc` (`stock >= qty`), see `MongoProductRepository.decrementStockIfAvailable`. | Stock must be reserved per variant, and still atomically. |
| Checkout | `CheckoutService.CartLine(productId, quantity)`; `mergeLines` merges by product id; `OrderItem` snapshots name, photo, price, sku, taxRate. | A line needs a `variantId`; two variants of one product are two lines; the order must snapshot which variant was bought. |
| Payment intent | Stores `OrderItem`s (`PaymentIntentDocument.items`). | Variant fields must be persisted there too, or confirm loses them. |
| Cart (SPA) | Identity is `productId` only (`cartReducer.addToCart` / `removeCartItem`); persisted in `localStorage`. | Identity becomes `productId + variantId`. Old saved carts must still load. |
| Storefront | No product page. Cards on Home/Search add straight to cart (`productCard.tsx`). | A variant product cannot be added from a card; it needs a product detail page with selectors. |
| Search | Filters by name, `price`, `category`; sorts by `price`. | Product-level `price` must stay meaningful ("from" price). |
| Docs | ADR-004 (keep `_id` and JSON names), ADR-015/016/017/018 (additive nested records, legacy docs load with defaults, no migration). | Same pattern: additive, no migration. |

## Decision 1 — Data model

| Option | How | Pros | Cons |
|---|---|---|---|
| **A. Embedded generic options + variants (recommended)** | Product document gets `options: [{name, type, values[]}]` and `variants: [{id, sku, options, price?, mrp?, stock, status}]` inside a nested record, like `ProductDetails`. | One atomic document update covers stock; any option axis (size, colour, material…) with no schema change; legacy docs load unchanged; follows ADR-015. | Admin save of the whole array can clobber concurrent stock changes unless handled (see Decision 3); ~100-variant cap per product. |
| B. Fixed `sizes[]` and `colours[]` fields | Two hard-coded lists plus a stock matrix. | Fastest to build. | Dead end the moment you sell a "500 g / 1 kg" or "128 GB" product; the matrix is just a worse variants array. |
| C. Each variant is its own product, linked by `groupId` | No change to stock/checkout code. | Smallest backend change. | Content duplicated per variant; search shows every colour as a separate result; admin editing is painful; no real "option" concept for the UI. |
| D. Separate `variants` collection | Shopify-style. | Scales to thousands of variants; clean per-variant queries. | Cross-collection consistency, extra module surface, and the product's derived stock/price need transactions. Overkill for a single-store app. |

**Recommendation: A.** It keeps stock updates single-document atomic and matches how the rest of the catalogue grew. Revisit D only if a product ever needs more than ~100 variants.

## Decision 2 — Which "other variants" to support

Variant **axes** create separately-stocked sellable units. Generic axes cover all of these without code changes; the admin form offers presets.

| Axis (preset) | Value type | Examples |
|---|---|---|
| Size (apparel) | text, ordered | XS, S, M, L, XL, XXL |
| Size (footwear) | text, ordered; system noted in the label | UK 7, UK 8, EU 41 |
| Colour | text + `hex` swatch (+ optional images) | Black `#111111`, Navy `#1f2a44` |
| Material / fabric | text | Cotton, Linen |
| Fit / style | text | Slim, Regular |
| Capacity / storage | text | 250 ml, 128 GB |
| Pack size / weight | text | Pack of 3, 500 g |
| Flavour / pattern / model | text | Mango, Striped |
| Custom | admin-named axis | anything else |

Limits (to keep the matrix usable): at most **3 axes** per product, at most **20 values** per axis, at most **100 variants** per product. Value order is display order.

Product **attributes** are descriptive facts that do *not* create stock units, and stay out of the variant matrix: country of origin, care instructions, weight/dimensions, size-chart image. Country of origin and MRP are display duties under the Consumer Protection (E-Commerce) Rules 2020, so a `countryOfOrigin` field belongs in this work as a plain optional product field (see Phase 1).

Images belong to the **option value**, not to every size×colour combination: uploading three images for "Black" once is enough, and the gallery switches when the shopper picks Black.

## Decision 3 — Stock and price rules (recommended)

1. **Single-document atomicity.** A purchase does one `updateFirst` with `variants: {$elemMatch: {id, stock: {$gte: qty}}}` and `$inc: {"variants.$.stock": -qty, "stock": -qty}`. The product-level `stock` stays an exact running total, so every existing reader (admin list, card "Only 3 left", low-stock tags) works untouched. Invariant: `product.stock == sum(variant.stock)` — tested under concurrency.
2. **Product `price` = lowest active variant price** (stored, recomputed whenever variant structure or prices change), so `maxPrice` filtering and price sort keep working. The storefront shows "From ₹X" when variant prices differ.
3. **Variant price is required; MRP is optional.** *(Changed while building: an optional variant price would fall back to a product price that is itself derived from the variants, which is circular.)* `mrp >= price` is validated per variant.
4. **Tax stays product-level in v1** (`taxRate`, `hsn`). `OrderItem` already snapshots `taxRate`, so a nullable per-variant override can be added later if price-slab GST for apparel/footwear needs it. Open question for the owner, see below.
5. **Admin saves must not overwrite live stock.** The structure endpoint (axes, variants, SKUs, prices, status) ignores `stock` for variants that already exist. Stock changes go through a dedicated atomic endpoint (`PATCH /product/{id}/variants/{variantId}/stock` with a `delta` or an absolute `set`). New variants take an initial stock on creation.
6. **Variant ids are server-generated and stable.** Carts, payment intents and orders reference them. Admin can disable a variant (`status`) instead of deleting it; deleting is allowed because orders snapshot everything.
7. **A product with variants must be bought with a `variantId`; a product without variants must not carry one** (400 either way). Disabled/archived variants cannot be bought.

## Target shape

```text
Product (existing)         + details.countryOfOrigin (optional text)
                           + variants: ProductVariants   (nested, additive, EMPTY for legacy)

ProductVariants
  options: [ { name: "Size",   type: TEXT,   values: [ { label: "M" }, ... ] },
             { name: "Colour", type: COLOUR, values: [ { label: "Black", hex: "#111111", images: [...] } ] } ]
  items:   [ { id, sku, options: [ {name:"Size",value:"M"}, {name:"Colour",value:"Black"} ],
               price?, mrp?, stock, status } ]

OrderItem (existing)       + variantId?, variantLabel? ("M / Black"), options? (name/value pairs)
CartItem (SPA)             + variantId?, variantLabel?
```

Options are stored as `{name, value}` pairs (not a map with dynamic keys) so they can be queried with `$elemMatch` and indexed later.

## Phases

Each phase ships and tests independently. Legacy products must behave identically after every phase.

### Phase 0 — Decisions and docs
- Confirm Decisions 1–3 and the open questions below.
- Add ADR-019 to `docs/foundations/architecture.md`; update `requirements.md` (new FR4c), `contracts.md` (fields, endpoints), `test-rollout.md`; mark the variants row in the spec as Tier 1.

### Phase 1 — Catalogue model and admin API (backend only, flag off)
- Domain: `ProductVariants`, `VariantOption`, `Variant`, `VariantStatus`; validation (axis/value/variant caps, every variant covers every axis exactly once, no duplicate option combination, unique SKUs inside the product, `mrp >= price`).
- Persistence: nested fields in `ProductDocument`; `variants.items.sku` sparse unique index plus a service-level check against other products' SKUs and `product.sku` (a unique multikey index does not stop duplicates *within* one document).
- Derived fields: product `price` and `stock` recomputed on every structure write.
- API: `PUT /api/v1/product/{id}/variants` (JSON: axes + variants), `PATCH …/variants/{variantId}/stock`; `GET /product/{id}` and list views return `variants` additively. Existing multipart `POST/PUT` untouched.
- `countryOfOrigin` added to `ProductDetails` (plain optional field).
- Gated by `sixpack.product.variants-enabled=false`, so no variant product can exist before checkout understands it (flipped in Phase 4).
- Tests: legacy-document load; round-trip; every validation rule returning 400 not 500; SKU collisions return 409; blank SKUs never collide.

### Phase 2 — Stock, checkout, orders (the risky phase)
- `ProductCatalog`: add `StockLine(productId, variantId, quantity)` and line-based `reserveStock` / `releaseStock` (old `Map` methods stay as adapters so existing tests compile); `findSnapshots` resolves each line to price/sku/stock/photo/variant label, using the variant's price when set and the option-value image when present.
- `CheckoutService`: `CartLine` gains `variantId`; `mergeLines` keys on product+variant; stock check per variant; error messages name the variant ("Only 2 left in M / Black for …"); rollback paths in `confirm` release per line.
- `OrderItem` / `OrderDocument.OrderItemDoc` / `PaymentIntentDocument`: additive `variantId`, `variantLabel`, `options`; `OrderView` exposes them. Old orders and in-flight intents load with nulls.
- Tests (Testcontainers): last unit of one variant with N concurrent buyers (exactly one wins, `stock == sum(variants)` afterwards); two variants of one product do not block each other; multi-line rollback restores every variant; variant product without `variantId` → 400; variant of another product → 400; disabled variant → 409/400; legacy product checkout unchanged; Modulith boundary test stays green (order module only touches `ProductCatalog`).

### Phase 3 — Storefront
- New product page (route `/product/:id`, slug later) using the existing `productDetails` query: gallery, price / MRP / discount, **size chips and colour swatches**, out-of-stock options struck through and disabled based on the current selection, stock hint, quantity, add to cart. Country of origin shown.
- `CartItem` + `cartReducer`: identity `productId + variantId`; `removeCartItem` takes that key; old `localStorage` carts load unchanged; cart rows show the variant label; stock cap uses the variant's stock.
- Cards (Home/Search): for variant products show swatches or "Select options" and link to the product page instead of adding to cart; "From ₹X".
- Order details / orders list show the variant label.
- Stale cart handling: if checkout returns 400/409 for a removed or sold-out variant, show which line failed and let the shopper fix it.

### Phase 4 — Admin UI, then flip the flag
- `ProductForm` gets a Variants section: pick preset axes or add a custom one, add values (colour picker for hex, drag to order), "Generate combinations", an editable table (SKU, price, MRP, status, stock, image per colour).
- Stock cell edits call the atomic stock endpoint; structure edits call the structure endpoint; unsaved state is clear.
- Admin help guide in `src/help/adminGuides.ts`.
- Then set `sixpack.product.variants-enabled=true`.

### Phase 5 — Later (not committed)
- Search facets (`?size=M&colour=Black`) via a denormalised `optionFacets` array with a multikey index; facet counts on the Search page.
- Per-variant analytics in the dashboard (`orderItems.variantId` scope).
- Low-stock alerts per variant; size-chart table; per-variant tax override; bulk CSV import/export of variants.

## Phase 1 as built (2026-10-18)

Done and verified (`mvn verify`: 163 tests, Modulith boundary check green):
- Domain: `ProductVariants`, `OptionAxis`, `OptionValue`, `OptionChoice`, `Variant`, `VariantStatus`, `OptionType`, and the pure `VariantRules.build` (14 unit tests).
- Persistence: nested `variants` in `ProductDocument`, `variantId` stored instead of `id`, `rev` counter, sparse unique index on `variants.items.sku`. `ProductRepository` gains `updateVariants` (revision-checked), `adjustVariantStock`, `compareAndSetVariantStock`; `findBySku` also matches variant SKUs.
- API: `PUT /product/{id}/variants`, `PATCH /product/{id}/variants/{variantId}/stock`; product JSON gains `variants` and `countryOfOrigin`; plain product `PUT` ignores `price`/`stock` for variant products.
- Flag `app.products.variants-enabled` (env `PRODUCT_VARIANTS_ENABLED`, default false); integration tests turn it on, and one test proves it blocks creation when off.
- Differences from the plan above: variant price is required; blank variant SKUs are always generated (a unique index across array elements breaks on null SKUs); `rev` makes structure saves safe against concurrent sales; a plain `save` never writes price/stock/variants of a variant product.
- At the end of Phase 1 checkout was unaware of variants, which is why the flag stayed off.

## Phase 2 as built (2026-10-18)

Done and verified (`mvn verify`: 178 tests, Modulith boundary check green):
- `ProductCatalog`: `resolveLine(productId, variantId)` (throws 400 for a missing/unavailable product or variant, a missing choice, or a variant on a simple product), and `reserveStock` / `releaseStock` over `StockLine(productId, variantId, qty)`. The old `Map`-based methods were removed (only checkout used them), which avoids a default method bypassing the cache-evicting proxy.
- `ProductSnapshot` carries the variant's price, stock, SKU, id, label and options; photo is the first image of any of the variant's option values, else the product photo.
- Repository: `decrementVariantStockIfAvailable` (needs a sellable variant with enough stock); `decrementStockIfAvailable` no longer matches a product that has variants.
- `CheckoutService`: `CartLine(productId, variantId, quantity)` (the 2-argument form still works), lines merged per product + variant, errors name the variant, rollback and confirm release per line.
- `OrderItem`, `OrderItemDoc` (so payment intents too) and `OrderView` gain `variantId`, `variantLabel`, `options`; old orders load with nulls and an empty `options` list.
- 9 integration tests: price/snapshot/decrement of one variant; two variants as two lines; merge of the same variant; line/variant mismatch rules; disabled variant; last-unit race; rollback of the other lines; simple sale never touches a variant product; legacy order loads.

## Phase 3 as built (2026-10-19)

Verified with `npm run lint`, `npm run build` and a real browser run against an isolated stack (own Mongo, backend and Vite).
- New `/product/:id` page for every product: gallery that follows the picked colour, size chips, colour swatches, sold-out values struck through but still selectable, price that narrows as options are picked ("From ₹499" → "₹549"), MRP and discount, stock hint, quantity stepper capped by remaining stock, SKU and country of origin.
- Cart lines are keyed by product + variant (`cartKey`); an old saved cart (no variant ids) still loaded and checked out.
- Cards link to the page; variant products show "From" price, colour swatches and an options link instead of quick-add.
- Cart, checkout request, order details and admin order details show the variant label and SKU.
- Pure helpers (selection, availability, price range) live in `src/utils/variants.ts`.

## Phase 4 as built (2026-10-19)

- Admin product page gets Details / Variants tabs. The Variants tab: option axes with preset names (typing "Colour" switches to swatches), comma-separated values, auto-generated combination table (price, MRP, stock, status, SKU), per-colour images chosen from the product gallery, "Save variants" and "Remove all variants". Stock cells of saved variants save on leaving the field through the atomic stock endpoint.
- Details tab: gallery upload and country of origin added; price and stock are disabled when the product has variants.
- Backend hardening found while building: colour images must come from the product's own gallery (400 otherwise), and replacing the gallery drops colour images that no longer exist.
- Bug found only in a real browser (MockMvc cannot see it): `PATCH` was missing from the CORS allow-list, so the stock edit returned 403. Fixed with a preflight regression test.
- Admin help guide "Sizes, colours and other variants".
- `app.products.variants-enabled` now defaults to true (`PRODUCT_VARIANTS_ENABLED=false` blocks creating new variants; existing ones keep selling).

## Phase 5 as built (2026-10-19): search filters only

- `GET /product/all?option=Size:M&option=Colour:Black` (up to 3): one variant that is sellable and in stock must match every filter, so "M" and "White" do not match a product that has M/Black and S/White. `GET /product/options` lists option names, types, values and swatch colours of public products (cached 60 s with the other catalogue caches, evicted on product changes). Option names may not contain `:`.
- Search page: option groups (chips, colour swatches), one value per option, replaced on a second click.
- Not done, on purpose: per-variant dashboard analytics, per-variant low-stock alerts, size-chart table, per-variant tax override, CSV import/export. The last two depend on the open questions about GST slabs and bulk stock editing, which were never answered. Each is additive on the current model.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Overselling a variant under concurrency | Conditional `$elemMatch` + `$inc`, concurrent test in Phase 2. |
| `product.stock` drifting from the variant sum | Updated in the same atomic operation; invariant test; one recompute helper used by every structure write. |
| Admin save clobbers live stock | Structure endpoint ignores stock for existing variants; stock has its own atomic endpoint. |
| A variant product created before checkout supports it | Feature flag off until Phase 4; checkout rejects variant products without `variantId`. |
| Old carts / intents / orders break | Every new field optional; legacy-shape tests at each layer; no migration script. |
| Duplicate SKUs across and within products | Service-level check plus sparse unique index; blank SKUs normalised to null. |
| Cart price drift when a variant price changes | Server already re-prices at checkout; quote shown before payment (existing behaviour). |
| Document growth | Caps: 3 axes × 20 values, 100 variants; far below Mongo's 16 MB limit. |

## Open questions for you

1. Is Decision 1 option A (embedded generic variants) what you want, or do you prefer the simpler fixed size + colour (option B)?
2. Which axes do you actually sell today (clothing? footwear? electronics?). That decides the presets in Phase 4 and whether the size chart moves out of Phase 5.
3. GST: do your products have price-dependent GST slabs (common for apparel and footwear)? If so the per-variant tax override moves from Phase 5 into Phase 2.
4. Should variants of one product share a single product page URL only (recommended), or should a colour also have its own shareable link (`/product/:id?colour=Black`)? The query-string form is cheap to add in Phase 3.
5. Must stock be editable in bulk (CSV) at launch, or is per-row editing in the admin enough?

## Verification gate (every phase)
`cd backend && mvn verify` (Docker running for Testcontainers), `cd ecommerce-frontend && npm run build && npm run lint`, plus a manual pass: legacy product still bought end to end, then (Phase 3 on) a variant product bought end to end with the fake gateway.

## Next step
Once Decisions 1–3 and the open questions are settled, expand Phase 1 into a task-by-task TDD plan in the same style as `2026-10-17-product-category-fields.md`; later phases get their own plan files once the previous one lands.
