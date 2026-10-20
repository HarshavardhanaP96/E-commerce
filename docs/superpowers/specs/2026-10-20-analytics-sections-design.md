# Analytics sections — design

Status: **approved in conversation 2026-10-20, awaiting review of this written spec.** Branch: `feat/analytics`
(fast-forwarded to `dev` at `5e8541a`). This is step 3 of the analytics plan ("more shop sections + CSV"), widened with
Location and in-tab trends. Steps 2 (daily summary table) and 4–6 are unchanged and out of scope here.

## 1. Goal and scope

A store admin opens **Analytics** and finds one tab per question they ask about the shop, all driven by the same
date-range / compare / category filters that step 1 added:

| Tab | Question it answers | Follows date filters |
|---|---|---|
| Overview | How did the period go, and when do people buy? | yes |
| Sales | Where does the money come from and go? | yes |
| Orders | Are orders flowing and getting delivered on time? | yes |
| Stock | What is running out or sitting idle right now? | no (current state; category applies) |
| Customers (the "users" section) | Who buys, and do they come back? | yes |
| Demand | Which products sell, rise, fall or stall? | yes |
| Location | Where do orders ship to? | yes |

"Basic" means descriptive numbers for a period plus a comparison. **Out of scope (advanced phase):** forecasting, moving
averages, cohorts, a map, city-level grouping, cost/margin, abandoned checkouts, product views/conversion (plan step 6),
and gender/age charts (dropped by decision, see §8).

Each tab shows a trend for its main metric and a change % against the comparison period; there is no separate
Trends tab.

## 2. Facts this design relies on (checked in code on `dev`)

- Orders snapshot `shippingInfo{city, state, country, pinCode}`; checkout sends `state` as free text and `pinCode` as
  4–10 digits ([shipping.tsx](../../../ecommerce-frontend/src/pages/shipping.tsx)).
- `OrderStatus` is only PROCESSING / SHIPPED / DELIVERED; `details.statusHistory[{status, at, actor}]` exists, empty on
  older orders. `details.couponCode` is stored.
- `PaymentInfo.method` (e.g. `upi`, null when unknown) and `payment.refunds[]` / `refundedPaise` exist (ADR-021).
- No cost price on products, so stock is valued at selling price.
- Low stock is already modelled: `ProductDetails.lowStockThreshold` (per product) → `StoreSettings.Catalog.lowStockThreshold`
  (per store) → `StoreDefaults` platform fallback 5, and `StockAlert` is the existing "line at or below threshold" type.
- Store time zone is `StoreSettings.Regional.timeZone`, resolved through `StoreConfig`; `SummaryService` already uses it.
- `PaymentIntent` has no timestamp, so abandoned checkouts cannot be measured yet.
- `Invoice.sameState` compares seller and buyer state by trimmed, lower-cased text, so free-text state also affects the
  CGST/SGST vs IGST split; the state normaliser in §5.7 fixes this too.

## 3. Shared contract

### 3.1 Endpoints

`GET /api/v1/dashboard/{overview|sales|orders|stock|customers|demand|location}` — 🛡 store admin/staff, tenant-scoped
exactly like `/summary`.

Common query parameters (same parsing and validation as `/summary`; reuse its helpers):

| Param | Notes |
|---|---|
| `from`, `to` | ISO dates, inclusive, store-local days; default last 30 days ending today in the store's zone |
| `compare` | `previous` (default) · `year` · `none` |
| `basis` | `net` (default) · `gross` — only where revenue is shown |
| `granularity` | `day` · `week` · `month`; null = `Granularity.auto(period)`; same point cap as `/summary` |
| `category` | optional, lower-cased |
| `limit` | ranked lists only; default 10, max 50 (platform safety limit) |
| `format` | `json` (default) · `csv` |

Location adds `country`, `state`, `pinPrefix` (3 digits). Demand adds `rank` = `units` (default) · `revenue` and
`by` = `product` (default) · `variant`.

Invalid values → 400 with the same message style as `/summary` (`compare: unknown value`).

### 3.2 Response envelope

```json
{ "success": true, "section": {
    "from": "2026-09-21", "to": "2026-10-20", "compareFrom": "2026-08-22", "compareTo": "2026-09-20",
    "timezone": "Asia/Kolkata", "granularity": "day", ...section fields } }
```

`compareFrom`/`compareTo` are null when `compare=none`; every `change` field is then null. Change % uses the existing
`MonthBuckets.percentChange` rules.

### 3.3 CSV

`?format=csv` returns `text/csv; charset=utf-8`, `Content-Disposition: attachment; filename="<section>-<from>_<to>.csv"`,
header row first, values RFC-4180 quoted, money as plain decimals in the store currency. Each section names its CSV
table below. Cells starting with `= + - @` are prefixed with `'` (spreadsheet formula injection). One shared
`CsvWriter` in `dashboard.web`.

### 3.4 Backend structure

- `dashboard.domain`: one record per section (`OverviewSection`, `SalesSection`, … ) and small shared value types
  (`Ranked<T>`, `Change`). Pure calculations (percentiles, trending filter, stock cover, state normalising) live here
  and are unit-tested without Mongo.
- `dashboard.application`: one service per section, `@Cacheable(DASHBOARD)` keyed by section + tenant + all params
  (the tenant key prefix the existing cache already applies). Evicted by the existing `DashboardCacheEvictor`.
- `DashboardReadPort`: new read methods (listed per section), implemented as aggregations in
  `MongoDashboardReadAdapter`, each starting with the tenant match the adapter already uses.
- `DashboardController` gets the seven routes; parameter parsing moves into a small `SectionParams` record so the
  routes stay one line each.

### 3.5 Frontend structure

- `pages/admin/analytics.tsx` becomes the shell: the existing filter toolbar plus a tab bar; active tab in `?tab=`
  (default `overview`), alongside the existing URL filters.
- One component per tab in `components/admin/analytics/` (`OverviewTab.tsx`, …), each with its own RTK Query hook in
  `dashboardAPI.ts`, so a tab fetches only when shown. Shared `ChangeBadge`, `RankedTable`, `CsvButton`.
- The page stops calling `/stats`, `/pie`, `/bar`, `/line`; those stay for the main dashboard page. Gender and age
  charts are removed from Analytics.
- Admin help article for Analytics updated in `help/adminGuides.ts`.
- `.admin-main` hides overflow, so the tab body is its own scroll container (`flex:1; min-height:0;
  overflow-y:auto`).

## 4. Store settings

| Setting | Where | Fallback | Notes |
|---|---|---|---|
| Low-stock threshold | existing `Catalog.lowStockThreshold` + per-product override | 5 | reused, no change |
| Stuck-order days | **new** `Commerce.stuckOrderDays` (Integer, 1–60) | 3 (`StoreDefaults`) | editable on Store settings page, audited like the other fields |

The "days of cover" look-back window (30 days) and the trending / small-group minimums (3) are fixed analysis rules,
not shop rules; they are constants in `dashboard.domain` and stated in the UI.

## 5. Sections

### 5.1 Overview

- `kpis`, `previous`, `change`, `series`: exactly `SummaryService.summary(...)` (reused, not duplicated).
- `byWeekday[7]` (Mon..Sun): `{orders, revenue}`; `byHour[24]`: `{orders}` — store-local, from order `createdAt`.
- UI: KPI row, revenue & orders chart, weekday × hour heatmap (orders).
- Port: `ordersByWeekdayHour(scope, from, to, zone)` → 7×24 counts and revenue.
- CSV: one row per series bucket (`start, revenue, orders, units, averageOrderValue`).

### 5.2 Sales

- `series` with `previousSeries` aligned by bucket index (for the comparison line).
- `byCategory[]`: `{category, revenue, units, share, change}`; item-level, so it works with the category filter.
- `breakdown`: `{grossSales, discount, tax, shipping, refunds, net}` for the period, plus `couponOrders` and
  `couponOrderShare`. Replaces the all-time "Revenue distribution" chart.
- `byPaymentMethod[]`: `{method, orders, revenue, share}`; null method → `"unknown"`. Hidden when narrowed to a
  category (cannot be split by item).
- Port: `salesByCategory`, `orderBreakdown`, `ordersByPaymentMethod`.
- CSV: one row per series bucket with all breakdown columns.

### 5.3 Orders

- `series` (orders) with `previousSeries`; `byStatus{processing, shipped, delivered}` for orders placed in the period.
- `fulfilment`: for each leg `placedToShipped`, `shippedToDelivered`, `placedToDelivered` → `{medianHours, p90Hours,
  count}`, plus `withoutHistory` (orders skipped for having no history) and a `placedToDelivered` median per bucket.
  Percentiles computed in Java from the per-order durations (period-bounded, at most a few thousand values).
- `stuck[]`: PROCESSING for more than `stuckOrderDays`, oldest first, `{id, orderNumber, placedAt, ageDays, total}`,
  capped at `limit`; `stuckCount` is the full count.
- `size`: `averageItems`, bands `{"1", "2-3", "4-5", "6+"}` by item quantity, `topCoupons[] {code, orders, discount}`.
- `refunds`: `{orders, amount, rate}` where rate = refunded orders ÷ orders.
- Port: `orderDurations(scope, from, to)`, `stuckOrders(scope, olderThan, limit)`, `orderSizes`, `couponUsage`,
  `refundSummary`.
- CSV: one row per order in the period (`orderNumber, placedAt, status, items, total, discount, paymentMethod,
  hoursToShip, hoursToDeliver`), capped at 10 000 rows with a trailing note row if cut.

### 5.4 Stock (current state)

- Lines are counted the `StockAlert` way: a product without variants, or each ACTIVE variant; DRAFT/ARCHIVED products
  and DISABLED variants are excluded.
- `counts{inStock, low, out, lines}` using each line's effective threshold (product → store → platform).
- `value{total, byCategory[]}` = Σ stock × selling price.
- `low[]`: `{productId, variantId, name, label, sku, stock, threshold, unitsPerDay, daysLeft}` sorted by `daysLeft`
  (null last), capped at `limit`. `unitsPerDay` = units sold in the last 30 store-local days ÷ 30.
- `out[]`: same identity fields plus `lastSoldAt` (latest order containing the line; null = never sold).
- Port: reuse the product-side stock alert query if it is reachable through a module API, otherwise
  `stockLines(scope)`; plus `unitsSoldSince(scope, from)` per product/variant and `lastSoldAt(lines)`.
- CSV: every line (`name, variant, sku, category, stock, threshold, price, value, unitsPerDay, daysLeft`).

### 5.5 Customers

- `newCustomers` series (accounts created, CUSTOMER role) with `change`.
- `buyers{new, returning, newRevenue, returningRevenue}`: a buyer is *new* if their first-ever order is inside the
  period, else *returning*.
- `repeatRate` (customers with ≥ 2 orders, all time ÷ customers with ≥ 1) and `ordersPerCustomer`.
- `top[]`: `{userId, name, orders, spent, lastOrderAt}` by spend in the period, linked to the user page.
- `neverOrdered`: accounts created in the period with no order.
- Port: `newUsers` (reuse `monthly`/count style with buckets), `buyerSplit`, `repeatStats`, `topCustomers`,
  `signupsWithoutOrders`.
- CSV: top customers only — `name, orders, spent, lastOrderAt`. No email, phone or address.

### 5.6 Demand

- `top[]` ranked by `rank`, grouped by `by`: `{productId, variantId?, name, label?, units, revenue, orders, share,
  change, removed}`. `removed` = product no longer exists (name from the order snapshot).
- `trending{rising[], falling[]}`: needs ≥ 3 units in either period; none before → `isNew: true`, change null.
- `notSelling[]`: ACTIVE lines with stock > 0 and zero units in the period, by stock value desc.
- `cover[]`: for `top`, `unitsPerDay` and `daysLeft` (same function as Stock); `reorderSoon` when `daysLeft` ≤ the
  length of the compare window (or 30 days when `compare=none`).
- `boughtTogether[]`: product pairs `{a, b, orders}` in the period with ≥ 3 shared orders, top 10. Done in Mongo by
  unwinding distinct product ids per order and self-pairing; orders with more than 20 distinct products are skipped to
  bound the work.
- Port: `productSales(scope, from, to, by)`, `idleStock(scope, from, to)`, `productPairs(scope, from, to, min, limit)`.
- CSV: one row per product/variant (`name, variant, units, revenue, orders, change, stock, daysLeft`).

### 5.7 Location

- Drill levels: no filter → by country; `country=India` → by state; `+state` → by 3-digit PIN prefix; `+pinPrefix` → by
  6-digit PIN (JSON only). Outside India the drill stops at country.
- Each row `{key, label, orders, revenue, averageOrderValue, share, change, small}`; `small` = fewer than 3 orders in
  the current period → shown greyed, change omitted, excluded from trending.
- `topProducts[]` (5) for the selected region.
- `unrecognised{orders, samples[{raw, orders}] (10)}` at the state level.
- **`IndianStates`** (in `shared`, used by dashboard and order modules): the 28 states and 8 union territories with
  official names, ISO 3166-2:IN codes, and common variants/abbreviations; `normalise(String) → Optional<State>` ignoring
  case, punctuation and extra spaces. Analytics groups by normalised state at read time (raw values fetched grouped,
  normalised in Java, so no data migration).
- **Checkout change** (separate commit): when country is India, `state` becomes a `<select>` on shipping and billing
  forms, fed from `GET /api/v1/reference/indian-states` (or a bundled frontend copy generated from the same list —
  decided in the plan). The backend validates Indian addresses against `IndianStates` and stores the official name;
  other countries keep free text. `Invoice.sameState` switches to comparing normalised states, and the store's
  `Invoice.state` setting is validated the same way.
- Port: `ordersByRegion(scope, from, to, level, filter)` returning raw grouping keys, `topProductsInRegion`.
- CSV: country / state / 3-digit prefix rows only — never full PIN codes.

## 6. Data access and indexes

- Every aggregation starts `{$match: {tenantId, createdAt: {$gte, $lt}}}`; existing `{tenantId, createdAt}` on
  `orders` covers it. Item-level work `$unwind`s `items` after the match.
- New indexes (created by the existing index initialiser): `orders {tenantId, "shippingInfo.state", createdAt}`,
  `orders {tenantId, status, createdAt}` (stuck orders), `orders {tenantId, userId, createdAt}` (first-order and repeat
  stats) — the plan checks which already exist before adding.
- Customer first-order lookups use a single `$group` by `userId` with `$min: createdAt` over the tenant's orders; fine
  at current scale, and the step-2 daily table is the planned relief if it is not.
- All money sums use the same fields as `/summary` so totals agree across tabs; an integration test asserts
  Overview revenue = Sales `breakdown.net` (net basis) for the same filters.

## 7. Testing

TDD per section, red first:

- **Domain unit tests:** percentile maths, trending filter (min 3, new items), stock cover and `reorderSoon`, size
  bands, `IndianStates.normalise` (official names, codes, case/spacing, old names like "Orissa", unknown → empty),
  CSV escaping including formula-injection prefixes.
- **Integration tests** (`it/`, Testcontainers like `DashboardSummaryIntegrationTest`), one per section: seeded orders
  across two periods and two tenants; asserts numbers, comparison, category narrowing, tenant isolation, `limit`
  bounds, 400s on bad params, CSV headers and content type, Location never exporting full PINs.
- **Settings:** `stuckOrderDays` validation, fallback, audit entry (extends `StoreSettingsIntegrationTest`).
- **Checkout:** Indian address with an unknown state rejected; variant spelling stored as the official name; non-India
  free text accepted; invoice same-state now true for "karnataka" vs "KA".
- **Frontend:** `node --experimental-strip-types` tests for tab/URL state and any pure helpers; a Playwright pass over
  every tab at 1280px and phone width, scrolling to the last control.

## 8. Decisions taken

- D1 Duration = Overview tab with period totals and time-of-week breakdowns (user choice).
- D2 Gender and age charts dropped from Analytics (user choice).
- D3 One endpoint per section (user choice, option 1 of 3).
- D4 Trends live inside each tab; no Trends tab.
- D5 State: normalise at read time and switch Indian checkout to a dropdown (user choice).
- D6 Stuck-order days is a store setting; analysis constants (30-day cover window, minimum 3) are not.
- D7 To record as **ADR-024** in `docs/foundations/architecture.md` when the first section lands; endpoint contracts
  go into `docs/foundations/contracts.md` section by section.

## 9. Build order

1. Shared: `SectionParams`, envelope, `CsvWriter`, tab shell + URL tab state.
2. Overview → 3. Sales → 4. Orders (+ `stuckOrderDays` setting) → 5. Stock → 6. Customers → 7. Demand →
8. Location (+ `IndianStates`) → 9. Checkout state dropdown + invoice same-state fix (own commit) →
10. Help article, contracts, ADR-024.

Each step ends green (backend `mvn verify`, frontend tests and build) and is committed on its own.
