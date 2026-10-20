# Multi-tenancy — draft design and open questions

Status: **implemented 2026-10-19 on the assumptions in §8.2 (plans 5 and 6; ADR-020 and ADR-021). Open questions Q1, Q4, Q5, Q7, Q14 were answered by default, not by the owner; see §9.** Date: 2026-10-18. Branch: `feat/multi-tenancy` (from `main` at `bcdc23f`).
Supersedes the unreviewed appendix in `2026-10-17-richer-entities-design.md`, which pre-dates the four entity plans.

## 1. What changes since the first draft

You said every entity needs a *different isolation level*. That is a better frame than "add `tenantId` everywhere", so this draft
defines a small set of isolation levels, then assigns one to every collection and every non-data concern (files, caches, secrets).
Where the right level depends on a business answer I have not got yet, the entity is marked **?** and the question is in §6.

## 2. Isolation levels

| Level | Meaning | How it is enforced |
|---|---|---|
| **G — Global** | One copy for the whole platform. No tenant key. | Only platform admins can write it. |
| **S — Shared identity, tenant-scoped use** | The record is global, but each tenant sees only the part linked to it (a membership). | Tenant filter on the link, not on the record. |
| **T — Tenant-scoped** | Every row has `tenantId`; every read and write is filtered by the current tenant. | One tenant-aware repository base class; raw `MongoTemplate` use outside it is forbidden by an architecture test. |
| **T+ — Tenant-scoped, tenant-configured** | Like T, and the tenant also owns secrets or settings for it (payment keys, legal text, branding). | T, plus the value lives on the `Tenant` record and secrets live in the sealed-secret store, never in Mongo. |
| **D — Dedicated storage** | The tenant's data sits in its own database. | `TenantContext` selects the Mongo database. Reserved for tenants that need it (see Q9). |

Levels G, S, T and T+ all work on one shared database. D is an escape hatch, not the starting point.

## 3. Entity by entity (current code → proposed level)

| Collection / concern (today) | Proposed level | Key changes | Depends on |
|---|---|---|---|
| `tenants` (new) | **G** | `slug`, `domains[]`, `status`, theme, locale/currency, legal text and versions, payment config reference, feature flags | — |
| `users` | **T** (recommended) or **S** | T: email unique per `(tenantId, email)`, same person = separate accounts. S: one global identity plus `memberships[{tenantId, role}]` | **Q1** |
| `password_reset_tokens` | follows `users` | Token is bound to a tenant; reset link uses the tenant's domain | Q1 |
| `consents` | **T+** | Consent is to *that tenant's* Terms and Privacy; versions come from the tenant, not `LEGAL_*_VERSION` | Q4 |
| `products`, `categories` | **T** | `(tenantId, slug)`, `(tenantId, sku)`, `(tenantId, categoryId)` | Q2 (shared catalogue?) |
| `coupons`, `coupon_redemptions` | **T** | `(tenantId, coupon)`; redemption key `(tenantId, couponId, userId)` | — |
| `orders` | **T** | `(tenantId, orderNumber)` unique; the number can also carry a tenant prefix | Q9 for D |
| `payment_intents` | **T** | Unique `(tenantId, providerOrderId)`; TTL stays | — |
| Payments gateway (Razorpay keys) | **T+** | Gateway resolved per tenant; secrets in sealed secrets, referenced by name | Q5 |
| Legal / policy text and business details (frontend `policies.ts`, `BUSINESS`) | **T+** | Served from `GET /tenant/config` instead of compiled in | Q4 |
| Uploaded files (`uploads/`) | **T** | Stored at `uploads/{tenantId}/…`; URL stays `uploads/…` so existing data moves with a one-off rename | Q10 |
| Caches (`products.*`, `dashboard`) | **T** | Tenant in every cache key | — |
| Dashboard aggregations | **T** | `MongoDashboardReadAdapter` uses raw `MongoTemplate`; it must filter by tenant, and platform admins get a cross-tenant view | Q8 |
| Rate limiting (`AuthRateLimitFilter`) | **T** | Key = tenant + IP + path | — |
| JWT | **T** | Add a `tid` claim; reject on mismatch with the resolved tenant | Q6 |
| Platform admin | **G** | New `PLATFORM_ADMIN` role with no tenant; manages tenants | Q3 |
| Bootstrap admin (`AdminBootstrap`, env vars) | **G → T** | One platform admin from env; tenant admins created per tenant | Q3 |
| Mail sender / reset-link base URL | **T+** | From-address and link domain per tenant | Q11 |
| Logs, metrics, traces | **T** | `tenant` label/attribute on every signal | — |

Everything marked **T** gets the same treatment: add `tenantId` (required, indexed), turn each unique index into a compound index that
starts with `tenantId`, and route all access through the tenant-aware repository base.

## 4. How a request becomes tenant-scoped

1. **Resolution.** A filter ahead of Spring Security reads `X-Forwarded-Host` (already forwarded by the nginx proxy, ADR-011), looks the host
   up in a cached `domain → tenantId` map and sets a request-scoped `TenantContext`. Unknown host → 404; suspended tenant → 403.
   `/platform/**` skips resolution.
2. **Authentication.** The JWT carries `tid`; a token for tenant A is rejected on tenant B's domain (401).
3. **Data access.** Repositories extend a tenant-aware base that adds `tenantId` to every query and stamps it on every insert.
   A Modulith/ArchUnit test fails the build if anything outside the base touches `MongoTemplate`.
4. **Background and event work** (cache eviction, `UserDeletedEvent`, `OrderChangedEvent`) carries `tenantId` explicitly; there is no ambient
   context off the request thread.
5. **Frontend.** One SPA build. At boot it calls `GET /tenant/config` and applies theme tokens (ADR-013), name, logo, legal links and currency.

## 5. Proposed delivery order

Each step ships and is tested on its own; the default tenant keeps today's behaviour intact until a second tenant exists.

1. **Foundation:** `tenant` module, `TenantContext`, host resolution, `GET /tenant/config`, `PLATFORM_ADMIN`, `default` tenant created from today's config.
2. **Tenant-aware repositories + guard:** base class, the architecture test, and a cross-tenant leak test harness (two tenants, same data shapes).
3. **Catalogue group:** `products`, `categories`, caches, uploads path. Compound indexes and backfill.
4. **Commerce group:** `coupons`, `coupon_redemptions`, `orders`, `payment_intents`, dashboard, order numbers.
5. **Identity group:** `users`, `password_reset_tokens`, `consents`, JWT `tid`, rate limits, account erasure per tenant. (Shape depends on Q1.)
6. **Per-tenant configuration:** payment gateway per tenant, legal text and versions, mail sender, branding served to the SPA.
7. **Platform admin API and UI**, tenant onboarding script, and observability labels.
8. **Migration and rollout:** idempotent backfill of existing data into the `default` tenant, index swap, rehearsal on a copy, rollback plan.

Risks to design against: a missed filter leaking data (mitigated by the base class, the architecture test and the leak suite); dropping old unique
indexes before the backfill (the migration orders its steps); per-tenant secrets (sealed secrets only); the single-replica uploads limit (ADR-008).

## 6. Open questions

Answers decide levels marked **?** above. My recommendation is in bold.

**Identity and access**
1. **Are customers shared across tenants?** (a) **No: each tenant has its own customers; the same email can register in two tenants (level T).** (b) Yes: one login works in every tenant, with a per-tenant membership (level S). (c) Mixed per tenant.
2. **Is any catalogue shared?** (a) **No, each tenant owns its products.** (b) A shared master catalogue with per-tenant price/visibility. (c) Platform-curated "starter" products copied into new tenants.
3. **Who administers what?** Do you want a platform super-admin who can see every tenant? Can a tenant admin invite other admins? **Recommend a `PLATFORM_ADMIN` plus per-tenant `ADMIN`s, no cross-tenant tenant-admin.**
6. **JWT signing:** **one platform secret with a `tid` claim**, or a signing secret per tenant (stronger isolation, more key management)?

**Per-tenant behaviour**
4. **Legal and policies:** is the Terms, Privacy and refund text per tenant (each tenant is its own legal entity with its own grievance officer), or one platform-wide set? **Recommend per tenant.** Consent records then point at the tenant's policy version.
5. **Payments:** does each tenant get **its own Razorpay account and keys** (money goes straight to the merchant), or does the platform collect on their behalf (needs Razorpay Route / marketplace features and settlement logic)?
10. **Files:** is local disk (ADR-008) acceptable for now, with a per-tenant folder, or should tenancy force object storage first?
11. **Email:** a sender address and reset-link domain per tenant — needed now, or later when a real mail provider arrives?

**Scale and operations**
7. **How many tenants, and how are they created?** (Earlier answer: a handful, created by you.) Still true? That keeps onboarding as a script and rules out self-serve signup.
8. **Platform reporting:** should the platform admin see cross-tenant dashboards (totals across all stores), or strictly one tenant at a time?
9. **Dedicated storage (level D):** do any tenants need their own database (compliance, data residency, a big customer)? **Recommend not now; keep the code able to add it later.**
12. **Domains:** one tenant can own several domains. Do you need subdomains under a platform domain (`acme.yourplatform.com`), custom domains (`shop.acme.com`) with verification, or both?
13. **Suspension and offboarding:** when a tenant is suspended or leaves, do you export then delete their data, or just disable the store? How long do you keep it?

**Migration**
14. **Existing data:** is today's data (database `Ecommerce_2024`) the first tenant ("default"), or should it be split or archived? Is there any real production data to protect, or only dev data?
15. **Order numbers and coupon codes:** unique per tenant (two tenants can both have `SAVE10`) — confirm that is fine, or should some identifiers stay platform-unique?

## 7. What I need from you

Answers (even one-line) to Q1, Q4, Q5, Q7 and Q14 unblock the plan; the rest have defaults. Once those are settled the next step is a
detailed, task-by-task implementation plan in the same format as plans 1–4, starting with the foundation step.

## 8. Review after other sessions' changes (2026-10-18)

Three other pieces of work landed on branch `feat/multi-tenancy` while this draft was open: **product variants**, the **admin
acceptable-use agreement**, and the start of the **audit log** (`docs/superpowers/specs/2026-10-18-admin-agreement-and-audit-design.md`).
None of them is on `main` yet. This section updates the draft for them.

### 8.1 What changed in the picture

| New thing | Isolation level | What it means for tenancy |
|---|---|---|
| **Product variants** (embedded `options` and `variants` inside the product document, no new collection) | **T**, same as `products` | The per-variant atomic stock updates are new raw Mongo operations. They must go through the tenant-aware wrapper like everything else; variant SKUs are unique per `(tenantId, sku)`. |
| **`audit_log`** (new, append-only, TTL) | **T** for tenant admins, **G-read** for platform admins | `tenantId` is already reserved on the entry. `AuditService` must stamp it from the tenant context. A tenant admin sees only their tenant's log; a platform admin can search across tenants. The TTL retention stays one platform setting. |
| **`ADMIN_AUP`** consent purpose and `LEGAL_ADMIN_AUP_VERSION` | **T+** | Moves onto the `Tenant` record with the Terms and Privacy versions (already planned for Q4). Whether a `PLATFORM_ADMIN` must also accept it is new question Q16. |
| Admin agreement interceptor (`HandlerInterceptor` on `/api/v1/**`) | follows the request | It runs after authentication, so it sees the tenant context set by the earlier tenant filter. |
| `IntegrationTest` base class | n/a | Three sessions now edit it. Treat it as a merge hotspot: tenancy adds helpers in a **subclass**, not in the base. |

**Ordering consequence.** Variants rewrite `MongoProductRepository` and `ProductService`, and the audit work adds a new repository and touches
most services. Converting those repositories to the tenant-aware wrapper before those branches reach `main` would cause large merge
conflicts. The foundation (tenant module, request resolution, JWT `tid`, the wrapper and the guard test) does not touch them, so it can start
now. The per-entity migrations (§5 steps 3 to 5) wait until variants and audit are on `main`.

### 8.2 Assumptions I am applying (answers to §6 not yet given)

Change any of these and the affected step changes; the foundation is the same under all of them.

| Q | Assumption | Why this is safe to start on |
|---|---|---|
| 1 | Customers are **not shared**: each tenant has its own users (level T). | The foundation does not touch users. Identity migration (step 5) is where this matters. |
| 2 | No shared catalogue. | Only affects step 3. |
| 3 | `PLATFORM_ADMIN` plus per-tenant `ADMIN`s. | Platform API is step 7. |
| 4 | Legal text and versions are per tenant. | Needs `legal` fields on `Tenant`; added in step 6. |
| 5 | Each tenant has its own Razorpay keys. | Step 6. |
| 6 | One signing secret with a `tid` claim. | **Built in the foundation.** |
| 7 | A handful of tenants, created by you. | No self-serve onboarding is built. |
| 9 | No dedicated databases (level D). | The wrapper is the single seam where a per-tenant database could be selected later. |
| 14 | Today's data becomes the `default` tenant. | Built as an idempotent backfill. |

New questions from this review:
16. Must a `PLATFORM_ADMIN` accept the admin acceptable-use agreement? **Recommend yes** (they can see more than any tenant admin).
17. Should a tenant admin see only their own tenant's audit log, with cross-tenant search for platform admins only? **Recommend yes.**
18. Variant SKUs unique per tenant (not per platform)? **Recommend yes**, same as product SKUs.

### 8.3 Decisions refined by the review

1. **A wrapper, not a base class.** Repositories are plain classes around `MongoTemplate`, so the cleanest enforcement point is a
   `TenantMongo` bean (in `com.sixpack.shared.tenant`, an open module, so no module cycle) that repositories use instead of `MongoTemplate`.
   It adds the tenant to every query, stamps it on every save, and refuses to overwrite another tenant's document.
2. **A guard that can only shrink.** An ArchUnit test fails the build if any class outside the wrapper and the tenant module uses
   `MongoTemplate`. It starts with an explicit allowlist of today's repositories and also fails if an allowlisted class no longer uses it, so
   each migration step must delete its entries.
3. **Single-tenant fallback.** When only the `default` tenant exists, any host resolves to it, so every current deployment keeps working with no
   configuration. As soon as a second tenant exists, unknown hosts get 404. (Without this, deploying the foundation would break production
   until someone listed its domain.)
4. **Legacy tokens.** Tokens issued before the change have no `tid`. They stay valid for the default tenant only, until they expire (24 hours).
5. **Known foundation-phase limit.** Users are still global until step 5, so a login on a second tenant's domain would succeed with a global
   user. No second real tenant should be created before the identity migration; only tests create one.

## 9. As built (2026-10-19)

All of §5 steps 1 to 8 are built except the items below. The `default` tenant keeps today's behaviour with no new configuration.

| Question | What was built |
|---|---|
| Q1 customers | Not shared: accounts are unique per `(tenant, email)`. |
| Q3 admins | `PLATFORM_ADMIN` in its own collection and login; per-tenant `ADMIN`s; no cross-tenant admin. |
| Q4 legal | Policy versions per tenant (fallback `app.legal.*`); business details per tenant (`storefront`); the policy **text** is shared and rebuilt with the tenant's name and details. |
| Q5 payments | Own Razorpay account per tenant; secret from `TENANT_SECRET_<REF>`; only the default tenant may use the platform gateway. |
| Q6 JWT | One signing secret, `tid` claim. |
| Q8 platform reporting | Not built (per-tenant dashboards only). |
| Q11 mail | Link base per tenant; there is still no real mail provider. |
| Q12 domains | Several exact domains per tenant; no wildcard or custom-domain verification. |
| Q17 audit | Each tenant admin sees only their tenant's log, including what platform administrators did to their store. Platform administrators get a read-only view of every store's log (`TenantMongo.findAcrossTenants`, the only cross-tenant read). Decided 2026-10-20. |

Not built: self-serve signup, per-tenant policy **text**, object storage for uploads,
dedicated databases, platform-level reporting.
