# Architecture & Decision Log

## Overview

```
Browser ──► nginx (ecommerce-frontend: static SPA + reverse proxy /api, /uploads)
                 │
                 ▼
          backend (Spring Boot 3.5, Java 21, modular monolith) ──► MongoDB (Ecommerce_2024)
                 │                                         └──► Razorpay REST API
                 └── /actuator/prometheus ◄── Prometheus ──► Grafana
                 └── OTLP traces ─────────► Jaeger (or Tempo/any OTLP collector)
```

### Backend module layout (`backend/src/main/java/com/sixpack/`)
Each module is a direct sub-package. Its **root package is its public API**: interfaces and records that
other modules may use. Everything in its sub-packages is internal, and Spring Modulith enforces this with
`ModularityTests` at build time.

| Module      | Public API (root package)                         | Internals |
|-------------|---------------------------------------------------|-----------|
| `shared`    | (open module) error model, security config, JWT, rate limit, web config; `shared.tenant`: `TenantContext`, `TenantMongo`, `TenantOwned`, `BackfillTarget` | — |
| `tenant`    | — (others use `shared.tenant`)                    | tenants and platform administrators (platform-global data), host-to-tenant registry, per-request resolution filter, default tenant bootstrap, backfill, legacy index cleanup, tenant settings, `/tenant/config` and `/platform/**` |
| `user`      | `UserDirectory` (look up name / exists), `UserDeletedEvent`, `AccountDeletionGuard` (other modules can veto a deletion) | `web` controllers + DTOs, `application` services, `domain` model + ports, `infra` Mongo + mail adapters |
| `product`   | `ProductCatalog` (snapshot, reserve and release stock) | same layering; also owns `Category` (`/category`) |
| `coupon`    | `CouponLookup` (discount for a code)              | same layering |
| `payment`   | `PaymentGateway` (create order, verify signature) | `infra.razorpay`, `infra.fake` adapters picked by `app.payment.provider` |
| `storage`   | `FileStorage` (store image, delete)               | `infra.local` disk adapter |
| `consent`   | `ConsentLedger` (record registration consent, check current acceptance, current policy version) | append-only `consents` log, policy versions from `app.legal.*`, `/legal` + `/consent` endpoints |
| `order`     | —                                                 | checkout + order lifecycle; uses product, coupon, payment, user and consent APIs. Blocks deletion of users with undelivered orders and anonymises addresses on `UserDeletedEvent` |
| `dashboard` | —                                                 | read-side Mongo aggregations (`DashboardReadPort`) |

Layer rules inside a module: `web → application → domain ← infra`. Domain types never import Spring Data
or Mongo classes. Each `infra` adapter implements a `domain` port. Moving to Postgres, S3 or a separate
service therefore means writing a new adapter, not rewriting the module.

### Environments
| Env   | Where | Config source | Payment | Notes |
|-------|-------|---------------|---------|-------|
| local | `docker compose up` or `mvn spring-boot:run` + `npm run dev` | `.env` (see `.env.example`) | `fake` | Mongo container, optional observability profile |
| test  | k8s namespace `test-env` (`k8s/overlays/test`) | ConfigMap + Secret `backend-secrets` | Razorpay test keys | 1 replica, deployed from every non-main branch push |
| prod  | k8s namespace `prod-env` (`k8s/overlays/prod`) | ConfigMap + Secret `backend-secrets` | Razorpay live keys | frontend 2–6 (HPA); backend 1 until ADR-008 is resolved; PDBs; deployed from `main` |

### DevOps pipeline (`.github/workflows/main.yml`)
`backend: mvn verify (Testcontainers)` ∥ `frontend: lint + build` → `docker build & push (sha tag)` →
`kustomize set image + kubectl apply -k` to test (branches) or prod (main), then `kubectl rollout status`.
A `manifests` job renders every overlay and Helm chart and validates them (kubeconform with CRD schemas,
promtool for alert rules) before images are built.
The rollout gate is the readiness probe. Rollback is `kubectl rollout undo` (see `test-rollout.md`).

### Observability plan
- **Metrics:** Micrometer → `/actuator/prometheus`. HTTP server metrics, JVM, Mongo driver pool, Caffeine
  cache, and business counters (`sixpack.orders.placed`, `sixpack.checkout.failed{reason}`,
  `sixpack.auth.login{result}`).
- **Traces:** Micrometer Tracing + OpenTelemetry bridge, OTLP HTTP export to `OTEL_EXPORTER_OTLP_ENDPOINT`.
  Sampling is 100% in local and test and 10% in prod (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`).
- **Logs:** Spring Boot structured logging (ECS JSON) to stdout, with `traceId`/`spanId` in MDC. In k8s an
  OTel collector DaemonSet (`otel-logs`) ships them to Loki. Grafana links trace ids in logs to Tempo.
- **Health:** liveness and readiness groups (readiness includes Mongo), used by k8s probes.
- **Local stack:** `docker compose --profile observability up` adds Prometheus, Grafana (Prometheus
  datasource provisioned) and Jaeger.
- **Cluster stack (ADR-012):** `infra/observability/k8s/install.sh` installs kube-prometheus-stack, Loki,
  Tempo and two OTel collectors into `observability`. The backend `ServiceMonitor` comes from
  `k8s/components/monitoring`.
- **Alerts** (`infra/observability/k8s/alerts.yaml`, through Alertmanager to Slack): 5xx rate > 2% for 5 min,
  p95 latency > 500 ms, readiness flapping, `checkout.failed{reason="paid_but_unfulfilled"}` > 0, no ready
  backend pods, scrape down, and memory near its limit. Severity is critical in prod-env and warning in test-env.

### Frontend UI (`ecommerce-frontend/src/`)
- **Design tokens** live in `styles/_tokens.scss` as CSS custom properties. There are two surfaces: the
  **store** (warm and editorial: coral accent, ink CTAs, Bricolage Grotesque headings) and the **admin**
  (neutral slate with an indigo accent, denser). `SurfaceScope` in `App.tsx` sets `<html data-surface>` from the
  route, so shared components pick up the right look. Components read tokens and do not hard-code colours.
  Chart.js can't read CSS variables, so `components/admin/chartTheme.ts` mirrors the admin palette.
- **Mobile store:** `components/bottomNav.tsx` is a tab bar shown below 640 px on all routes except `/admin` and
  `/shipping` (`utils/routes.ts#showsBottomNav`). The account menu (`components/accountMenu.tsx`) is shared
  between the header dropdown and the bottom sheet, which is a native `<dialog>` for focus trapping and Escape.
- **Admin conventions:** `DataTable` puts search, inline filters and the primary action (e.g. "Add product") in
  one toolbar. Column `width` values are minimums (auto layout shares spare width; cells truncate with
  ellipsis), pagination and page size sit at the bottom, and the table body scrolls instead of the page.
  `DetailLayout` is the shell for product, user, order and new-product pages and puts Save / Update / Delete
  top-right; its cards fit their content.
- **Help:** two separate surfaces. The store has a static, searchable FAQ at `/help` (`pages/help.tsx`, content in
  `help/articles.ts`, linked from the footer and account menu) with a support email taken from
  `BUSINESS.email`. The admin panel has its own knowledge base at `/admin/help[/:slug]`
  (`pages/admin/helpcenter.tsx`, content in `help/adminGuides.ts`). Every admin page shows a Help button in the
  header that opens the guide for that page (`guideForPath`); a new admin page needs a guide and a case there.
  FAQ answers restate `legal/policies.ts`, so update both when a policy changes.

## Key decisions (append-only log, newest first)
- 2026-10-19: ADR-021 **Multi-tenancy completed: every collection on `TenantMongo`, accounts per tenant, per-tenant
  settings, a separate platform administrator.** All tenant-owned documents extend `TenantDocument`; the guard test
  forbids `MongoTemplate` outside the wrapper and the tenant module (its allowlist is now empty) and requires every stored
  document to be tenant-owned or declared global (`tenants`, `platform_admins`). Unique indexes are per tenant (partial
  filters for optional fields); `LegacyIndexCleanup` drops the old global ones at startup because Spring Data only adds
  indexes, and `TenantBackfill` gives existing documents to the default tenant. Users are per tenant (the same email is a
  separate account in each store), so reset tokens, consents, the audit log and the dashboard follow. Caches are keyed by
  tenant in one `CacheManager` decorator that fails closed; uploads go to `uploads/<tenantId>/`; rate limits include the
  tenant. Per-tenant settings (legal versions, payment account, email link base, storefront details) sit on the `Tenant`
  and fall back to `app.*` properties, so the default tenant keeps running on today's environment; payment secrets are
  never stored in Mongo (`TENANT_SECRET_<REF>` in the environment) and only the default tenant may use the platform's
  gateway. Platform administrators are a separate global collection with their own login and a token that has no tenant
  and is refused by every store (and the reverse), so no platform credential can read store data. Exempt paths run with an
  explicitly empty tenant. Rejected: one global user table with memberships (the questions on shared customers were left
  open and separate accounts are the safer default), per-tenant signing keys (more key management, no stated need), and
  a database per tenant (`TenantMongo` stays the single seam where one could be chosen). Known limits: platform
  administrator actions are logged but not yet written to `audit_log` (it is tenant-scoped); there is no cross-tenant
  dashboard or audit view; custom-domain verification and self-serve signup are not built. Still active.
- 2026-10-18: ADR-020 **Tenancy foundation: host-resolved tenant, a fail-closed `TenantMongo` wrapper, and a
  guard that can only shrink.** A filter ahead of Spring Security maps the request host (after the
  forwarded-header filter) to a tenant and binds it to the thread; unknown host is 404 and a suspended tenant is
  403. Tenant-owned data is reached through `TenantMongo` (in the open `shared` module, so no module depends on
  `tenant`), which adds the tenant to every query, stamps it on every save, refuses to overwrite another tenant's
  document and throws when no tenant is in context. `TenantIsolationArchitectureTest` forbids `MongoTemplate`
  outside the wrapper and the tenant module, with an allowlist of the repositories not yet migrated that must
  shrink (it also fails on stale entries). While exactly one tenant exists every host resolves to it, so existing
  deployments need no configuration; a second tenant switches to exact-host matching. Access tokens carry a
  `tid` claim and are valid only for that tenant; tokens from before the change are honoured for the default
  tenant only, until they expire (24 h). `tenants` is platform-global data and stays on `MongoTemplate`.
  Limitation: users are still global, so no second real tenant may be created before the identity migration.
  Rejected: a Mongo-level interceptor that rewrites every query (invisible, and it would also touch global
  data), a database per tenant now (no dedicated-storage requirement, but `TenantMongo` is the single seam where
  one could be chosen), and per-tenant signing keys (more key management with no stated need). Still active.
- 2026-10-18: ADR-019 **Variants embedded in the product document as generic option axes plus a variants array;
  product `price`/`stock` are derived and stored.** Rejected: fixed size/colour fields (dead end for other axes),
  one product per variant (duplicated content, noisy search), a separate variants collection (cross-document
  consistency for a single-store app). Stock changes are single-document atomic updates that move the variant and
  the product total together, so every existing reader of `stock` keeps working. A variant-structure save
  re-reads the product and writes only variants/price/stock under a revision check (`rev`, bumped by every stock
  change), retrying on conflict, so it cannot overwrite a concurrent sale; a plain `save` leaves those fields
  alone for variant products. Variant ids are server-generated and stable; they are stored as `variantId`
  because a field named `id` would map to `_id`. Variant SKUs are always set (generated when blank) so the sparse
  unique index never sees nulls. Gated by `app.products.variants-enabled` (default off) until checkout, orders and
  the storefront support variants. Checkout resolves each cart line through `ProductCatalog.resolveLine`, and stock
  is reserved and released per `StockLine(productId, variantId, qty)`; a sale of a variant needs the variant to be
  sellable and in stock, and a sale without a variant never matches a product that has variants. `OrderItem` and the
  payment intent snapshot the variant id, label and options. Still active.
- 2026-10-18: ADR-019 **Audit trail through an explicit `AuditTrail` port; redaction by allowlist; the admin
  agreement enforced on the server.** A new `audit` module owns the append-only `audit_log` collection (TTL
  retention, fixed at 365 days in `AuditDocument`; changing it needs a code change plus
  `db.audit_log.dropIndex("at")`, because MongoDB refuses to change an index's options in place). Services call
  `AuditTrail.record(AuditEvent)` after a successful write; the
  actor, IP hash and trace id are read from the request by `AuditService`, so callers cannot forge or forget
  them. Each entity has a `FieldPolicy`: only listed fields are logged, and personal fields are recorded as
  "changed" without values, so the log holds no personal values (only pseudonymous salted hashes of the IP and of an attempted login email) and erasure needs no log rewrite. The product-variant
  write paths (`replaceVariants`, `adjustVariantStock`) are audited as `PRODUCT_UPDATED` with `variantPrices` and
  `variantStocks` maps keyed by SKU. A failed audit
  write is logged and counted (`sixpack.audit.write{result=failure}`) and never fails the action. The admin
  acceptable-use agreement is a new consent purpose (`admin_aup`, required for admins only) enforced by an
  interceptor that returns 403 on admin requests until it is accepted; the role comes from the JWT because
  `consent` cannot depend on `user`. Rejected: domain events (they would all need widening with actor and
  diff, and could lose the actor), persistence callbacks (no actor, and they miss the targeted `$set` updates
  of ADR-016), and a prompt-only agreement (bypassable through the API). Reads of personal data and a hash-chain
  for tamper evidence are out of scope; revisit if DPDP accountability or an audit requires them. Known gaps:
  `AdminBootstrap`'s admin creation at startup is not audited. The trail is keyed to the request's JWT, so
  system-driven writes appear as SYSTEM. `CONSENT_IP_SALT` must be set in production, because the attempted-login
  email hash (`subjectHash`) is only as strong as that salt (it defaults to empty). Still active.
- 2026-10-18: ADR-018 **Coupon rules in an additive nested `CouponRules`; one pure `Coupon.evaluate`; redemption
  counted at order creation.** Old coupons (a code and an amount) load as always-active flat coupons, so no
  migration. The same method decides eligibility for the cart preview and for checkout, and returns the reason a
  coupon was refused. A use is counted when the order is created, so an order whose payment was already taken is
  never refused: a limited coupon can be over-used by the number of checkouts open at the same moment, and new
  checkouts are refused once the limit is reached. Counters live in the coupon module (`usedCount` plus
  `coupon_redemptions`), so `coupon` never depends on `order`. Deleting an order does not return a use, and edits
  cannot clear a field (pause with `active`, or create a new coupon). Rejected: reserving a use at checkout with an
  expiry (more moving parts than a handful of coupons need) and counting per-customer use from the orders
  collection (would make `coupon` depend on `order`). Product/category scoping and first-order-only coupons stay in
  Tier 2. Still active.
- 2026-10-17: ADR-017 **Order and payment fields in an additive nested `OrderDetails`; tax per product rate;
  new personal data erased with the account.** Same pattern as ADR-015/016: old orders load with defaults, so no
  migration. Choices: `PaymentInfo.method` and `failureReason` are deferred, because nothing can fill them without
  a gateway "fetch payment" call or webhooks (the refund/settlement work); items store the tax **rate**, not an
  amount, because per-line amounts would disagree with the order total, which rounds to whole rupees per rate
  group (a test proves default-18% carts price exactly as before); no retry on a duplicate order number (about
  one in 887 million per day, and the existing rollback releases stock and re-opens the intent); the buyer's
  contact is snapshotted at confirm and, with the billing address, recipient name/phone and notes, removed by
  `OrderAccountPolicy` on `UserDeletedEvent` while amounts, order number and history stay for tax. Rejected: a
  per-line tax amount and a separate invoice collection (premature until invoices exist). Still active.
- 2026-10-17: ADR-016 **User profile fields in an additive nested `UserDetails`; addresses embedded in the user
  document; targeted `$set` updates.** Same pattern as ADR-015: old documents load with defaults, so there is no
  migration. Addresses are embedded (max 10, always read with the user) and their rules (limit, one default,
  promotion on delete) live in the pure `AddressBook` class. Profile, address and login writes use targeted
  `$set` updates so they can't overwrite a concurrent role or password change. Marketing preferences stay in the
  consent ledger (ADR-014), not on `User`. Suspension is enforced at login and `/me` only: an already-issued JWT
  keeps working until it expires (24 h, ADR-006), because checking per request costs a DB read per call.
  `emailVerified` is stored but nothing sets it yet. Rejected: a separate `addresses` collection and a global
  suspended-user request filter. Revisit the filter if suspension needs to bite immediately. Still active.
- 2026-10-17: ADR-015 **Catalogue fields as an additive nested `ProductDetails`; `Category` inside the `product`
  module; no migration.** The new product fields live in a `ProductDetails` record on `Product`, so the old
  constructor and every call site keep compiling, and stored documents without the fields load as
  `ProductDetails.EMPTY` (status ACTIVE). `slug` and `sku` get sparse unique indexes, so legacy documents don't
  collide, and blank SKUs are normalised to none. Public queries use `status nin [DRAFT, ARCHIVED]`, which keeps
  documents with no status visible; `ProductCatalog.findSnapshots` drops non-public products so checkout can't
  sell them. `Category` is a new collection in the same module (no new module, since nothing else needs it yet).
  Rejected: a separate `catalogue` module (nothing else depends on categories) and a one-off backfill script
  (optional fields don't need one). Multi-tenancy stays a parked future plan (spec appendix). Still active.
- 2026-10-16: ADR-014 **Consent and erasure in-house, no cookie banner yet (Option A in
  `compliance-consent.md`).** A new `consent` module keeps an append-only `consents` log as proof (DPDP),
  versions the Terms/Privacy by effective date (`LEGAL_*_VERSION`), and blocks checkout until the current
  Terms are accepted. Account erasure lives in `user` and other modules join through `AccountDeletionGuard` /
  `UserDeletedEvent`, so `user` never depends on `order`. Data export is assembled in the SPA from existing
  endpoints rather than a new cross-module endpoint. No cookie banner: the site stores only strictly necessary
  data, and fonts are now self-hosted. Rejected: a third-party CMP (cost and an extra script for nothing to
  gate) and a banner with nothing optional in it. Revisit when the first analytics or marketing tag is added,
  or if we sell to the EU/UK. Still active.
- 2026-10-15: ADR-013 **One token-driven design system with two surfaces (store and admin).** All styling is
  driven by CSS custom properties in `_tokens.scss`, scoped by `data-surface`. This replaced the old
  per-page SCSS (`_dashboard`, `_products`, `_chart`, `_mediaquery`, …) and the compile-time SCSS variables
  in `_mixin.scss`, which couldn't switch per surface at runtime. Fonts are Inter
  for UI and Bricolage Grotesque for store headlines. Text colours are checked for WCAG AA contrast, and
  `prefers-reduced-motion` is respected. The mobile store uses a bottom tab bar instead of header icons.
  Rejected: a component library such as MUI or Chakra (bundle weight, and harder to give the store its own
  look) and Tailwind (a large rewrite of existing SCSS). Revisit if a dark mode is needed: add a third token
  set and leave components unchanged. Still active.
- 2026-10-15: ADR-012 **Self-hosted observability with the standard Helm charts, plus Sealed Secrets.** The
  stack is kube-prometheus-stack (Prometheus, Alertmanager, Grafana), Loki and Tempo on filesystem PVCs, and
  OpenTelemetry collectors for traces (a gateway) and logs (a DaemonSet). Chart versions are pinned in
  `install.sh`. It runs once per cluster and is shared by test-env and prod-env, which are told apart by the
  `namespace` label. App secrets are committed as SealedSecrets instead of being created by hand. Ingress
  NetworkPolicies are deny-by-default; egress is open. Rejected: plain manifests (no alerting, not
  production-grade) and a hosted vendor (cost, plus another account). Revisit by moving Loki and Tempo to
  object storage when volume grows. Still active.
- 2026-10-14: ADR-011 **The SPA's nginx forwards `Host`/`X-Forwarded-Host` with the port, plus `X-Forwarded-Proto`
  from the ingress.** Spring (`forward-headers-strategy: framework`) then recognises same-origin requests.
  Without this, browser POSTs were rejected as cross-origin (403). `X-Forwarded-For` is replaced with one
  client IP (taken from the ingress's `X-Real-IP`) so the login rate limiter can't be bypassed. Still active.
- 2026-10-14: ADR-010 **Frontend calls the API same-origin via an nginx reverse proxy** (`/api`, `/uploads`).
  This removes the build-time `VITE_SERVER` coupling, so one image works in every environment, and CORS
  isn't needed in k8s. `VITE_SERVER` stays as an optional override for `npm run dev`. Still active.
- 2026-10-14: ADR-009 **Payment intents.** `POST /order/checkout` prices the cart on the server and stores a
  `payment_intents` record (user, items, amounts, Razorpay order id). `POST /order/new` accepts only the
  Razorpay ids and signature, verifies them, and builds the order from the stored intent. This stops price
  tampering and replay. Still active.
- 2026-10-14: ADR-008 **Uploads stay on local disk behind a `FileStorage` port.** In k8s this needs a
  ReadWriteMany PVC, or a single replica. An S3-compatible adapter is the next step before scaling beyond
  one node. Still active.
- 2026-10-14: ADR-007 **Caffeine in-process cache with short TTLs (60 s catalogue, 120 s dashboard)** in
  place of NodeCache, with explicit evictions on writes. With several replicas, a pod can serve stale data
  for up to one TTL. Moving to Redis is a config change (`spring.cache.type=redis`) once Redis exists.
  Still active.
- 2026-10-14: ADR-006 **JWT via Spring Security OAuth2 resource server (HS256, Nimbus).** Tokens carry
  `sub=userId` and `role`, and expire after 24 h. `JWT_SECRET` must be ≥ 32 bytes or the app won't start.
  This replaces the 30-day tokens that fell back to the secret `"secret"`. Still active.
- 2026-10-14: ADR-005 **Raw Razorpay REST via `RestClient` instead of the Razorpay Java SDK.** Only two
  calls are needed (create order, plus HMAC verify done locally), so the SDK dependency isn't worth it.
  Still active.
- 2026-10-14: ADR-004 **Keep the `/api/v1` paths and JSON field names, including `_id`,** so the frontend
  changes stay small. Fixes: `totalPage` (was `totalpage`), consistent chart keys, and responses that
  never include the password. Still active.
- 2026-10-14: ADR-003 **Spring Boot 3.5.16 + Spring Modulith 1.4.13** instead of Boot 4.x. 3.5 is the
  mature, well-documented line. Revisit Boot 4 (Jackson 3, modular starters) once this migration is stable.
  Still active.
- 2026-10-14: ADR-002 **Modular monolith, hexagonal per module** (see the table above). It fits the app's
  size, and the module boundaries are where it would split into services later. Still active.
- 2026-10-14: ADR-001 **Keep MongoDB** (Spring Data MongoDB), with the same database and collection
  names (`users`, `products`, `orders`, `coupons`). There's no data migration, and bcrypt `$2b$` hashes
  verify with Spring's `BCryptPasswordEncoder`. Postgres stays possible by swapping adapters. Still active.
