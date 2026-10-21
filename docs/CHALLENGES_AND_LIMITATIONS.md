# Challenges, limitations and known problems

As of 2026-10-20, on `dev`. Covers the per-store settings (ADR-023), analytics steps 1–3 (ADR-024, ADR-025) and the
integration work that brought the feature branches together on `dev`. The short, standing list of accepted limits
stays in `docs/foundations/requirements.md` ("Known limits"); this file is the longer record of what is wrong, what is
limited on purpose, and what was hard.

## 1. Known problems (should be fixed)

| # | Problem | Effect | Where | Suggested fix |
|---|---|---|---|---|
| P1 | The GST report does not deduct refunds; there are no credit notes. | GST is overstated for any month with refunds. Filing from it as-is over-pays. | `SectionsService.tax`, `/dashboard/tax` | Issue a credit note per refund (number, rate split) and net it in the month the refund was issued. |
| P2 | Shipping is never taxed, and so never in the GST report. | In India shipping charged with goods usually takes the goods' GST rate. Invoices and the report may under-charge tax. | `PriceCalculator`, `Invoice`, tax section | Decide the rule with an accountant, then tax shipping at the order's main rate in all three places. |
| P3 | Same-state (CGST+SGST) or IGST is decided by comparing state names as text. | "KA" and "Karnataka" differ, so a typo in the store's state or a buyer's address files the tax under the wrong heads. | `Invoice.sameState`, `SectionsService.sameState` | Store a state code (GST state code or ISO 3166-2:IN) on addresses and on the store; pick from a list instead of free text. |
| P4 | Cancelled orders still count as revenue. | The plan defines net revenue as paid − refunds − cancelled; the code uses gross − refunds. A cancelled, unrefunded order inflates revenue. | Metric definitions in `contracts.md`, `Kpis`, daily summary | Decide whether cancellation always produces a refund. If not, exclude `Cancelled` orders in the aggregations and rebuild the daily summary. |
| P5 | Orders do not record their currency. | The daily summary stamps the store's current currency on every row. If a platform admin changes a store's currency, old and new money mix. Pricing is in paise, which assumes a 2-decimal currency. | `orders`, `DailySummaryJob`, `PriceCalculator` | Write `currency` on each order at checkout; summarise by it; refuse a currency change on a store with orders, or convert explicitly. |
| P6 | Analytics sections run live aggregations over every order in the range. | Fine at today's volume. Slows down as a store grows, mainly for a year-long range. | `MongoSectionsReadAdapter` | Add a compound index `{tenantId: 1, createdAt: 1}` on `orders`, and move the heavy sections onto summary rows when needed. |
| P7 | Changes to orders older than 30 days do not reach the daily summary, and there is no button to rebuild it. | Refunds are fine, because they count on the day they are issued. An edited or deleted old order keeps its stored totals. | `DailySummaryJob`, `RefreshPlan` | Add a platform action to rebuild one store's summary, or trigger a rebuild from order-change events for old days. |
| P8 | The daily summary job assumes one backend instance (ADR-008). | Two instances would run it at the same time. The second run's inserts can hit the unique index; that store is logged as failed and fixed on the next run. | `DailySummaryJob` | Add a lock (ShedLock or a lease document) before scaling the backend out. |
| P9 | Failed or abandoned payments are not stored as orders. | The plan's payment-failure rate (Orders section) cannot be computed. Only `payment_intents` hint at abandoned checkouts. | `CheckoutService`, `payment_intents` | Record payment attempts with outcome and gateway reason (Razorpay webhooks), then report on them. |
| P10 | An admin who has not yet accepted the admin agreement gets 403s from public endpoints. | Seen in the browser: `GET /product/latest` returned 403 on the home page right after an admin logged in, before they accepted. Most likely `AdminAgreementInterceptor`, not confirmed. | `AdminAgreementInterceptor` | Limit the agreement check to admin-only endpoints, or exempt public reads. |
| P11 | The audit log's filter list lacks `TENANT_EXPORTED` and `TENANT_PURGED`. | Those entries exist but cannot be picked in the Action filter. | `auditAPI.ts` `AUDIT_ACTIONS` | Add them, or serve the list from the backend so the two can't drift. |
| P12 | Some help and admin texts still say ₹, even though each store has its own currency. | Wrong wording for a non-INR store. Form labels and money already follow the store's currency. | `help/adminGuides.ts`, `help/articles.ts` | Render those strings with `currencySymbol()`, as the forms do. |
| P13 | Two charts put two scales on one chart (dual axis): Revenue/Orders on the dashboard, and refunds in Payments. | Easy to misread: the lines' crossing points mean nothing, and the two scales invite false comparisons. | `analytics.tsx` (`dualAxis`), `AnalyticsSections.tsx` | Split each into two charts sharing the time axis, or index both to a common base. |

## 2. Limitations by design (accepted for now)

**Look and feel**
- **No dark mode** on the store or the admin panel; `color-scheme` is fixed to light. The token sets in `styles/_tokens.scss` are ready for a `[data-theme="dark"]` block, which needs its own contrast-tested values (`styles/tokens.test.ts`).
- **Chart series 3–5 (aqua, yellow, pink) sit under 3:1 on white.** Charts keep legends and labels, so colour is never the only cue.
- **A store's theme can only recolour its storefront.** The admin panel keeps its own colours by design (ADR-026).

**Store settings**
- **Currency and locale are set by the platform admin, not the store owner.** Changing them would mix currencies across existing orders (see P5).
- **Minimum shopper age can only be raised above 18,** never lowered. 18 is the legal floor under DPDP.
- **The order number prefix is fixed at `ORD-`.** The invoice number is derived from it.
- **The invoice seller is the store's storefront details plus its GSTIN and state.** Only the default store falls back to `INVOICE_SELLER_*`, so a new store's invoice is bare until its owner fills in Store settings.
- **Platform safety limits stay platform-owned:** variant, CSV and size-chart caps, JWT, rate limits, token lifetimes and data retention.

**Analytics**
- **Time zone changes:** after a store changes its zone, whole-store figures are read live until the next nightly run rebuilds the table (up to a day). They are correct, just slower.
- **"Today" is always live.** The table covers up to yesterday, and product, category and customer views never use it.
- **Revenue on the section tabs is gross:** the value of orders placed. Net revenue (after refunds) exists only on the Overview cards and trend.
- **Category share uses each product's current category,** not its category at the time of sale.
- **Days of stock left uses the chosen range's selling rate.** A short or unusual range (a sale week) skews it.
- **"Not sold in this range" includes products added part-way through the range.** It leaves out products created after the range ended.
- **Customer names and emails come from the order snapshot,** so a customer shows the details on their latest order in the range. Erased accounts show as "Erased account".
- **Order lines saved before each line carried its tax rate count at 18%,** in the tax report and on invoices.
- **Top lists show 20 rows (places: 50).** The CSV has every row.
- **The plan's Orders section isn't built yet:** status breakdown, payment failure rate, days to ship. The super admin view (steps 4–5) and visits/conversion (step 6) aren't built either.

**Development environment**
- **The local docker stack does not rebuild after a merge.** Run `docker compose up -d --build backend frontend` in `../6Pack-dev`.
- **Nothing is pushed.** No pull requests are open and CI has not run on these branches; `dev` is a local integration branch.
- **ADR numbers repeat on `dev`** (ADR-019 twice, ADR-020 three times, ADR-021 twice), because each feature branch numbered its own. ADR-022 to ADR-025 are unique.

## 3. Challenges met while building, and how they were handled

| Challenge | What happened | How it was handled |
|---|---|---|
| Several sessions sharing one checkout | Other sessions switch branches in `6Pack`, so commits can land on the wrong branch. Finished audit and help-page work was sitting uncommitted on `main`. | Each feature was built in its own git worktree. The `main` edits were copied with `git diff \| git apply` onto `fix/audit-paging-help` and merged from there, without touching `main`. |
| Which branch to build on | Invoices, coupon scope and quotes exist only after the loose-ends merge, and the plan said to branch off `feat/tenancy-hardening`. | Store settings and analytics were branched off `dev`, which has everything merged. |
| Merge conflicts | `AuditController` had been refactored into helpers on `dev`. The dashboard controller, port and API grew in two branches. `package.json` got two `"test"` scripts (`node:test` and Vitest). | Kept both sides. Added size overloads to the shared audit helpers. Kept Vitest and moved the date-range test onto it. Every conflict was resolved with tests run on the merge commit. |
| Module boundaries (Spring Modulith) | The dashboard may not read the tenant module's internals or the product module's rules. | Shared ports `StoreConfig` (rules, zone, currency, seller) and `StoreDirectory` (stores to iterate). Low stock goes through `ProductCatalog.lowStock()`. |
| Tenant isolation in joins | `TenantMongo` adds the tenant filter only to the collection being aggregated, not to a `$lookup`. | The category lookup matches `tenantId` explicitly. ADR-025 records the rule for future joins. |
| Keeping invoices stable | A store changing its default GST rate would have re-priced old invoices. | Checkout writes the rate charged on each order line. Only old lines without one fall back to 18%. |
| No platform GSTIN on other stores' invoices | One set of `INVOICE_SELLER_*` printed the same seller for every store. | The seller comes from each store's own details. Only the default store falls back to the env vars, the same rule as platform payment keys. |
| Admin pages clip their content | The admin main area has `overflow: hidden`. The Store settings page was not scrollable, and a 900px screenshot hid it. The user reported it. | The page is its own scroll area, as the help centre is. Browser checks now scroll to the last control at 1280px and 390px widths. |
| A Spring Data trap | `Criteria.where(f).ne(null).ne("")` keeps only the last `$ne`. Orders with `couponCode: null` counted as coupon uses. Integration data left the field out, so tests passed. | A browser run on seeded data showed it. The test now stores `null`, and the filter uses one `$nin`. Searched the code for the same pattern: none. |
| Cache eviction from inside a job | `@CacheEvict` on a method called from the same class is skipped (Spring proxies). | The job clears the dashboard cache through `CacheManager` after each run. |
| Tests and the clock | The daily-summary job and "today" depend on the real date and on a startup run. | The job takes `now` as a parameter. Tests turn off the startup run (`app.analytics.summary.startup-delay=-1s`) and call it themselves. |
| Checking in a real browser without touching user data | The user's docker stack runs on 8080/8081/27017 with real data. | Each check used a throwaway Mongo container (27118), a backend on 8095 and Vite on 5173, all stopped afterwards. Once, a `pkill -f` pattern matched its own shell and killed it; processes are now stopped by PID. |
| Tests that pass for the wrong reason | An integration test can pass without checking the behaviour it names. | The store-settings and daily-summary suites were mutation-checked (seller fallback forced on, rate stamping removed, table reads turned off), and the scroll check was run without the fix; each failed as expected. The sections suite was checked against a seeded browser run instead, which is how the coupon bug surfaced. |

## 4. Where to look

- Decisions: `docs/foundations/architecture.md`, ADR-023 to ADR-025.
- API and metric definitions: `docs/foundations/contracts.md` ("Dashboard").
- Plan and remaining steps: the "6Pack analytics plan" doc.
