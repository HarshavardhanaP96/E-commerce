# Richer entities — design (multi-tenancy parked)

Status: approved scope, 2026-10-17 · Multi-tenancy is a **future plan** (Appendix A). It is not built now, and `vision.md` keeps it as a non-goal.

## 1. Intent
Entities gain the fields a real store needs (catalogue, customer addresses, order lifecycle, richer coupons).
Changes are additive and backward compatible, so the current frontend and existing `Ecommerce_2024` data keep
working. Design choices avoid blocking a later tenant migration (no global unique assumptions beyond what exists
today, no hidden singletons).

## 2. Non-goals
Multi-tenancy (Appendix A), product variants (Tier 2), shipment carrier integrations, refunds/returns,
invoice PDFs.

## 3. Approach
Add fields to the existing domain records and Mongo documents. Keep JSON names and `_id` (ADR-004). New fields
are optional on read: old documents load with null/default values, so **no data migration is required**. Only new
indexes are added. `Category` becomes its own collection; the existing free-text `category` string stays readable
and is backfilled lazily by an optional script.

## 4. Entities
Field names and `_id` stay compatible (ADR-004). New fields are additive and optional on read, so the current
frontend keeps working during rollout.

| Entity | Tier 1 (with migration) | Tier 2 (later) |
|---|---|---|
| Product | `slug, sku, description, brand, images[], mrp, price, taxRate, hsn, status (DRAFT\|ACTIVE\|ARCHIVED), tags[], categoryId` | variants (size/colour) with per-variant stock and price, weight/dimensions, rating aggregate, SEO fields |
| Category (new collection) | `name, slug, parentId, image` | sort order, attributes |
| User | `phone, emailVerified, status, lastLoginAt, addresses[], locale, marketing prefs` | loyalty points, saved payment prefs |
| Address (embedded) | `label, name, phone, line1, line2, city, state, country, pinCode, isDefault` | geo, GSTIN |
| Order | `orderNumber, shippingAddress, billingAddress, customer email/phone snapshot, statusHistory[{status,at,actor}], items[].sku/tax, discount, couponCode, notes` | shipments and tracking, refunds/returns, invoice number and PDF |
| PaymentInfo | `method, status, amount, currency, paidAt, failureReason` | refund records, settlement info |
| Coupon | `type (FLAT\|PERCENT), maxDiscount, minOrderValue, startsAt, expiresAt, usageLimit, usedCount, perUserLimit, active` | product/category scoping, first-order-only |

Open decision: **variants**. They are the largest scope driver in the product model and are parked in Tier 2
until confirmed. `PriceCalculator`, stock handling and `OrderItem` all change if they move to Tier 1.

## 5. Delivery order
Each step ships and tests independently.
1. Product + Category Tier 1 (backend, admin form, store pages).
2. User/Address (profile, checkout address picker).
3. Order + PaymentInfo Tier 1 (orderNumber, addresses, statusHistory, discount).
4. Coupon richness (types, limits, validity) in `PriceCalculator`.
5. Tier 2 items as needed.

## 6. Testing
- Per entity: domain unit tests for new rules, Mongo Testcontainers round-trip, and a legacy-document test (old shape loads with defaults).
- `PriceCalculator`: percent/flat, `maxDiscount`, `minOrderValue`, validity window, usage limits.
- Contract tests: existing endpoints return the old fields unchanged.

## 7. Risks
- Coupon usage counters can race under concurrent checkouts: use an atomic `$inc` with a limit guard.
- `statusHistory` must be append-only and written with the status change in one update.
- Slug/SKU uniqueness: create indexes sparse/partial so legacy documents without them don't collide.

## 8. Docs to update
`contracts.md` (new fields and endpoints), `requirements.md`, `architecture.md` (new ADR for the field model,
`category` collection), `test-rollout.md`.

## Appendix A. Future plan: multi-tenancy
Parked. Direction already agreed: isolated SaaS stores (separate customers, admins, branding, one or more
domains each), a handful of tenants created by the platform owner, shared database with `tenantId` on every
document and compound unique indexes, tenant resolved from `X-Forwarded-Host`, JWT `tid` claim,
`PLATFORM_ADMIN` role, per-tenant theme/legal/payment config, one SPA build. Full draft is preserved below and
is to be re-reviewed before any work starts.

### Draft (unreviewed)
### 4.1 Tenant model
New module `tenant`, collection `tenants`:
`id, slug, name, status (ACTIVE|SUSPENDED), domains[], theme{tokens, logoUrl, faviconUrl}, locale, currency,
legal{termsVersion, privacyVersion, grievanceOfficer}, payment{provider, keyId, secretRef}, settings{shipping,
taxInclusive, supportEmail, supportPhone}, features{}, createdAt`.
The Razorpay secret is never stored in the document: `secretRef` points at a sealed secret (ADR-012).

### 4.2 Resolution
A filter ordered before Spring Security reads `X-Forwarded-Host` (ADR-011 already forwards it), looks up
`domain -> tenantId` in a cached map (invalidated on tenant change) and sets a request-scoped `TenantContext`.
Unknown host: 404. `SUSPENDED` tenant: 403. Platform endpoints (`/platform/**`) skip tenant resolution.

### 4.3 Enforcement
- Every tenant-owned document gets `tenantId` (required, indexed).
- All reads and writes go through a tenant-scoped repository base class that adds the filter from
  `TenantContext`. Raw `MongoTemplate` use outside it is forbidden, and a Modulith/ArchUnit test enforces that.
- Unique indexes become compound: `(tenantId,email)`, `(tenantId,code)` for coupons, `(tenantId,slug)` and
  `(tenantId,sku)` for products, `(tenantId,orderNumber)` for orders.
- Background work (events, cache eviction, dashboard) carries `tenantId` explicitly. There is no ambient
  context off the request thread.

### 4.4 Security
- JWT gains claim `tid`. A token whose `tid` differs from the resolved tenant is rejected (401).
- Roles: `USER`, `ADMIN` (scoped to their tenant), and new `PLATFORM_ADMIN` (no tenant, manages tenants through
  `/platform/**`).
- `AuthRateLimitFilter` keys on tenant + IP.

### 4.5 Cross-cutting
Caffeine keys are prefixed with the tenant (ADR-007). Uploads go to `uploads/{tenantId}/...` (ADR-008).
Dashboard queries are tenant-scoped. Consent log and erasure are per tenant. Checkout uses the tenant's
Razorpay credentials and currency. Metrics, traces and logs carry a `tenant` label/attribute.

### 4.6 Frontend
One build. At boot the SPA calls `GET /tenant/config` (public) and applies `theme` to the CSS tokens
(ADR-013), plus name, logo, legal links, currency and feature flags. Hard-coded `BUSINESS` constants and
`legal/policies.ts` values move to tenant config. Different domains render differently with no extra deploys.

### 4.7 Migration
Idempotent script, run after a backup: create the `default` tenant from current env/config, backfill
`tenantId` on every collection, create compound indexes, then drop the old single-field unique indexes.
New fields (§5) are optional on read, so the migration needs no per-document rewrite beyond `tenantId`.

