# Marketing integrations (Google, Meta, …) — system design

Status: **Phase 0–1 in progress on `feat/marketing`, 2026-10-30.** Phases 2–5 not built. Builds on `dev` at `f3db546`
(tenancy, store settings, consent gate). Decision: ADR-028 in `docs/foundations/architecture.md`; the phase 0–1 API
is fixed in `docs/foundations/contracts.md` (Marketing), which wins where this draft differs.
Implementation plan: `docs/superpowers/plans/2026-10-30-marketing-integrations.md`.

## 1. Goal

A store owner opens **Admin → Marketing** and, without touching code or a tag manager, can:

1. **Measure** — the storefront sends standard shop events (view item, add to cart, checkout, purchase) to Google
   Analytics 4, Google Ads and the Meta Pixel, only after the shopper consents.
2. **List products** — Google Merchant Center and the Meta catalogue pull the store's products from one feed URL.
3. **Send conversions from the server** — paid orders reach Meta Conversions API and GA4 Measurement Protocol even
   when the browser blocked the pixel, de-duplicated against the browser event.
4. **Connect with one click** (later) — "Connect Google" / "Connect Meta" via OAuth instead of pasting IDs, then see
   spend and return on ad spend next to sales.

Everything is **bound to the tenant**: every ID, token, feed, queued event and log line belongs to exactly one store,
and nothing one store configures can reach another store's data or shoppers.

**Not in scope:** creating or editing ad campaigns from 6Pack, email/SMS/WhatsApp campaigns, a shopper segment
builder, custom webhooks to arbitrary URLs (SSRF risk), per-tenant "bring your own OAuth app".

## 2. What exists that this reuses

| Piece | Where | Used for |
|---|---|---|
| Host-resolved `TenantContext`, fail-closed `TenantMongo`, `TenantDocument` | `shared/tenant` (ADR-020/021) | Isolation of every new collection |
| Per-tenant secrets by reference (`PaymentSettings.secretRef` → `TENANT_SECRET_<REF>`) | `payment/infra/TenantPaymentGateways` | Pattern for per-tenant clients; **not** enough for OAuth tokens (§5.3) |
| Per-store feature switches (`Feature`, `TenantFeatures`) | `shared/tenant` | `marketing` switch the platform admin controls |
| `GET /api/v1/tenant/config` | `tenant/web/TenantConfigController` | Ships public tag IDs to the storefront — no extra call |
| Consent gate `hasConsent(category)` (always `false` today) | `ecommerce-frontend/src/utils/consent.ts`, compliance-consent §5 | Every tag loads behind it; banner is a prerequisite |
| `OrderChangedEvent`, `ProductChangedEvent` | `order`, `product` modules | Trigger server conversions and feed cache eviction |
| `AuditTrail` with allowlist redaction | `audit` (ADR-019) | Connect/disconnect/config changes |
| Daily summary job pattern | `dashboard` (ADR-024) | Ad spend roll-up in phase 5 |
| `TenantExport`, `TenantPurge` | `tenant/application` | Offboarding: export config without secrets, purge and revoke |

## 3. Options considered

| | Option | For | Against |
|---|---|---|---|
| **A** | **In-house `marketing` module with one adapter per provider, phased** (paste IDs → feed → server conversions → OAuth → reporting) | Fits the modular monolith (ADR-002); isolation comes free from `TenantMongo`; no new processor of shopper data; each phase ships alone | We maintain each provider's API changes |
| B | Tag-manager only: each store pastes a GTM container ID, does the rest in GTM | Smallest build (one field) | Not "easy" for a shop owner; no feed, no server conversions, no de-dup |
| C | Third-party CDP (Segment/RudderStack) workspace per store | Many destinations at once | Per-store cost, another data processor for shopper PII (DPDP notice changes), vendor lock-in |
| D | Server-side GTM container per store | Best data quality | One running container per tenant — infra and cost grow with every store |

**Recommendation: A**, with an optional "GTM container ID" field inside the Google card for advanced owners (it costs
one validated string). Reason: it is the only option that gives a shop owner a guided setup *and* keeps every store's
data inside the isolation the platform already enforces.

## 4. Architecture

Interactive diagram, all phases, phase 0–1 parts tagged "now":
[marketing-integrations.html](../../../.archify/architecture-marketing-integrations-20261030-221254/marketing-integrations.html)
(source `candidate.json` in the same folder).

```
 Storefront (tenant domain)                       Admin SPA (tenant domain)
 ┌──────────────────────────────┐                 ┌───────────────────────────────┐
 │ cookie banner → hasConsent() │                 │ Marketing page: cards per     │
 │ tracking.ts: gtag / fbq      │                 │ provider, test event, log     │
 │ captures utm/gclid/fbclid    │                 └──────────────┬────────────────┘
 └──────┬──────────────┬────────┘                                │ /api/v1/admin/marketing/**
        │ /tenant/config│ /order/checkout (+attribution, consent) │
        ▼               ▼                                         ▼
 ┌────────────────────────────── backend (Spring Modulith) ─────────────────────────────┐
 │ TenantResolutionFilter → TenantContext                                                │
 │                                                                                       │
 │  marketing module                                                                     │
 │   web:  MarketingAdminController · FeedController · OAuthCallbackController (phase 4)  │
 │   app:  IntegrationService · CatalogFeedService · ConversionRecorder (listener)        │
 │         ConversionDispatcher (scheduled) · ReportingJob (phase 5)                      │
 │   domain: Integration · ConversionEvent · Provider · port MarketingDestination         │
 │   infra: mongo repos (TenantMongo) · SecretBox (AES-GCM) · google/ · meta/ adapters    │
 │                                                                                       │
 │  order ──OrderChangedEvent──▶ ConversionRecorder ──▶ marketing_outbox (per tenant)     │
 │  product ──ProductChangedEvent──▶ feed cache evict                                     │
 └──────────────┬──────────────────────────────────────────────┬────────────────────────┘
                │ MongoDB (tenantId on every row)               │ HTTPS, fixed provider hosts only
                ▼                                               ▼
   marketing_integrations · marketing_outbox ·        graph.facebook.com · google-analytics.com
   marketing_daily (phase 5)                          googleads.googleapis.com · merchantapi.googleapis.com
```

### 4.1 Module boundaries

New Modulith module `com.sixpack.marketing` (hexagonal like the others). It **reads** from `order` and `product`
through their published events and a read port (`CatalogReads`: paged active products with variants, price, stock,
image URL), and from `tenant` through `TenantSettings`/`TenantFeatures`. Nothing depends on `marketing`, so switching
it off never breaks checkout.

### 4.2 Provider model

`Provider` is a company (one admin card each): `GOOGLE`, `META`; `TIKTOK`, `PINTEREST` later. Each provider has
**capabilities**, enabled independently inside its card:

| Provider | Capability | Needs | Phase |
|---|---|---|---|
| Google | GA4 browser tag | Measurement ID `G-XXXXXXX` | 1 |
| Google | Google Ads conversion tag | Conversion ID `AW-123…` + purchase label | 1 |
| Google | GTM container (advanced) | `GTM-XXXXXX` | 1 |
| Google | Merchant Center feed | feed URL (we serve it) | 2 |
| Google | GA4 Measurement Protocol | API secret (**secret**) | 3 |
| Google | OAuth: Ads enhanced conversions, Merchant API push, reporting | OAuth refresh token (**secret**), developer token (platform) | 4–5 |
| Meta | Pixel | Pixel ID (digits) | 1 |
| Meta | Catalogue feed | feed URL (same feed as Google) | 2 |
| Meta | Conversions API | access token (**secret**), optional test code | 3 |
| Meta | OAuth: business/pixel/catalogue discovery, reporting | long-lived token (**secret**) | 4–5 |

One adapter port; every provider implements only what it supports:

```java
public interface MarketingDestination {
    Provider provider();
    /** Throws RetryableDeliveryException (5xx, 429, timeout) or PermanentDeliveryException (4xx). */
    void send(Integration integration, ConversionEvent event);
    HealthCheck check(Integration integration);
}
```

### 4.3 Tenant binding — the rules

| Concern | How it is bound to one tenant | Proven by |
|---|---|---|
| Stored config and secrets | `marketing_integrations` extends `TenantDocument`; unique `{tenantId, provider}`; only `TenantMongo` touches it | IT: store A admin `GET`/`PUT` cannot see or change store B's row |
| Encrypted secrets | AES-256-GCM with **`tenantId|provider` as associated data**: a ciphertext copied to another tenant's row fails to decrypt | Unit: decrypt with other tenant AAD throws |
| Browser tags | IDs come only from the current host's `/tenant/config`; tags never hard-coded or shared | IT: store B's config has none of store A's IDs |
| Product feed | URL carries a random 32-byte per-tenant `feedToken`; request must match **both** host-resolved tenant and token; rotatable | IT: store A token on store B's host → 404 |
| Queued conversions | `marketing_outbox` rows carry `tenantId`; dispatcher sets `TenantContext` from the row, never ambient | IT: dispatcher sends store A's event with store A's credentials only |
| OAuth (phase 4) | Single platform callback URL; `state` = signed `{tenantId, userId, provider, nonce, exp}` + PKCE; single use, 10 min; tenant taken from verified state, then redirect to that tenant's own admin domain | IT: tampered, expired, replayed, or cross-tenant state → 400, nothing stored |
| Caches | Feed and config caches keyed by tenant (`TenantCacheManager`) | existing tenant cache tests + one feed test |
| Rate limits and quotas | Per `{tenantId, provider}` token bucket in the dispatcher; `TenantQuotas` caps outbox rows/day | Unit test of the bucket |
| Feature switch | `Feature.MARKETING`; off → admin endpoints 404, config omits `marketing`, dispatcher skips the tenant | IT |
| Offboarding | `TenantExport` includes integrations **without** secrets; `TenantPurge` revokes OAuth tokens at the provider then deletes rows | IT on export shape; purge test with a fake adapter |
| Observability | `tenant` and `provider` attributes on every metric/span (`marketing.events{outcome}`) | — |
| Audit | `INTEGRATION_CONNECTED/UPDATED/DISCONNECTED/TESTED`, `FEED_TOKEN_ROTATED`; secret fields never in the diff | Audit IT per event, asserts secret absent |

## 5. Data

### 5.1 `marketing_integrations` (level T+)

This section and §7 describe the finished design across all phases. Phase 1 builds a subset: stored statuses
`NOT_CONNECTED | ACTIVE` only, providers lower-case on the wire (`google`, `meta`; the stored enum may be upper-case),
no feed URL or delivery log in `GET /admin/marketing`, and only the `INTEGRATION_UPDATED` / `INTEGRATION_DISCONNECTED`
audit events. The Phase 1 contract is in `docs/foundations/contracts.md`.

```json
{
  "_id": "…", "tenantId": "t_123", "provider": "META",
  "status": "ACTIVE",                       // ACTIVE | ERROR | DISABLED
  "browser":  { "pixelId": "123456789012345" },
  "server":   { "enabled": true, "testEventCode": "TEST123",
                "secret": { "keyId": "k1", "iv": "…", "ciphertext": "…" } },
  "oauth":    null,                         // phase 4: { "accountId", "scopes", "refreshToken": {sealed}, "expiresAt" }
  "lastSuccessAt": "2026-10-30T10:00:00Z",
  "lastError": { "at": "…", "code": "AUTH_EXPIRED", "message": "Token expired" },
  "createdAt": "…", "updatedAt": "…", "version": 3
}
```

Every public ID is validated by a strict pattern **before** saving, because it is later written into a `<script>` call:
GA4 `^G-[A-Z0-9]{4,12}$`, Ads `^AW-\d{6,12}$`, label `^[A-Za-z0-9_-]{1,40}$`, GTM `^GTM-[A-Z0-9]{4,10}$`, Meta pixel
`^\d{10,20}$`. Secrets are write-only: the API returns `hasSecret: true`, never the value.

### 5.2 Feed token

One feed serves both Google and Meta, so its token sits on a small per-tenant `marketing_settings` document
`{tenantId, feedToken, feedEnabled}`, unique on `tenantId`. `feedToken` is 32 random bytes, URL-safe base64.

### 5.3 Why secrets are encrypted in Mongo here (deviation from T+)

T+ says tenant secrets live in sealed secrets, referenced by name — fine for Razorpay keys a platform admin sets once.
Marketing secrets are set by **store owners** in the UI and OAuth tokens **rotate** on their own, so a redeploy per change
is not possible. So: envelope-style encryption in Mongo.

- `SecretBox` (infra): AES-256-GCM, random 12-byte IV, AAD = `tenantId|provider|field`.
- Key from `MARKETING_KEYS` (comma list `k1:<base64>,k2:<base64>`, newest first), delivered like other platform secrets
  (pass → secrun locally, Sealed Secret in k8s). Encrypt with the first, decrypt by `keyId`; a re-encrypt job rotates.
- No key configured → the server-side and OAuth capabilities are unavailable (fail closed); browser tags and feed
  still work.

This needs its own ADR, written in phase 3 with the next free number then. ADR-028 is the module decision (option A);
ADR-027 is taken on `feat/analytics`.

### 5.4 `marketing_outbox` (level T)

`{tenantId, provider, eventId, eventName, orderId, payload (hashed PII only), status PENDING|SENDING|SENT|FAILED|SKIPPED,
attempts, nextAttemptAt, leaseUntil, lastError, createdAt}`. Unique `{tenantId, provider, eventId}` (idempotent
inserts). TTL index removes rows 30 days after `createdAt`. Claiming uses `findAndModify` PENDING→SENDING with a
2-minute lease, so two replicas never send the same row.

### 5.5 Order attribution (additive nested field, like ADR-015/016)

`order.attribution = {utmSource, utmMedium, utmCampaign, utmContent, utmTerm, gclid, fbclid, fbp, fbc, landingPath,
consent: {analytics, marketing, version, at}}`. Captured by the storefront into `sessionStorage` on landing (first touch
per session) **only if** consent allows, and sent with `POST /order/checkout`. Old orders load with `null`.

### 5.6 `marketing_daily` (phase 5, level T)

`{tenantId, provider, day, currency, spend, clicks, impressions, conversions, conversionValue}`, unique
`{tenantId, provider, day}`, filled by a nightly pull per connected tenant.

## 6. Flows

**Browser purchase (phase 1).** Storefront loads `/tenant/config` → `marketing.tags` → after `hasConsent("marketing")`
loads `fbq`/Ads tag, after `hasConsent("analytics")` loads GA4. On the order-success page it fires `purchase` with
`event_id = order._id`. Google Consent Mode v2 defaults are `denied` and updated from the banner choice.

**Server purchase (phase 3).** Order becomes paid → `OrderChangedEvent(orderId)` → `ConversionRecorder` loads the
order in its tenant → if `attribution.consent.marketing` is true and the provider has server sending on, writes one
outbox row per provider with `eventId = order._id` (same as the browser, so Meta de-duplicates). Email and phone are
normalised and SHA-256 hashed before they enter the row. `ConversionDispatcher` (every 30 s) claims due rows, sets
`TenantContext`, calls the adapter; on retryable failure backs off `1m, 5m, 30m, 2h, 12h`, then FAILED; on
permanent failure FAILED at once and marks the integration `ERROR` with a message the owner can act on.

**Feed (phase 2).** Google Merchant / Meta fetch `https://<store-domain>/api/v1/feeds/<feedToken>/products.xml` on
their own schedule. One Google-RSS 2.0 file (`g:` namespace) is accepted by both. Variants become items sharing
`g:item_group_id`. Cached 15 min per tenant with `ETag`; evicted on `ProductChangedEvent`.

**OAuth connect (phase 4).** Owner clicks Connect → `GET /admin/marketing/oauth/{provider}/start` returns the provider
authorise URL with signed `state` + PKCE → provider redirects to the platform callback → callback verifies state,
exchanges code, encrypts refresh token, lists accounts/pixels/catalogues for the owner to pick → redirect back to
`https://<tenant-domain>/admin/marketing?connected=meta`.

## 7. API contracts and call budgets

| Screen / action | Calls | Budget |
|---|---|---|
| Admin → Marketing first load | `GET /api/v1/admin/marketing` (all cards: config without secrets, status, feed URL, last 10 deliveries) | 1 ≤ 3 |
| Save a card | `PUT /api/v1/admin/marketing/{provider}` (returns the updated card) | 1 |
| Send test event | `POST /api/v1/admin/marketing/{provider}/test` | 1 |
| Disconnect | `DELETE /api/v1/admin/marketing/{provider}` | 1 |
| Rotate feed URL | `POST /api/v1/admin/marketing/feed/rotate` | 1 |
| Delivery log, older pages | `GET /api/v1/admin/marketing/{provider}/deliveries?page=` | 1 |
| Storefront first load | `marketing.tags` added to existing `GET /api/v1/tenant/config` | +0 |
| Checkout | `attribution` added to existing `POST /api/v1/order/checkout` | +0 |
| Feed (machine) | `GET /api/v1/feeds/{token}/products.xml` (public, rate-limited) | — |
| OAuth (phase 4) | `GET …/oauth/{provider}/start`; `GET /api/v1/oauth/{provider}/callback` on the platform host, added to `TenantResolutionFilter`'s tenant-less list beside `/api/v1/platform` | 1 each |

All `/admin/marketing/**` endpoints: tenant `ADMIN` role, admin agreement required (existing interceptor), 404 when the
feature is off. Request bodies validated with the patterns in §5.1; unknown providers 404.

## 8. Security and privacy

- **Consent first.** No tag, pixel, click ID or server event without the matching category. The cookie banner
  (compliance-consent §5) ships in phase 0, before anything else. Privacy notice gains "advertising partners" and the
  consent version bumps.
- **No user-chosen outbound URLs.** Adapters call fixed provider hosts only; owners never enter a URL (no SSRF).
- **Script injection.** Only pattern-validated IDs reach the page; the tag loader builds script URLs from constants.
- **CSP.** Admin and storefront are one SPA behind one nginx template (`ecommerce-frontend/nginx.conf.template`, both
  `Content-Security-Policy` lines), so the policy adds `https://www.googletagmanager.com`,
  `https://*.google-analytics.com`, `https://*.doubleclick.net`, `https://connect.facebook.net` to `script-src` and
  `connect-src` for the whole app. Admin routes never call the tag loader, so nothing loads there in practice.
- **PII.** Only hashed email/phone leave the server, only for consenting shoppers; outbox payloads expire in 30 days;
  `UserDeletedEvent` deletes that user's pending outbox rows.
- **Secrets** never in responses, logs, audit diffs, exports, frontend env vars or error messages.
- **Tests that scanners miss** (in the plan): cross-tenant IDOR on every endpoint, OAuth state tampering/replay, feed
  token mismatch, rate limit on the feed and test-event endpoints, no secret in any response.

## 9. Operations

- Config: `app.marketing.enabled`, `MARKETING_KEYS`, phase 4 `GOOGLE_OAUTH_CLIENT_ID/SECRET`,
  `GOOGLE_ADS_DEVELOPER_TOKEN`, `META_APP_ID/SECRET`, `app.marketing.oauth.callback-base-url`. All via pass/secrun
  locally and Sealed Secrets in k8s; `.env.example` gets fake values.
- Metrics: `marketing.events{provider,outcome}`, `marketing.outbox.pending`, `marketing.feed.requests`; alert when
  FAILED rate > 5 % for 15 min or pending age > 30 min (same style as the audit-write alert).
- Runbook entry: "a store's conversions stopped" → check card status, delivery log, token expiry, re-connect.
- **Lead time (phase 4):** Google OAuth consent-screen verification, Google Ads API developer token (Basic access) and
  Meta App Review (`ads_read`, `business_management`, `catalog_management`) each take days to weeks and need a public
  privacy policy and company details. Apply when phase 3 starts.

## 10. Open questions (defaults used if not answered)

| # | Question | Default |
|---|---|---|
| Q1 | Which providers first? | Google + Meta; TikTok/Pinterest as later adapters |
| Q2 | Who may connect — store `ADMIN`, platform admin, or both? | Store `ADMIN`; platform admin only switches the feature per store |
| Q3 | Paste-IDs first, or wait for one-click OAuth? | Paste-IDs first (phases 1–3), OAuth in phase 4 |
| Q4 | Will the platform register its own Google/Meta apps (company verification)? | Yes, started at phase 3 |
| Q5 | Is marketing a paid-plan feature? | Feature switch only; billing later |
| Q6 | Are shoppers outside India in scope (GDPR/consent mode rules)? | India (DPDP) only; consent mode v2 still on |
