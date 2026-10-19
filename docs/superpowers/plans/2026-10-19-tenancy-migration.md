# Multi-tenancy migration (steps 3 to 8) — plan 6

**Goal:** Finish multi-tenancy on top of the foundation (plan 5): move every tenant-owned collection onto `TenantMongo`, make users, consents and login per tenant, give each tenant its own configuration (legal versions, payment keys, mail links), add a platform administrator, let the storefront render per tenant, and ship the migration and a cross-tenant leak test suite.

**Base:** `feat/tenancy-foundation` merged with `main` (audit log, admin agreement, variants backend). Baseline: 255 backend tests green.

**Spec:** `docs/superpowers/specs/2026-10-18-multitenancy-design.md` (§3 isolation levels, §5 steps 3 to 8, §8 assumptions). Questions 1, 4, 5, 7, 14 were not answered; the defaults in §8.2 apply: each tenant has its own customers, own legal text, own Razorpay keys, a handful of tenants created by a script, and today's data becomes the `default` tenant.

## Decisions made for this plan

1. **Ownership.** Every tenant-owned document class implements `TenantOwned` (a `tenantId` field) and gets a `BackfillTarget` bean. Reads and writes go through `TenantMongo`. The guard allowlist loses one entry per migrated repository.
2. **Unique indexes become per tenant.** Each old single-field unique index is replaced by a compound index starting with `tenantId`; optional fields (slug, sku, order number, variant sku) use a partial filter (`$type: string`) so absent values never collide. A startup step (`LegacyIndexCleanup`) drops the old index names if they exist, because `auto-index-creation` only adds indexes. It is idempotent.
3. **Direct repository calls need a tenant.** `TenantMongo` throws without one. `IntegrationTest` binds the default tenant for the test thread (setup/teardown), and tests that insert raw legacy documents call `backfill()` afterwards, which is exactly what a real migration does.
4. **Platform administrators are not users.** They live in their own global collection `platform_admins`, log in at `/platform/login` (a path that runs without a tenant), and get a token with role `PLATFORM_ADMIN` and no `tid`. That token is rejected on tenant paths. They manage tenants through `/platform/**` and have no access to tenant data.
5. **Per-tenant configuration with a safe fallback.** `Tenant` gains optional `legal`, `payment` and `mail` settings. A small `TenantSettings` port in `shared.tenant` returns the tenant's value, or the existing `app.*` property when the tenant has none, so the `default` tenant keeps working with today's environment variables. Payment secrets are never stored in Mongo: the tenant stores a `secretRef`, resolved from the environment (`TENANT_SECRET_<REF>`).
6. **Caches are keyed by tenant in one place.** A `CacheManager` decorator prefixes every key with the tenant id and fails closed without a tenant; `allEntries` evictions clear all tenants (harmless, a little wasteful).
7. **Uploads.** New files go to `uploads/<tenantId>/<name>`. Existing files stay where they are and remain valid for the default tenant's records.
8. **Rate limits** key on tenant + IP + path.
9. **Not in this plan:** cross-tenant platform dashboards and a cross-tenant audit view (Q8, Q17 stay open; audit entries are stamped with the tenant so adding the view later is a read-only change), self-serve signup, custom-domain verification, dedicated databases, object storage.

## Steps

### Step A — Catalogue: products, categories
- `ProductDocument`/`CategoryDocument` implement `TenantOwned`; replace the unique indexes on `slug`, `sku` (and the variants' sku index) with `(tenantId, …)` partial compound indexes; `BackfillTarget` beans for `products`, `categories`.
- `MongoProductRepository` and `MongoCategoryRepository` use `TenantMongo`, including the per-variant atomic stock updates and the revision-checked variant writes.
- Cache manager decorator (Decision 6); upload path per tenant (Decision 7).
- Guard allowlist: remove both repositories.
- Tests: all existing catalogue tests unchanged; new two-tenant tests: same slug/sku/category name in both tenants, a product and its variants invisible across tenants (list, by id, search, admin list, update, delete, stock reserve), cache separation.

### Step B — Commerce: coupons, redemptions, orders, payment intents, dashboard
- Documents implement `TenantOwned`; unique indexes → `(tenantId, coupon)`, `(tenantId, orderNumber)` (partial), `(tenantId, providerOrderId)`, `(tenantId, couponId, userId)`; backfill targets for `coupons`, `coupon_redemptions`, `orders`, `payment_intents`.
- Repositories on `TenantMongo`; `TenantMongo.aggregate` (prepends a tenant `$match`) for the dashboard adapter.
- Tests: the same coupon code and order-number shape in both tenants, an order never visible to the other tenant's admin or customer, redemptions counted per tenant, dashboard totals per tenant.

### Step C — Identity: users, reset tokens, consents, audit
- `users`: unique `(tenantId, email)`; `password_reset_tokens`; `consents`; `audit_log` (stamped from the context) on `TenantMongo`; backfill targets.
- `AdminBootstrap` runs as the default tenant. Rate limiter keys include the tenant.
- Reset links and erasure work per tenant (events run on the request thread, so the context is bound).
- Tests: the same email registers in two tenants, logging in on the wrong host fails, a reset token only works on its own tenant, erasure removes only that tenant's data, an admin of one tenant cannot see or change another's users, audit entries carry the tenant.
- Gate: after this step a second real tenant is safe to create.

### Step D — Per-tenant configuration
- `Tenant.legal` (terms/privacy/admin-agreement versions), `Tenant.payment` (provider, key id, `secretRef`), `Tenant.mail` (link base URL); `TenantSettings` port with env fallback (Decision 5).
- `ConsentService` takes versions from `TenantSettings`; `CheckoutService` obtains the gateway from a `PaymentGatewayResolver`; reset links use the tenant's base URL.
- Tests: two tenants with different policy versions (accepting one does not satisfy the other), per-tenant gateway selection with the fake provider, link base per tenant, fallback for a tenant with no settings.

### Step E — Platform administrator
- `platform_admins` collection, bootstrap from `PLATFORM_ADMIN_EMAIL`/`PLATFORM_ADMIN_PASSWORD`, `POST /platform/login`, `GET/POST/PUT /platform/tenants` (create, edit settings, suspend, reactivate, validated domains), role `PLATFORM_ADMIN` rejected on tenant paths.
- Tests: platform login, tenant lifecycle, a tenant admin token is rejected on `/platform/**`, a platform token is rejected on tenant paths, domain uniqueness.

### Step F — Storefront and admin app
- The SPA fetches `/tenant/config` before first render and applies theme tokens, name and logo; business details in the legal pages come from the tenant. A small platform admin screen lists and edits tenants.

### Step G — Migration, leak suite, rollout
- `LegacyIndexCleanup`, a repeatable backfill, and a rehearsal checklist (restore a copy of production, start the new build, verify counts per collection).
- `TenantIsolationIntegrationTest`: one scenario matrix over every entity with two tenants.
- Docs: ADR-021, contracts, requirements, compliance retention, test-rollout, `.env.example`, k8s config notes.

## Review focus

1. No code path can read or write tenant data without the wrapper (guard allowlist ends empty apart from global data).
2. Unique constraints are per tenant, and the old global ones are really gone after startup.
3. A user, token, reset link, cache entry or uploaded file never crosses tenants.
4. The `default` tenant behaves exactly like today with no new configuration.
5. A platform token never works on tenant data, and a tenant token never works on `/platform/**`.
