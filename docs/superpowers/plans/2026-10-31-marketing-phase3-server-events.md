# Marketing Phase 3 — Server-side conversions (Meta Conversions API, GA4 Measurement Protocol) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Part A (backend + docs) and Part B (frontend) run in parallel in separate worktrees; Part B depends only on the Fixed API contract below.**

**Goal:** A store owner pastes a Meta Conversions API access token and a GA4 Measurement Protocol API secret on Admin → Marketing; every paid order of a consenting shopper then reaches Meta and GA4 from the server — once, de-duplicated against the browser event, retried through provider outages, never sent with another store's credentials — and the owner sees each delivery, a test-event button and an actionable error when a provider refuses.

**Architecture:** Owner secrets are sealed with AES-256-GCM (`SecretBox`, ADR-029) and stored on the existing `marketing_integrations` row. Checkout captures a consent-gated attribution snapshot onto the payment intent and then the order (additive nested field). A paid order reaches a tenant-scoped `marketing_outbox` (unique `{tenantId, provider, eventId}`, no PII, 30-day TTL) through a synchronous `OrderChangedEvent` listener **plus** a 5-minute reconciliation sweep (no Mongo transactions exist). A per-provider `ConversionDispatcher` claims rows one at a time with a `findAndModify` lease and fencing token, builds the payload from the order *at send time* (so erasure and consent apply), and calls a `MarketingDestination` adapter with fixed timeouts, bounded backoff with jitter, a per-provider circuit breaker and a one-thread-per-provider bulkhead. FAILED rows are the dead letter: visible in the card's delivery log and the card's `lastError`, alerted on in Prometheus.

**Tech Stack:** Java 21 / Spring Boot 3.5 / Spring Modulith / Spring Data MongoDB (`TenantMongo`), `RestClient` + `SimpleClientHttpRequestFactory`, JCA `AES/GCM/NoPadding`, Micrometer; JUnit 5 + AssertJ + MockMvc + Testcontainers (`mongo:7.0`, Docker must run) + `MockRestServiceServer` + a JDK `com.sun.net.httpserver` provider stub + Mockito (all already on the test classpath). React 18 + TypeScript + RTK Query 2.11, Vitest 3.2 + jsdom (no Testing Library; `src/test/dom.tsx`). **No new dependencies.**

**Spec:** `docs/superpowers/specs/2026-10-30-marketing-integrations-design.md` (§4.2, §4.3, §5.3–§5.5, §6 server purchase, §8, §9). **Phase plan:** `docs/superpowers/plans/2026-10-30-marketing-integrations.md` — this file details Tasks 3.1, 3.2, 3.3. **Format:** the two Phase 1 plans (`2026-10-30-marketing-phase1-backend.md` / `-frontend.md`).

**Provider API versions pinned (checked 2026-10-31 via Context7 against developers.facebook.com and developers.google.com):**
- **Meta Conversions API:** Graph API **v25.0** — `POST https://graph.facebook.com/v25.0/{pixel_id}/events`, JSON body `{data:[event], access_token, test_event_code?}`; response `{events_received, messages, fbtrace_id}`; error `{error:{message, type, code, error_subcode, is_transient, fbtrace_id}}` (190 = invalid token, 100 = invalid parameter). Website events carry `action_source:"website"`, `event_source_url`, `user_data.client_user_agent`. De-duplication: same `event_name` + `event_id` as the Pixel's `eventID`, window 48 h. `event_time` (Unix seconds) at most 7 days old. Hashing: SHA-256 lower-case hex of normalised values — email trimmed + lower-cased, phone digits only, no leading zeros, with country code; `fbp`/`fbc`/`client_user_agent` are **not** hashed. Published vectors: `mary@example.com` → `f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79`, `15559876543` → `1ef970831d7963307784fa8688e8fce101a15685d62aa765fed23f3a2c576a4e` (verified locally with `sha256sum`). Version lives in `app.marketing.meta.api-version` so a bump is a config change.
- **GA4 Measurement Protocol** (unversioned): `POST https://www.google-analytics.com/mp/collect?measurement_id=G-…&api_secret=…`, JSON `{client_id, timestamp_micros, consent:{ad_user_data, ad_personalization}, events:[{name:"purchase", params:{transaction_id, currency, value, tax, shipping, coupon?, items[]}}]}`; returns 2xx with no body even for malformed payloads ("don't retry the same request" on non-2xx). `transaction_id` de-duplicates against the browser purchase. Events may be backdated **72 h** at most. Validation server: `POST https://www.google-analytics.com/debug/mp/collect` → `{validationMessages:[{fieldPath, description, validationCode}]}` (empty = valid). It does **not** check the API secret.

---

## Global Constraints

- **Branches and worktrees.** This plan is committed on `feat/marketing--server-events` (cut from `feat/marketing`). Each part gets its own sub-branch and worktree, merged back into `feat/marketing--server-events` by PR (or local merge, ask first), which then merges into `feat/marketing`:
  ```bash
  cd /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing-events
  git worktree add ../6Pack-marketing-events-be -b feat/marketing--server-events--backend feat/marketing--server-events   # Part A
  git worktree add ../6Pack-marketing-events-fe -b feat/marketing--server-events--frontend feat/marketing--server-events  # Part B
  ```
  Never commit on `dev`/`main`/`feat/marketing`. Commit after each task; ask before push, merge or rebase.
- **Sandbox:** in this environment Bash may only write in another folder; run every write/git/mvn/npm command in a worktree with `dangerouslyDisableSandbox: true`.
- **Backend commands** from `backend/`: one class `mvn -q test -Dtest=<Class>`, several `mvn -q test -Dtest='<A>,<B>'`, all `mvn -q verify`. Integration tests need Docker. **Test classes end in `Test`/`IntegrationTest`** (no failsafe plugin; `*IT` would be skipped silently).
- **Frontend commands** from `ecommerce-frontend/`: `[ -d node_modules ] || npm ci`, then `npm test -- <file>`, `npm test`, `npm run lint` (`--max-warnings 0`), `npm run build`. **Part A touches no file under `ecommerce-frontend/`; Part B touches nothing else.**
- Every Mongo access through `TenantMongo`; new documents extend `TenantDocument`; new tenant collections get a `BackfillTarget` (offboarding export/purge walk them; `TenantOffboardingIntegrationTest.everyTenantOwnedCollectionIsRegistered` enforces it). No `MongoTemplate` in main code.
- No Spring, Jackson or Mongo types in `com.sixpack.marketing.domain`. Layering `web → application → domain ← infra`. `ModularityTests.nothingOutsideMarketingDependsOnIt` must stay green: `order` publishes a read port (`PaidOrders`) and a public record (`OrderAttribution`), `marketing` consumes them.
- Off-request work sets `TenantContext` from the store being processed (`TenantContext.runAs/callAs` over `StoreDirectory.stores()`), never from ambient state.
- **Secrets are write-only.** No response, log line, metric tag, trace attribute, audit value, export, exception message or `toString()` contains a secret. Adapters never log a `RestClientException` object (its message holds the request URL, and GA4's secret is a query parameter); they log the class name only.
- Adapters call fixed provider hosts from platform configuration (`app.marketing.*.base-url`, defaults to the real hosts). Owners never enter a URL.
- **Marketing never slows or fails checkout:** the recorder makes no network call, swallows its own errors, and attribution input is sanitised (bad values dropped), never rejected.
- Money and currency: the order's own currency, else `StoreConfig.currency()`. Never a hard-coded INR.
- `Feature.MARKETING` off → all marketing endpoints 404; recorder, sweep and dispatcher skip the store.
- Errors through `ApiException` (`{success:false, message}`); responses through `ApiResponse.of`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Parallel work (Phase 2, product feed, on `feat/marketing--feed`) edits** `marketing.tsx`, `marketingAPI.ts`, `MarketingAdminController`, `AuditAction`, `adminGuides.ts`, `contracts.md`, `requirements.md` (and probably `MarketingBackfill`, `application.yml`, `IntegrationTest.cleanDatabase`, `_admin-ui.scss`). This plan keeps those edits small and additive: new controller (`MarketingServerController`), new component (`ServerEventsSection.tsx`, mounted with one line), new backfill class (`OutboxBackfill`), new SCSS partial, outbox cleanup in its own test base, enum values and doc rows on their own lines. **Known merge points:** `MarketingAdminController` (request/view records), `application.yml` (if the feed also adds an `app.marketing:` block, merge both into **one** `app.marketing` key — duplicate YAML keys break startup), `AuditAction`/`auditAPI.ts` (adjacent new lines), `contracts.md` data-model rows after `marketing_integrations`, and the ADR number (this plan takes **ADR-029**; if Phase 2 also wrote one, renumber the later merge).

## Fixed API contract (Part B relies on it — implement exactly; Phase 1 stays backward compatible)

All routes: tenant `ADMIN` only (admin-agreement 403 applies), **404 when the store's `marketing` switch is off**, `{provider}` ∈ `google|meta` else 404.

### Integration card (every response that returns a card: `GET /admin/marketing`, `PUT`, `DELETE`)

```json
{
  "provider": "meta",
  "status": "NOT_CONNECTED | ACTIVE | ERROR",
  "browser": { "pixelId": "123456789012345" },
  "updatedAt": "2026-10-31T10:00:00.123Z",
  "server": { "enabled": true, "hasSecret": true, "testEventCode": "TEST123", "available": true },
  "lastError": { "at": "2026-10-31T10:05:00Z", "code": "AUTH_FAILED", "message": "Meta rejected the saved secret. Create a new one and save it here." },
  "lastSuccessAt": "2026-10-31T10:00:30Z",
  "deliveries": [ { "eventId": "6720f0c2a1b2c3d4e5f60718", "orderNumber": "ORD-20261031-K7M3QX", "eventName": "purchase",
                    "status": "SENT", "attempts": 1, "createdAt": "…", "updatedAt": "…", "nextAttemptAt": null, "error": null } ]
}
```

- The Phase 1 keys and their meaning are unchanged; the four new keys are always present (in this order, after `updatedAt`).
- `server.testEventCode` is always `null` for `google`. `server.available` is `false` when the platform has no `MARKETING_KEYS`. A NOT_CONNECTED card has `server = {enabled:false, hasSecret:false, testEventCode:null, available}`, `lastError:null`, `lastSuccessAt:null`.
- `status`: `NOT_CONNECTED` when no browser ID is saved; `ERROR` when `server.enabled` and `lastError` is newer than `lastSuccessAt` (or there was no success); else `ACTIVE`.
- `deliveries`: the provider's newest 10 outbox rows, newest first (`createdAt` desc). `nextAttemptAt` only for `PENDING`, else `null`. `error` is `null` or `{code, message}`. **No email, phone, hash, IP, user agent or click ID ever appears.**
- `status` of a delivery ∈ `PENDING | SENDING | SENT | FAILED | SKIPPED`. Error codes (closed list): retried — `TIMEOUT`, `RATE_LIMITED`, `PROVIDER_DOWN`; failed at once — `AUTH_FAILED`, `REJECTED`, `SECRET_UNREADABLE`; failed after 6 attempts — `GAVE_UP`; skipped — `NO_CONSENT`, `SERVER_OFF`, `TOO_OLD`, `ORDER_GONE`. `lastError.code` is one of `AUTH_FAILED | REJECTED | SECRET_UNREADABLE | GAVE_UP`.

### `PUT /api/v1/admin/marketing/{provider}`

Body `{browser?: {…phase 1 fields}, server?: {enabled?: boolean, secret?: string, testEventCode?: string|null}}`.

| Rule | Behaviour |
|---|---|
| neither `browser` nor `server` | 400 `browser: is required` (Phase 1 message) |
| `browser` omitted | saved IDs kept |
| `server` omitted | saved server settings (and secret) kept — a Phase 1 client never wipes them |
| `server.enabled` missing | `false` |
| `server.secret` missing or `null` | stored secret kept; a string replaces it (stripped) and clears `lastError` |
| `server.testEventCode` | Meta only (ignored for Google); `null`/blank clears it; never used on real events, only by `/test` |
| resulting browser IDs all empty (browser sent) | row deleted **including the secret** → NOT_CONNECTED (Phase 1 rule) |
| invalid browser ID | Phase 1 400 messages, checked first |

Exact 400 messages for `server`:

| Condition | Message |
|---|---|
| `enabled:true` or a `secret` while the platform has no key | `server: not available on this platform yet` |
| `enabled:true` or a `secret` without the provider's ID (saved or in this body); also `{server}` alone when nothing is saved | `server: save the Meta Pixel ID first` / `server: save the GA4 measurement ID first` |
| `secret` not `^[A-Za-z0-9_-]{16,512}$` | `server.secret: use 16-512 letters, digits, _ or -` |
| `enabled:true`, no stored and no new secret | `server.secret: is required to turn on server events` |
| `testEventCode` (Meta) not `^[A-Za-z0-9]{1,32}$` | `server.testEventCode: use 1-32 letters or digits` |

Audit `INTEGRATION_UPDATED` diff adds `serverEnabled` (values), `testEventCode` (values) and `secret` as a **redacted** change (`{field:"secret", redacted:true}`, no values — the audit UI renders it as "secret changed").

### `POST /api/v1/admin/marketing/{provider}/test` (new)

No body. Sends one synthetic purchase with the stored secret: Meta with the saved `test_event_code`, GA4 to the debug endpoint. Never touches the outbox, `lastError` or the card status.

- 200 `{success:true, test:{ok: boolean, code: string|null, message: string}}` for any provider answer (`code` = an error code above when `ok` is false).
- 400 `server: not available on this platform yet` · `server: save the Meta Pixel ID first` / `server: save the GA4 measurement ID first` · `server.secret: save the secret first` · Meta only: `server.testEventCode: set a test event code from Events Manager first`.
- 429 `Too many test events, please wait a minute` from the 11th call in one clock minute per store (per instance).
- Audited `INTEGRATION_TESTED`, entity `marketing_integration:<provider>`, outcome `SUCCESS` when `ok`, else `FAILURE`; no changes.

### `GET /api/v1/admin/marketing/{provider}/deliveries?page=` (new)

`page` ≥ 1 (default 1), 20 per page, newest first → 200 `{success:true, deliveries:[delivery], page, totalPage}`. Past the end → `deliveries: []`. Non-number or < 1 → 400 `page: must be a positive number`.

### `POST /api/v1/order/checkout` gains optional `attribution`

```json
{ "items": [...], "coupon": "…",
  "attribution": { "utmSource": "…", "utmMedium": "…", "utmCampaign": "…", "utmContent": "…", "utmTerm": "…",
                   "gclid": "…", "fbclid": "…", "fbp": "fb.1.1730000000000.1098115397", "fbc": "fb.1.1730000000000.IwAR…",
                   "gaClientId": "1234567890.1730000000", "landingPath": "/product/lamp",
                   "consent": { "analytics": true, "marketing": true, "version": "2026-10-31" } } }
```

- **Never fails a checkout.** No `consent` (or a `version` not `^[A-Za-z0-9.-]{1,20}$`) → the whole attribution is dropped. Each other bad value is dropped: `utm*` stripped of control characters, blank → absent, cut to 150; `gclid`/`fbclid` `^[A-Za-z0-9_.-]{1,512}$`; `fbp` `^fb\.\d\.\d{1,15}\.\d{1,25}$`; `fbc` `^fb\.\d\.\d{1,15}\.[A-Za-z0-9_.-]{1,512}$`; `gaClientId` `^\d{1,20}\.\d{1,20}$`; `landingPath` must start with one `/`, ≤ 512.
- **Server-side consent enforcement:** `utm*`, click IDs, `fbp`/`fbc` kept only when `consent.marketing`; `gaClientId` only when `consent.analytics`; `landingPath` when either. With marketing consent the server also records the request's `User-Agent` (≤ 512) and `sourceUrl = https://<store host>/shipping`. No IP is stored.
- The server stamps `consent.at`. The response is unchanged. Order JSON (`GET /order/…`) does **not** expose `attribution`.
- Erasure (`DELETE /user/me`, admin user delete) removes `attribution` from that user's orders.

### Call budgets

| Screen / action | Calls | Budget |
|---|---|---|
| Admin → Marketing first load (cards incl. server settings and last 10 deliveries each) | `GET /admin/marketing` | 1 |
| Save server settings | `PUT /admin/marketing/{provider}` with `{server}` (returns the card) | 1 |
| Send test event | `POST /admin/marketing/{provider}/test` | 1 |
| Older deliveries | `GET /admin/marketing/{provider}/deliveries?page=` | 1 per page |
| Checkout | `attribution` inside the existing `POST /order/checkout` | +0 |

## Distributed-systems decisions (each one becomes a row of "Consistency & failure" in `architecture.md`, Task A14)

| # | Boundary | Failure modes | Decision | Proven by |
|---|---|---|---|---|
| D1 | Paid order → outbox (Mongo, **no transactions**) | process dies after the order is saved but before the row is written; listener throws | Synchronous `@EventListener(OrderChangedEvent)` queues with an idempotent upsert and swallows its own errors; `ConversionSweep` every 5 min re-queues consented paid orders of the last 72 h that have no row (the order is the source of truth — the outbox is derived from it, so there is no dual write to lose) | `theSweepQueuesAPaidOrderTheListenerMissed`, `aFailingRecorderNeverFailsCheckout` |
| D2 | Duplicate `OrderChangedEvent` (placed, advanced, refunded), sweep racing the listener, N replicas | duplicate rows → double send | **Idempotency key `eventId = order._id`** (the same id the browser sends as Meta `eventID` / GA4 `transaction_id`); unique index `{tenantId, provider, eventId}` + `$setOnInsert` upsert (Mongo retries a duplicate-key upsert on that exact key; a `DuplicateKeyException` is caught as "already queued") | `theSameOrderIsQueuedOnceHoweverOftenItIsSeen` (incl. 10 concurrent), `addIfAbsentIsIdempotentUnderConcurrency` |
| D3 | Several dispatchers / replicas | double send | Claim one row at a time with `findAndModify` PENDING(due) → SENDING, `leaseUntil = now + 2 min`, `attempts + 1`, fresh `claimToken`; finishing requires the same `claimToken` (fencing), so a dispatcher whose lease expired cannot overwrite the new owner's result | `concurrentDispatchersSendEachRowOnce`, `aStaleClaimCannotOverwriteTheNewOwner` |
| D4 | Replica dies mid-send | row stuck in SENDING | A SENDING row with an expired lease is claimable again (attempt counted). Delivery to the provider is therefore **at-least-once**; the provider de-duplicates by `event_id` / `transaction_id` | `anExpiredLeaseIsSentAgainAndALiveOneIsLeftAlone` |
| D5 | Outbound HTTP | slow, hung, reset | Connect 2 s, read 5 s on every call (`SimpleClientHttpRequestFactory`); the 2-min lease is far longer than one call. Test sends use the same client. Sync vs async: purchases are **async** (checkout must not wait on or fail because of Meta/Google); the test event is **sync** (the owner is waiting for the verdict) | `aTimeoutIsRetried` (stub delays 1.5 s, read timeout 500 ms) |
| D6 | 5xx, 429, timeout, Meta transient/throttle codes | transient | Retry only these, at one layer (the outbox): backoff 1 m, 5 m, 30 m, 2 h, 12 h × jitter U(0.8, 1.2), `Retry-After` honoured, capped at 12 h; 6 attempts (≈ 15 h, inside GA4's 72 h and Meta's 7 days), then FAILED `GAVE_UP` | `BackoffTest`, `retryableFailuresBackOffThenGiveUpAndFlagTheCard`, `throttlingHonoursRetryAfter` |
| D7 | 4xx (bad token, bad parameter) | permanent | No retry: FAILED at once, the card gets `lastError` (→ `ERROR`) with an owner-actionable message; provider text is cleaned (secret replaced, ≤ 200 chars) | `aRejectedEventFailsAtOnceAndShowsTheReason`, `aRejectedTokenAsksTheOwnerForANewOne`, adapter tests |
| D8 | Provider outage / slowness | threads tied up, hammering a down provider, one provider starving the other | Circuit breaker per provider per instance (5 transient failures in a row → open 60 s, then the first failure reopens it; rows stay PENDING untouched). Bulkhead: one dispatcher thread per provider; at most 50 rows per store per pass (fairness between stores). No Resilience4j (not on the classpath; ~25 lines instead) | `ProviderBreakerTest` |
| D9 | Dead letter | silent loss | FAILED and SKIPPED rows stay 30 days (TTL) and show in the delivery log; `lastError` flags the card; alerts on failed ratio > 5 % for 15 min and oldest due row > 30 min. Replay: the owner fixes the secret; new orders flow (re-queueing old FAILED rows is a manual DB step, documented in the runbook) | admin ITs, `promtool check rules` |
| D10 | Shutdown | half-sent row, lost work | `SmartLifecycle.stop()`: stop claiming, wait ≤ 20 s for the in-flight send, then exit; anything left in SENDING is recovered by lease expiry (D4) | `stopFinishesTheSendInFlightAndClaimsNothingMore` |
| D11 | Cross-tenant | wrong credentials or rows | Dispatcher, sweep and recorder run per store inside `TenantContext`; `TenantMongo` scopes every query; the sealed secret's AAD is `tenantId|PROVIDER|server.secret`, so a ciphertext copied to another store's row does not decrypt | `sendsEachStoresOwnCredentials`, `anotherStoresCopiedSecretCannotBeRead`, `SecretBoxTest` |
| D12 | Admin `PUT` vs dispatcher writes on the same integration row | lost update | Field-level `$set` only: the dispatcher writes only `lastError`/`lastSuccessAt`; `PUT` writes browser/server fields and only *unsets* `lastError` with a new secret. Two admins editing one card at once: last write wins per field (rare; no `version` field, as in Phase 1) | by construction |
| D13 | Erasure and consent after queueing | PII of a deleted user or a withdrawn shopper sent | Outbox rows hold **no PII** (ids and status only); the payload is built from the order at send time, and consent is re-checked then → `SKIPPED NO_CONSENT` once erasure removed `attribution` | `rowsAreSkippedWhenConsentOrSettingsAreGone` |
| D14 | Clock | replica clock skew | Server `Instant.now()` for leases and backoff; skew (NTP, ms) is negligible against a 2-min lease and ≥ 48 s backoff. Documented, not tested | — |
| — | Caching / isolation level | — | No cache added (one integration read per row is cheap). Every write is a single-document atomic operation; no multi-document invariant exists | — |

## Review Focus

1. **Secret exposure** — token/API secret never in a response, audit value, export, log, exception message, `toString()`, metric or trace (GA4 secret is a query parameter, so the adapters use a plain `RestClient.builder()` without observation and never log a `RestClientException`). Pinned by `SecretBoxTest`, `aSecretIsStoredEncryptedAndNeverReturned`, `serverChangesAreAuditedWithoutTheSecret`, `theExportLeavesOutSecretsAndTheOutbox`, `nothingLogsTheSecret`, `credentialsNeverPrintTheSecret`.
2. **Double counting** — one outbox row per `{store, provider, order}`; one send per row across dispatchers; `event_id`/`transaction_id` = order `_id` = the browser's id. Pinned by D2/D3 tests and the adapter payload tests.
3. **Cross-tenant leakage** — store A's rows sent only with store A's credentials; copied ciphertext unreadable. D11 tests.
4. **No consent, no server event** — Meta needs marketing consent, GA4 analytics consent, checked at queue time and again at send time; hashed email/phone only to Meta and only for consent given under cookie version ≥ `2026-10-31`. Pinned by recorder ITs, `ConversionsTest`, `hashedEmailGoesOnlyToMeta`, `OrderAttributionTest`.
5. **Checkout is never slowed or failed** — recorder has no `MarketingDestination` dependency (no network), swallows errors; attribution junk is dropped. Pinned by `aFailingRecorderNeverFailsCheckout`, `junkAttributionNeverFailsCheckout`.
6. **Provider outage** — retry/backoff/give-up/breaker/bulkhead/shutdown per the decisions table.
7. **Phase 1 compatibility** — a `PUT {browser}` keeps server settings; old cards without `server` render as before; first load stays 1 call.

## Changes from the phase plan / spec (decided while planning — read before starting)

- **No PII in `marketing_outbox` (spec §5.4 said "payload (hashed PII only)").** Rows hold ids and status; the dispatcher builds the payload from the order when it sends. Erasure then needs no outbox clean-up (the order's `attribution` and customer snapshot are erased, the row is SKIPPED), consent is re-checked at send time, and an export or a leaked row reveals nothing. Hashing still happens only in memory before the HTTP call.
- **GA4 server events need *analytics* consent; Meta needs *marketing* consent** (spec §6 said marketing for both). Matches the browser categories from Phase 1 (GA4 = analytics). GA4 gets **no** email/phone (no `user_data`), and `consent.ad_user_data/ad_personalization` follow the marketing choice.
- **Hashed email/phone go to Meta only for consents given under cookie-notice version ≥ `2026-10-31`** (`app.marketing.pii-consent-version`). The Phase 1 privacy text says "we don't send them your … email, phone"; Task B7 rewrites it and bumps the cookie notice (and `CONSENT_VERSION`) so the banner asks again. Bumping `LEGAL_PRIVACY_VERSION` (re-prompting every signed-in user) stays an owner decision (as in Phase 1).
- **Attribution gains `gaClientId`** (GA4 MP requires `client_id`; without the browser's `_ga` id the server purchase is not tied to the visit — a stable id derived from the order is used then) and server-captured **`userAgent` + `sourceUrl`** (Meta requires `client_user_agent` and `event_source_url` for website events). No IP stored or sent.
- **`OrderAttribution` lives in the `order` root package** (public API), not `order/domain`, because `marketing` reads it through `PaidOrders`. It sits on `OrderDetails` (additive, like ADR-015/016) and is copied from the payment intent at confirm.
- **`PUT` accepts `{server}` without `browser`** (the server section saves alone). The empty-body message is unchanged.
- **Status is derived, not stored** (`ERROR` = enabled + `lastError` newer than `lastSuccessAt`), so there is no status field to keep consistent.
- **Dispatcher loops over stores** (`StoreDirectory`) instead of one cross-tenant claim: `TenantMongo` deliberately has no cross-tenant write. Adds one scoped `TenantMongo.findAndModify`. `shortcut:` one claim query per store per provider per pass; switch to a cross-tenant due index when stores ≫ 500.
- **Metric `marketing.outbox.pending` → `marketing.outbox.due.age` (seconds)**: the alert needs "how long has the oldest due row waited", which a count does not give.
- **Not built (named on purpose):** the per-`{tenant, provider}` token bucket and the `TenantQuotas` daily outbox cap (rows come only from paid orders; the 50-rows-per-store pass gives fairness); a re-encrypt job for key rotation (rotate by putting the new key first; old secrets re-encrypt when the owner saves); replaying FAILED rows from the UI; exposing attribution on order views; Google Ads enhanced conversions; OAuth.
- **Test-event rate limit is per instance** (in-memory fixed window, like `AuthRateLimitFilter`): N replicas allow N × 10/min.
- **Phone normalisation** prefixes `91` to a 10-digit number without `+` (`app.marketing.calling-code`, design Q6: India only).
- **Tenant export** drops the `secrets` field of `marketing_integrations` and the whole `marketing_outbox` (short-lived queue, like `payment_intents`). Purge covers both through their `BackfillTarget`s.
- **Provider stub** for ITs is a ~60-line JDK `HttpServer` (no WireMock dependency); adapter payload and fault mapping are unit-tested with `MockRestServiceServer` (as `RazorpayGatewayTest`).
- **Alerts are `warning` in both namespaces** (a lost conversion is not a page), unlike the audit-write alert's prod `critical`.

## File map

Backend main (`backend/src/main/java/com/sixpack/…`):

| File | Responsibility | Task |
|---|---|---|
| `marketing/application/MarketingProperties.java`, `../resources/application.yml`, `/.env.example` | `app.marketing.*`, `MARKETING_KEYS` | A1 |
| `marketing/domain/SecretSealer.java`, `SealedSecret.java`; `marketing/infra/crypto/SecretBox.java` | AES-256-GCM port + adapter | A1 |
| `shared/tenant/TenantMongo.java` | scoped `findAndModify` | A2 |
| `marketing/domain/Integration.java`, `ServerSettings.java`, `LastError.java`, `DeliveryCode.java`, `DeliveryException.java`, `MarketingDestination.java`, `Conversion.java`, `OutboxRow.java`, `Outbox.java` | domain | A3 |
| `marketing/infra/mongo/MarketingOutboxDocument.java`, `MongoOutbox.java`, `OutboxBackfill.java` | `marketing_outbox` | A4 |
| `marketing/infra/mongo/MarketingIntegrationDocument.java`, `MongoIntegrationRepository.java`, `domain/IntegrationRepository.java`, `application/IntegrationService.java`, `application/MarketingAudit.java`, `application/ServerEventsService.java`, `web/MarketingAdminController.java`, `web/DeliveryView.java`, `tenant/application/TenantExport.java` | server settings, card shape, export | A5 |
| `order/OrderAttribution.java`, `order/domain/{OrderDetails,Order,PaymentIntent}.java`, `order/infra/mongo/{OrderDocument,PaymentIntentDocument,MongoOrderRepository,MongoPaymentIntentRepository}.java`, `order/application/CheckoutService.java`, `order/web/OrderController.java` | attribution capture, erasure | A6 |
| `order/PaidOrders.java`, `order/application/OrderPaidOrders.java`, `order/domain/OrderRepository.java` | read port | A7 |
| `marketing/application/PiiHasher.java`, `Conversions.java` | hashing, payload source | A8 |
| `marketing/infra/http/ProviderHttp.java`, `infra/meta/MetaConversionsApi.java`, `infra/google/Ga4MeasurementProtocol.java` | adapters | A9 |
| `marketing/application/ConversionRecorder.java`, `ConversionSweep.java` | queueing | A10 |
| `marketing/application/ConversionDispatcher.java`, `Backoff.java`, `ProviderBreaker.java` | sending | A11 |
| `marketing/application/FixedWindowLimiter.java`, `ServerEventsService.java`, `web/MarketingServerController.java`, `audit/AuditAction.java` | test event, delivery log | A12 |
| `infra/observability/k8s/alerts.yaml` | alerts | A13 |
| `docs/foundations/{architecture,contracts,requirements,compliance-consent,test-rollout}.md`, `k8s/README.md`, `k8s/scripts/seal-backend-secrets.sh` | docs | A14 |

Backend tests (`backend/src/test/java/com/sixpack/…`): `marketing/infra/crypto/SecretBoxTest` (A1), `it/TenantMongoIntegrationTest` (A2), `marketing/domain/MarketingDomainTest` (A3), `it/ProviderStub`, `it/MarketingServerIntegrationTest` (base), `it/MarketingOutboxIntegrationTest` (A4), `it/MarketingServerSettingsIntegrationTest`, `it/MarketingAdminIntegrationTest`, `it/TenantOffboardingIntegrationTest` (A5), `order/OrderAttributionTest`, `it/CheckoutAttributionIntegrationTest`, `it/OrderErasureIntegrationTest` (A6, A7), `marketing/application/{PiiHasherTest,ConversionsTest}` (A8), `marketing/infra/meta/MetaConversionsApiTest`, `marketing/infra/google/Ga4MeasurementProtocolTest` (A9), `it/ConversionRecorderIntegrationTest`, `marketing/application/ConversionRecorderTest` (A10), `marketing/application/{BackoffTest,ProviderBreakerTest,ConversionDispatcherLifecycleTest}`, `it/ConversionDispatcherIntegrationTest` (A11), `marketing/application/FixedWindowLimiterTest`, `it/MarketingServerEventsIntegrationTest` (A12).

Frontend (`ecommerce-frontend/src/…`, Part B):

| File | Responsibility | Task |
|---|---|---|
| `redux/api/marketingAPI.ts` | types + `saveServerSettings`, `sendTestEvent`, `deliveries` | B1 |
| `redux/api/auditAPI.ts` (+ test) | `INTEGRATION_TESTED` | B1 |
| `pages/admin/apps/serverEventsForm.ts` (+ `.test.ts`) | pure helpers: validation, payload, error mapping, labels | B2 |
| `pages/admin/apps/ServerEventsSection.tsx` (+ `.test.tsx`), `styles/admin-styles/_server-events.scss`, `styles/app.scss` | the section inside each card | B3 |
| `pages/admin/apps/marketing.tsx` | one-line mount | B3 (step 4) |
| `utils/attribution.ts` (+ `.test.ts`), `main.tsx` | consent-gated first touch, checkout payload | B5 |
| `types/api-types.ts`, `pages/shipping.tsx` | send `attribution` with checkout | B6 |
| `help/adminGuides.ts`, `legal/policies.ts` (+ test), `utils/consent.ts` | help article, privacy/cookie text, `CONSENT_VERSION` = backend `app.marketing.pii-consent-version` | B7 |

---

# Part A — Backend (and docs)

Worktree `../6Pack-marketing-events-be`, branch `feat/marketing--server-events--backend`.

### Task A0: Baseline

**Files:** none.

- [ ] **Step 1: Create the worktree** (command in Global Constraints), then `cd ../6Pack-marketing-events-be && git status --short` → clean.
- [ ] **Step 2: Record the baseline**

Run: `cd backend && mvn -q test -Dtest='Marketing*Test,ModularityTests,TenantOffboardingIntegrationTest,OrderErasureIntegrationTest,TenantMongoIntegrationTest'`
Expected: PASS. If anything fails here, stop and report — it is not caused by this plan.

---

### Task A1 (phase plan 3.1): `SecretBox` — AES-256-GCM for owner secrets, keys from `MARKETING_KEYS`

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/application/MarketingProperties.java`
- Create: `backend/src/main/java/com/sixpack/marketing/domain/SealedSecret.java`, `SecretSealer.java`
- Create: `backend/src/main/java/com/sixpack/marketing/infra/crypto/SecretBox.java`
- Modify: `backend/src/main/resources/application.yml`, `.env.example`
- Test: `backend/src/test/java/com/sixpack/marketing/infra/crypto/SecretBoxTest.java`

**Interfaces:**
- Produces: `SecretSealer { boolean available(); SealedSecret seal(String plaintext, String aad); String open(SealedSecret, String aad); static String aad(String tenantId, Provider, String field); final class UnreadableSecretException }`; `record SealedSecret(String keyId, String iv, String ciphertext)` (base64); `MarketingProperties(keys, piiConsentVersion, callingCode, breakerOpenFor, meta{baseUrl, apiVersion}, google{baseUrl}, http{connectTimeout, readTimeout})`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/sixpack/marketing/infra/crypto/SecretBoxTest.java`:

```java
package com.sixpack.marketing.infra.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.SecretSealer.UnreadableSecretException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private static final String SECRET = "fake-meta-token-aaaaaaaa";
    private static final String AAD = SecretSealer.aad("t_a", Provider.META, "server.secret");

    /** {@code id:<base64 of 32 bytes all equal to fill>}, built here so no key literal sits in the repo. */
    private static String key(String id, int fill) {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) fill);
        return id + ":" + Base64.getEncoder().encodeToString(raw);
    }

    private final SecretBox box = new SecretBox(key("k1", 7));

    @Test
    void opensWhatItSealed() {
        assertThat(box.open(box.seal(SECRET, AAD), AAD)).isEqualTo(SECRET);
    }

    @Test
    void theSameSecretSealsDifferentlyEachTimeWithATwelveByteIv() {
        SealedSecret a = box.seal(SECRET, AAD);
        SealedSecret b = box.seal(SECRET, AAD);
        assertThat(a.keyId()).isEqualTo("k1");
        assertThat(Base64.getDecoder().decode(a.iv())).hasSize(12);
        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext()).doesNotContain(SECRET);
    }

    @Test
    void anotherStoresOrProvidersBindingCannotOpenIt() {
        SealedSecret sealed = box.seal(SECRET, AAD);
        for (String other : List.of(SecretSealer.aad("t_b", Provider.META, "server.secret"),
                SecretSealer.aad("t_a", Provider.GOOGLE, "server.secret"),
                SecretSealer.aad("t_a", Provider.META, "oauth.refreshToken"))) {
            assertThatThrownBy(() -> box.open(sealed, other)).isInstanceOf(UnreadableSecretException.class);
        }
    }

    @Test
    void tamperedCiphertextTagOrIvIsRefused() {
        SealedSecret sealed = box.seal(SECRET, AAD);
        byte[] data = Base64.getDecoder().decode(sealed.ciphertext());
        byte[] iv = Base64.getDecoder().decode(sealed.iv());
        for (SealedSecret bad : List.of(
                new SealedSecret("k1", sealed.iv(), flip(data, 0)),               // ciphertext
                new SealedSecret("k1", sealed.iv(), flip(data, data.length - 1)), // tag (GCM appends it)
                new SealedSecret("k1", Base64.getEncoder().encodeToString(Base64.getDecoder().decode(flip(iv, 3))),
                        sealed.ciphertext()))) {
            assertThatThrownBy(() -> box.open(bad, AAD)).isInstanceOf(UnreadableSecretException.class);
        }
    }

    @Test
    void anOlderKeyStillOpensAfterANewOneIsPutFirst() {
        SealedSecret old = box.seal(SECRET, AAD);
        SecretBox rotated = new SecretBox(key("k2", 9) + "," + key("k1", 7));
        assertThat(rotated.open(old, AAD)).isEqualTo(SECRET);
        assertThat(rotated.seal(SECRET, AAD).keyId()).isEqualTo("k2");
    }

    @Test
    void aSecretSealedWithARemovedKeyIsUnreadable() {
        SealedSecret old = box.seal(SECRET, AAD);
        assertThatThrownBy(() -> new SecretBox(key("k2", 9)).open(old, AAD))
                .isInstanceOf(UnreadableSecretException.class).hasMessageNotContaining(SECRET);
    }

    @Test
    void withoutKeysNothingIsAvailableAndSealingFails() {
        for (SecretBox empty : List.of(new SecretBox(""), new SecretBox((String) null), new SecretBox("  "))) {
            assertThat(empty.available()).isFalse();
            assertThatThrownBy(() -> empty.seal(SECRET, AAD)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(box.available()).isTrue();
    }

    @Test
    void aMalformedKeyListFailsWithoutEchoingTheKeyMaterial() {
        String good = key("k1", 7);
        String material = good.substring(3);
        for (String spec : List.of("k1", "k1:" + material.substring(0, 20), "bad id!:" + material, good + "," + good,
                ":" + material)) {
            assertThatThrownBy(() -> new SecretBox(spec)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MARKETING_KEYS").hasMessageNotContaining(material);
        }
    }

    private static String flip(byte[] bytes, int index) {
        byte[] copy = bytes.clone();
        copy[index] ^= 1;
        return Base64.getEncoder().encodeToString(copy);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=SecretBoxTest`
Expected: FAIL — compilation error `cannot find symbol … class SecretBox` (and `SealedSecret`, `SecretSealer`).

- [ ] **Step 3: Implement**

`backend/src/main/java/com/sixpack/marketing/domain/SealedSecret.java`:

```java
package com.sixpack.marketing.domain;

/** An encrypted secret as stored: which key sealed it, the 12-byte IV and the ciphertext with its GCM tag (base64). */
public record SealedSecret(String keyId, String iv, String ciphertext) {
}
```

`backend/src/main/java/com/sixpack/marketing/domain/SecretSealer.java`:

```java
package com.sixpack.marketing.domain;

/**
 * Encrypts owner-set secrets (ADR-029). The associated data binds a ciphertext to one store, provider and field,
 * so a value copied to another row does not decrypt.
 */
public interface SecretSealer {

    /** False when the platform has no key: server-side features are then off (fail closed). */
    boolean available();

    SealedSecret seal(String plaintext, String aad);

    /** @throws UnreadableSecretException when the key is gone or the data or its binding does not match */
    String open(SealedSecret sealed, String aad);

    static String aad(String tenantId, Provider provider, String field) {
        return tenantId + "|" + provider.name() + "|" + field;
    }

    /** Carries no detail on purpose: nothing about the secret or the key may reach a log or a response. */
    final class UnreadableSecretException extends RuntimeException {
        public UnreadableSecretException() {
            super("Stored secret cannot be read", null, false, false);
        }
    }
}
```

`backend/src/main/java/com/sixpack/marketing/application/MarketingProperties.java`:

```java
package com.sixpack.marketing.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform settings for marketing (application.yml {@code app.marketing}). {@code keys} is MARKETING_KEYS:
 * {@code k1:<base64 of 32 bytes>,k0:<older>}, newest first; empty means server-side conversions are unavailable.
 */
@ConfigurationProperties("app.marketing")
public record MarketingProperties(String keys, String piiConsentVersion, String callingCode, Duration breakerOpenFor,
                                  Meta meta, Google google, Http http) {

    public record Meta(String baseUrl, String apiVersion) {
    }

    public record Google(String baseUrl) {
    }

    public record Http(Duration connectTimeout, Duration readTimeout) {
    }
}
```

`backend/src/main/java/com/sixpack/marketing/infra/crypto/SecretBox.java`:

```java
package com.sixpack.marketing.infra.crypto;

import com.sixpack.marketing.application.MarketingProperties;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.SecretSealer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** AES-256-GCM, random 12-byte IV, 128-bit tag (ADR-029). Seals with the newest key, opens with the one named. */
@Component
class SecretBox implements SecretSealer {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9]{1,16}$");
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKey> keys = new LinkedHashMap<>(); // newest first
    private final SecureRandom random = new SecureRandom();

    @Autowired
    SecretBox(MarketingProperties props) {
        this(props.keys());
    }

    SecretBox(String spec) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        String[] entries = spec.split(",");
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i].strip();
            int colon = entry.indexOf(':');
            String id = colon < 0 ? "" : entry.substring(0, colon);
            byte[] raw = decode(colon < 0 ? "" : entry.substring(colon + 1));
            if (!KEY_ID.matcher(id).matches() || raw.length != 32 || keys.containsKey(id)) {
                // Never echo the entry: it is key material.
                throw new IllegalStateException("MARKETING_KEYS entry " + (i + 1)
                        + " must be <id>:<base64 of 32 bytes> with a unique id");
            }
            if (Arrays.equals(raw, new byte[32])) {
                log.warn("MARKETING_KEYS key '{}' is the all-zero example key: generate a real one "
                        + "(openssl rand -base64 32) for any shared environment", id);
            }
            keys.put(id, new SecretKeySpec(raw, "AES"));
        }
    }

    @Override
    public boolean available() {
        return !keys.isEmpty();
    }

    @Override
    public SealedSecret seal(String plaintext, String aad) {
        Map.Entry<String, SecretKey> newest = keys.entrySet().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No marketing key configured"));
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            byte[] sealed = cipher(Cipher.ENCRYPT_MODE, newest.getValue(), iv, aad)
                    .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new SealedSecret(newest.getKey(), b64(iv), b64(sealed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public String open(SealedSecret sealed, String aad) {
        SecretKey key = sealed == null ? null : keys.get(sealed.keyId());
        if (key == null) {
            throw new UnreadableSecretException();
        }
        try {
            byte[] iv = Base64.getDecoder().decode(sealed.iv());
            byte[] plain = cipher(Cipher.DECRYPT_MODE, key, iv, aad)
                    .doFinal(Base64.getDecoder().decode(sealed.ciphertext()));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new UnreadableSecretException();
        }
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] iv, String aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
```

`application.yml` — under `app:` (sibling of `features:`; **if the feed branch already added `app.marketing`, merge into that block**):

```yaml
  marketing:
    # Encryption keys for store owners' marketing secrets (ADR-029): "k1:<base64 of 32 bytes>,k0:<older>", newest
    # first. Empty: server-side conversions are unavailable (fail closed); browser tags and the feed still work.
    keys: ${MARKETING_KEYS:}
    # Cookie-notice version from which shoppers are told that Meta gets their hashed email and phone (policies.ts).
    pii-consent-version: "2026-10-31"
    # Prefix for 10-digit phone numbers without a country code (India only for now, design Q6).
    calling-code: "91"
    dispatch-interval: PT30S
    sweep-interval: PT5M
    breaker-open-for: PT60S
    meta:
      base-url: https://graph.facebook.com
      api-version: v25.0
    google:
      base-url: https://www.google-analytics.com
    http:
      connect-timeout: 2s
      read-timeout: 5s
```

`.env.example` — append:

```bash
# --- marketing server events (ADR-029) ---
# Keys that encrypt store owners' Conversions API token / Measurement Protocol secret in MongoDB:
# "k1:<base64 of 32 bytes>", newest first, comma-separated; keep an old key listed until every store saved again.
# Generate one with: openssl rand -base64 32. Empty = server events unavailable (the Marketing page says so).
# The all-zero value below is for local development only; the backend logs a warning when it sees it.
MARKETING_KEYS=k1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest='SecretBoxTest,ModularityTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/application/MarketingProperties.java \
        backend/src/main/java/com/sixpack/marketing/domain/SealedSecret.java \
        backend/src/main/java/com/sixpack/marketing/domain/SecretSealer.java \
        backend/src/main/java/com/sixpack/marketing/infra/crypto/SecretBox.java \
        backend/src/main/resources/application.yml .env.example \
        backend/src/test/java/com/sixpack/marketing/infra/crypto/SecretBoxTest.java
git commit -m "feat(marketing): seal owner secrets with AES-256-GCM bound to store and provider

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A2: `TenantMongo.findAndModify` (scoped, fail-closed) for the outbox claim

**Files:**
- Modify: `backend/src/main/java/com/sixpack/shared/tenant/TenantMongo.java`
- Test: `backend/src/test/java/com/sixpack/it/TenantMongoIntegrationTest.java`

- [ ] **Step 1: Write the failing test** — add to `TenantMongoIntegrationTest` (add imports `org.springframework.data.mongodb.core.FindAndModifyOptions`, `java.util.Optional`):

```java
    @Test
    void findAndModifyOnlyTouchesTheCurrentTenantsDocuments() {
        saveAs(A, "a-row", "x");
        saveAs(B, "b-row", "x");

        Optional<Probe> claimed = TenantContext.callAs(A, () -> tenantMongo.findAndModify(
                Query.query(Criteria.where("kind").is("x")), new Update().set("kind", "claimed"),
                FindAndModifyOptions.options().returnNew(true), Probe.class));
        assertThat(claimed).get().satisfies(p -> {
            assertThat(p.name).isEqualTo("a-row");
            assertThat(p.kind).isEqualTo("claimed");
        });

        // Nothing left for A, and B's row was never a candidate.
        assertThat(TenantContext.callAs(A, () -> tenantMongo.findAndModify(Query.query(Criteria.where("kind").is("x")),
                new Update().set("kind", "claimed"), FindAndModifyOptions.options(), Probe.class))).isEmpty();
        assertThat(TenantContext.callAs(B, () -> tenantMongo.find(new Query(), Probe.class)))
                .extracting(p -> p.kind).containsExactly("x");
    }

    @Test
    void findAndModifyRefusesToMoveADocumentOrRunWithoutATenant() {
        saveAs(A, "a-row", "x");
        assertThatThrownBy(() -> TenantContext.callAs(A, () -> tenantMongo.findAndModify(new Query(),
                new Update().set("tenantId", B.id()), FindAndModifyOptions.options(), Probe.class)))
                .isInstanceOf(IllegalArgumentException.class);
        try (TenantContext.Scope ignored = TenantContext.open(null)) {
            assertThatThrownBy(() -> tenantMongo.findAndModify(new Query(), new Update().set("kind", "y"),
                    FindAndModifyOptions.options(), Probe.class)).isInstanceOf(IllegalStateException.class);
        }
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=TenantMongoIntegrationTest`
Expected: FAIL — compilation error `cannot find symbol … method findAndModify`.

- [ ] **Step 3: Implement** — in `TenantMongo`, after `findAndRemove` (import `org.springframework.data.mongodb.core.FindAndModifyOptions`):

```java
    /** Atomically updates and returns one of the current tenant's documents (job claiming). */
    public <T> Optional<T> findAndModify(Query query, UpdateDefinition update, FindAndModifyOptions options,
                                         Class<T> type) {
        rejectTenantChange(update);
        return Optional.ofNullable(mongo.findAndModify(scoped(query), update, options, type));
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest='TenantMongoIntegrationTest,TenantIsolationArchitectureTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/shared/tenant/TenantMongo.java \
        backend/src/test/java/com/sixpack/it/TenantMongoIntegrationTest.java
git commit -m "feat(tenancy): scoped findAndModify for claiming queued work

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A3: Domain — server settings, `ERROR` status, delivery codes, adapter port, outbox port

**Files:**
- Modify: `backend/src/main/java/com/sixpack/marketing/domain/Integration.java`
- Create (domain): `ServerSettings.java`, `LastError.java`, `DeliveryCode.java`, `DeliveryException.java`, `MarketingDestination.java`, `Conversion.java`, `OutboxRow.java`, `Outbox.java`
- Test: `backend/src/test/java/com/sixpack/marketing/domain/MarketingDomainTest.java`

- [ ] **Step 1: Write the failing tests** — add to `MarketingDomainTest` (imports `static org.assertj.core.api.Assertions.assertThatThrownBy`, `java.time.Duration`):

```java
    private static final BrowserTags PIXEL = new BrowserTags(null, null, null, null, "1234567890");
    private static final SealedSecret SEALED = new SealedSecret("k1", "aXY=", "Y2lwaGVy");

    @Test
    void aFailingServerConnectionShowsAsErrorUntilASendSucceeds() {
        Instant t0 = Instant.parse("2026-10-31T10:00:00Z");
        ServerSettings on = new ServerSettings(true, null, SEALED);
        LastError error = new LastError(t0, "AUTH_FAILED", "Meta rejected the saved secret.");

        assertThat(new Integration(Provider.META, PIXEL, on, error, null, t0).status())
                .isEqualTo(Integration.Status.ERROR);
        assertThat(new Integration(Provider.META, PIXEL, on, error, t0.minusSeconds(60), t0).status())
                .isEqualTo(Integration.Status.ERROR);
        assertThat(new Integration(Provider.META, PIXEL, on, error, t0.plusSeconds(60), t0).status())
                .isEqualTo(Integration.Status.ACTIVE);
        assertThat(new Integration(Provider.META, PIXEL, ServerSettings.OFF, error, null, t0).status())
                .isEqualTo(Integration.Status.ACTIVE); // server events off: the browser tag still works
        assertThat(new Integration(Provider.META, BrowserTags.NONE, on, error, null, t0).status())
                .isEqualTo(Integration.Status.NOT_CONNECTED);
    }

    @Test
    void theServerIdIsThePixelForMetaAndTheMeasurementIdForGoogle() {
        assertThat(new Integration(Provider.META, PIXEL, Instant.EPOCH).serverId()).isEqualTo("1234567890");
        assertThat(Integration.serverId(Provider.GOOGLE, new BrowserTags("G-AB12CD3", null, null, null, null)))
                .isEqualTo("G-AB12CD3");
        assertThat(Integration.serverId(Provider.GOOGLE, PIXEL)).isNull();
        assertThat(Integration.notConnected(Provider.META).server()).isEqualTo(ServerSettings.OFF);
    }

    @Test
    void serverSettingsCheckTheTestCodeAndTheSecretShape() {
        assertThat(new ServerSettings(false, "  TEST123 ", null).testEventCode()).isEqualTo("TEST123");
        assertThat(new ServerSettings(false, " ", null).testEventCode()).isNull();
        assertThatThrownBy(() -> new ServerSettings(false, "TE ST", null))
                .hasMessage("server.testEventCode: use 1-32 letters or digits");
        assertThat(ServerSettings.checkSecret(" fake-meta-token-aaaaaaaa ")).isEqualTo("fake-meta-token-aaaaaaaa");
        for (String bad : new String[] {"short", "has space in the middle!", "x".repeat(513), ""}) {
            assertThatThrownBy(() -> ServerSettings.checkSecret(bad))
                    .hasMessage("server.secret: use 16-512 letters, digits, _ or -");
        }
        assertThat(new ServerSettings(true, null, SEALED).hasSecret()).isTrue();
    }

    @Test
    void deliveryCodesSayWhatHappenedAndWhichAreRetried() {
        assertThat(java.util.Arrays.stream(DeliveryCode.values()).filter(DeliveryCode::retryable))
                .containsExactlyInAnyOrder(DeliveryCode.TIMEOUT, DeliveryCode.RATE_LIMITED, DeliveryCode.PROVIDER_DOWN);
        assertThat(DeliveryCode.AUTH_FAILED.message(Provider.META))
                .isEqualTo("Meta rejected the saved secret. Create a new one and save it here.");
        assertThat(DeliveryCode.TOO_OLD.message(Provider.GOOGLE)).contains("Google");
    }

    @Test
    void aDeliveryFailureCarriesOnlyItsCodeAsMessage() {
        DeliveryException e = new DeliveryException(DeliveryCode.REJECTED, "Invalid parameter", Duration.ofSeconds(5));
        assertThat(e.getMessage()).isEqualTo("REJECTED");
        assertThat(e.getStackTrace()).isEmpty();
        assertThat(e.ownerMessage(Provider.META)).isEqualTo("Meta rejected the event: Invalid parameter");
        assertThat(new DeliveryException(DeliveryCode.TIMEOUT, null, null).ownerMessage(Provider.GOOGLE))
                .isEqualTo("Google did not answer in time. We will try again.");
    }

    @Test
    void credentialsNeverPrintTheSecret() {
        MarketingDestination.Credentials c = new MarketingDestination.Credentials("1234567890", "fake-meta-token-aaaaaaaa");
        assertThat(c.toString()).contains("1234567890").doesNotContain("fake-meta-token");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=MarketingDomainTest`
Expected: FAIL — compilation errors (`ServerSettings`, `LastError`, `DeliveryCode`, … not found; `Integration` has no 6-argument constructor).

- [ ] **Step 3: Implement**

`Integration.java` (replace the file):

```java
package com.sixpack.marketing.domain;

import java.time.Instant;

/**
 * One provider's saved settings for the store: the public browser tag IDs (phase 1) and the server-side settings
 * (phase 3). {@code lastError}/{@code lastSuccessAt} are written by the dispatcher only.
 */
public record Integration(Provider provider, BrowserTags browser, ServerSettings server, LastError lastError,
                          Instant lastSuccessAt, Instant updatedAt) {

    public enum Status { NOT_CONNECTED, ACTIVE, ERROR }

    public Integration {
        server = server == null ? ServerSettings.OFF : server;
    }

    /** Phase 1 shape, kept so existing callers compile unchanged. */
    public Integration(Provider provider, BrowserTags browser, Instant updatedAt) {
        this(provider, browser, ServerSettings.OFF, null, null, updatedAt);
    }

    public static Integration notConnected(Provider provider) {
        return new Integration(provider, BrowserTags.NONE, null);
    }

    /** A stored row always has an ID; ERROR while server sending is on and its last failure is newer than its last success. */
    public Status status() {
        if (browser.isEmpty()) {
            return Status.NOT_CONNECTED;
        }
        boolean failing = server.enabled() && lastError != null
                && (lastSuccessAt == null || lastError.at().isAfter(lastSuccessAt));
        return failing ? Status.ERROR : Status.ACTIVE;
    }

    /** The ID the server-side API addresses: the Meta Pixel ID or the GA4 measurement ID; null when not saved. */
    public String serverId() {
        return serverId(provider, browser);
    }

    public static String serverId(Provider provider, BrowserTags browser) {
        return provider == Provider.META ? browser.pixelId() : browser.ga4MeasurementId();
    }
}
```

`ServerSettings.java`:

```java
package com.sixpack.marketing.domain;

import java.util.regex.Pattern;

/**
 * Server-side conversion settings of one provider. {@code testEventCode} is Meta's code for Events Manager → Test
 * events; it is used only by the test-event button, never on real purchases. {@code secret} is sealed (ADR-029).
 */
public record ServerSettings(boolean enabled, String testEventCode, SealedSecret secret) {

    // Declared before OFF: static fields initialise in order and OFF runs the constructor.
    private static final Pattern TEST_CODE = Pattern.compile("^[A-Za-z0-9]{1,32}$");
    private static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9_-]{16,512}$");

    public static final ServerSettings OFF = new ServerSettings(false, null, null);
    /** The associated-data field name the secret is sealed under. */
    public static final String SECRET_FIELD = "server.secret";

    public ServerSettings {
        testEventCode = testEventCode == null || testEventCode.isBlank() ? null : testEventCode.strip();
        if (testEventCode != null && !TEST_CODE.matcher(testEventCode).matches()) {
            throw new IllegalArgumentException("server.testEventCode: use 1-32 letters or digits");
        }
    }

    public boolean hasSecret() {
        return secret != null;
    }

    /** The pasted secret, stripped, or IllegalArgumentException naming the field. Never echoes the value. */
    public static String checkSecret(String raw) {
        String stripped = raw == null ? "" : raw.strip();
        if (!SECRET.matcher(stripped).matches()) {
            throw new IllegalArgumentException("server.secret: use 16-512 letters, digits, _ or -");
        }
        return stripped;
    }
}
```

`LastError.java`:

```java
package com.sixpack.marketing.domain;

import java.time.Instant;

/** Why the provider last refused a purchase, in words the owner can act on. {@code code} is a {@link DeliveryCode} name. */
public record LastError(Instant at, String code, String message) {
}
```

`DeliveryCode.java`:

```java
package com.sixpack.marketing.domain;

/** Why a queued purchase was retried, failed or skipped. The message is shown to the store owner. */
public enum DeliveryCode {
    TIMEOUT("%s did not answer in time. We will try again."),
    RATE_LIMITED("%s asked us to slow down. We will try again."),
    PROVIDER_DOWN("%s had a temporary problem. We will try again."),
    AUTH_FAILED("%s rejected the saved secret. Create a new one and save it here."),
    REJECTED("%s rejected the event"),
    SECRET_UNREADABLE("The saved secret can no longer be read. Save it again."),
    GAVE_UP("%s could not be reached after 6 attempts, so this purchase was not sent."),
    NO_CONSENT("The shopper did not allow this, so nothing was sent."),
    SERVER_OFF("Server events were off or not set up, so nothing was sent."),
    TOO_OLD("The purchase is older than %s accepts, so it was not sent."),
    ORDER_GONE("The order no longer exists, so nothing was sent.");

    private final String template;

    DeliveryCode(String template) {
        this.template = template;
    }

    /** Transient: the row is tried again later. Everything else is final. */
    public boolean retryable() {
        return this == TIMEOUT || this == RATE_LIMITED || this == PROVIDER_DOWN;
    }

    public String message(Provider provider) {
        return String.format(template, provider == Provider.META ? "Meta" : "Google");
    }
}
```

`DeliveryException.java`:

```java
package com.sixpack.marketing.domain;

import java.time.Duration;

/**
 * A provider call that did not succeed. The exception message is only the code: provider text (which might echo a
 * request) travels in {@code detail}, already cleaned, and is shown to the owner but never logged.
 */
public class DeliveryException extends RuntimeException {

    private final DeliveryCode code;
    private final String detail;
    private final Duration retryAfter;

    public DeliveryException(DeliveryCode code, String detail, Duration retryAfter) {
        super(code.name(), null, false, false);
        this.code = code;
        this.detail = detail;
        this.retryAfter = retryAfter;
    }

    public DeliveryCode code() {
        return code;
    }

    /** The provider's Retry-After, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public String ownerMessage(Provider provider) {
        return detail == null ? code.message(provider) : code.message(provider) + ": " + detail;
    }
}
```

`MarketingDestination.java`:

```java
package com.sixpack.marketing.domain;

/** A provider's server-side API (spec §4.2). Adapters call fixed hosts only. */
public interface MarketingDestination {

    Provider provider();

    /** @throws DeliveryException with a retryable code (5xx, 429, timeout) or a final one (4xx) */
    void send(Credentials credentials, Conversion conversion);

    /** Sends one synthetic purchase to the provider's test channel and reports its answer; never throws for provider errors. */
    TestVerdict test(Credentials credentials, String testEventCode, String currency);

    /** {@code id} is the pixel or measurement ID. {@link #toString()} hides the secret. */
    record Credentials(String id, String secret) {
        @Override
        public String toString() {
            return "Credentials[id=" + id + ", secret=***]";
        }
    }

    record TestVerdict(boolean ok, String code, String message) {
    }
}
```

`Conversion.java`:

```java
package com.sixpack.marketing.domain;

import java.time.Instant;
import java.util.List;

/** One purchase as a provider receives it. {@code eventId} is the order id, the browser's event id too. */
public record Conversion(String eventId, Instant at, String currency, double value, double tax, double shipping,
                         String coupon, List<Item> items, Identity identity) {

    public record Item(String id, String name, String variant, double price, int quantity) {
    }

    /**
     * What may identify the shopper, already reduced to what consent allows: hashes are SHA-256 hex of normalised
     * values (null when not allowed); {@code adConsent} is the marketing choice.
     */
    public record Identity(String emailSha256, String phoneSha256, String externalIdSha256, String fbp, String fbc,
                           String gaClientId, String userAgent, String sourceUrl, boolean adConsent) {
    }
}
```

`OutboxRow.java`:

```java
package com.sixpack.marketing.domain;

import java.time.Instant;

/** A purchase queued for one provider. Holds ids and delivery state only: no personal data. */
public record OutboxRow(String id, Provider provider, String eventId, String eventName, String orderId,
                        String orderNumber, Status status, int attempts, Instant nextAttemptAt, String claimToken,
                        DeliveryCode error, Instant createdAt, Instant updatedAt) {

    public enum Status { PENDING, SENDING, SENT, FAILED, SKIPPED }
}
```

`Outbox.java`:

```java
package com.sixpack.marketing.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The current store's queued conversions (marketing_outbox). Every call is scoped to the tenant in context. */
public interface Outbox {

    /** Queues a PENDING row unless one exists for {@code (provider, eventId)}. @return whether a row was added */
    boolean addIfAbsent(Provider provider, String eventId, String orderId, String orderNumber, Instant now);

    /**
     * Claims the oldest due row for this process: PENDING with {@code nextAttemptAt <= now}, or SENDING whose lease ran
     * out. The claim sets a fresh claim token, a lease and counts the attempt.
     */
    Optional<OutboxRow> claim(Provider provider, Instant now, Duration lease);

    /** Ends a claim. @return false when the claim was lost (its lease ran out and another process took the row) */
    boolean finish(OutboxRow claimed, OutboxRow.Status status, DeliveryCode error, Instant nextAttemptAt, Instant now);

    Set<String> existingEventIds(Provider provider, Collection<String> eventIds);

    /** Newest first; {@code page} starts at 1. */
    Page recent(Provider provider, int page, int size);

    record Page(List<OutboxRow> rows, int totalPage) {
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest='MarketingDomainTest,BrowserTagsTest,ModularityTests'`
Expected: PASS (the Phase 1 code still compiles through the 3-argument `Integration` constructor).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/domain backend/src/test/java/com/sixpack/marketing/domain
git commit -m "feat(marketing): domain for server events, delivery codes and the outbox port

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A4 (phase plan 3.3, storage): `marketing_outbox` — idempotent queue, lease claim, fencing, TTL

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingOutboxDocument.java`, `MongoOutbox.java`, `OutboxBackfill.java`
- Create (test helpers): `backend/src/test/java/com/sixpack/it/ProviderStub.java`, `backend/src/test/java/com/sixpack/it/MarketingServerIntegrationTest.java`
- Test: `backend/src/test/java/com/sixpack/it/MarketingOutboxIntegrationTest.java`

**Interfaces:**
- Consumes: `TenantMongo.findAndModify` (A2), `Outbox`/`OutboxRow` (A3).
- Produces: the `Outbox` bean; test base `MarketingServerIntegrationTest` (one Spring context for every phase-3 IT: test key, provider stub, schedulers parked, breaker disabled, 500 ms read timeout) with helpers used by A5, A10–A12.

- [ ] **Step 1: Write the test helpers and the failing test**

`backend/src/test/java/com/sixpack/it/ProviderStub.java`:

```java
package com.sixpack.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Local stand-in for graph.facebook.com and www.google-analytics.com (JDK HttpServer, no extra dependency):
 * records every request and answers as scripted per path prefix. Unscripted paths succeed like the real APIs.
 */
final class ProviderStub {

    record Call(String method, String path, String query, String body) {
    }

    record Reply(int status, String body, Map<String, String> headers, long delayMillis) {
        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of(), 0);
        }
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    private ProviderStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    static ProviderStub start() {
        try {
            return new ProviderStub();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reply(String pathPrefix, Reply reply) {
        replies.put(pathPrefix, reply);
    }

    void reset() {
        calls.clear();
        replies.clear();
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    List<Call> callsTo(String pathPrefix) {
        return calls.stream().filter(c -> c.path().startsWith(pathPrefix)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        calls.add(new Call(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply reply = replies.entrySet().stream().filter(e -> path.startsWith(e.getKey())).map(Map.Entry::getValue)
                .findFirst().orElseGet(() -> path.startsWith("/debug/") ? Reply.of(200, "{\"validationMessages\":[]}")
                        : path.startsWith("/mp/") ? Reply.of(204, "")
                        : Reply.of(200, "{\"events_received\":1,\"messages\":[],\"fbtrace_id\":\"stub\"}"));
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach(exchange.getResponseHeaders()::add);
        try {
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(out);
                }
            }
        } catch (IOException ignored) {
            // the client gave up first (timeout tests)
        } finally {
            exchange.close();
        }
    }
}
```

`backend/src/test/java/com/sixpack/it/MarketingServerIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.storage.TestImages;
import com.sixpack.tenant.domain.Tenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One Spring context for the phase-3 tests: an all-zero test key (so server events are available), both provider
 * hosts pointed at {@link ProviderStub}, the dispatcher and sweep parked (tests call them), the breaker never
 * staying open, and a short read timeout for the timeout tests.
 */
@TestPropertySource(properties = {
        "app.marketing.keys=k1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.marketing.dispatch-interval=PT1H",
        "app.marketing.sweep-interval=PT1H",
        "app.marketing.breaker-open-for=PT0S",
        "app.marketing.http.read-timeout=500ms"})
abstract class MarketingServerIntegrationTest extends MarketingIntegrationTest {

    protected static final ProviderStub STUB = ProviderStub.start();
    protected static final String META_EVENTS = "/v25.0/";

    @DynamicPropertySource
    static void providerHosts(DynamicPropertyRegistry registry) {
        registry.add("app.marketing.meta.base-url", STUB::url);
        registry.add("app.marketing.google.base-url", STUB::url);
    }

    /** marketing_outbox is cleaned here, not in the shared IntegrationTest base (edited by other workstreams). */
    @BeforeEach
    void cleanOutboxAndStub() {
        mongo.getCollection("marketing_outbox").deleteMany(new Document());
        STUB.reset();
    }

    protected record Store(Tenant tenant, String admin, String product, String shopper) {
    }

    /** A store with fake payments, the marketing switch on, Meta and Google set up for server events, a product and a shopper. */
    protected Store configuredStore(String slug, String pixel, String metaToken, String ga4, String apiSecret)
            throws Exception {
        Tenant t = marketing(withFakePayments(createTenant(slug, slug, "shop." + slug + ".test")), true);
        String admin = adminTokenFor(t);
        save(t, admin, "meta", browser("pixelId", pixel)).andExpect(status().isOk());
        saveServer(t, admin, "meta", server(true, metaToken, "TEST123")).andExpect(status().isOk());
        save(t, admin, "google", browser("ga4MeasurementId", ga4)).andExpect(status().isOk());
        saveServer(t, admin, "google", server(true, apiSecret, null)).andExpect(status().isOk());
        return new Store(t, admin, productOn(t, admin), registerOn(t, "buyer@" + slug + ".test"));
    }

    protected ResultActions saveServer(Tenant t, String token, String provider, Map<String, Object> server)
            throws Exception {
        return mvc.perform(put(BASE + "/" + provider).with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("server", server))));
    }

    protected static Map<String, Object> server(boolean enabled, String secret, String testEventCode) {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", enabled);
        if (secret != null) {
            m.put("secret", secret);
        }
        m.put("testEventCode", testEventCode);
        return m;
    }

    protected String productOn(Tenant t, String admin) throws Exception {
        return body(mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .with(host(hostOf(t))).param("name", "Lamp").param("price", "500").param("stock", "50")
                        .param("category", "home").header("Authorization", bearer(admin)))
                .andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
    }

    /** The attribution a consenting shopper's browser sends (cookie version 2026-10-31, so Meta may get hashes). */
    protected static Map<String, Object> consented(boolean analytics, boolean marketing) {
        Map<String, Object> a = new HashMap<>(Map.of("utmSource", "google", "utmCampaign", "diwali",
                "gclid", "Cj0KCQ-abc", "fbclid", "IwAR-xyz", "fbp", "fb.1.1730000000000.1098115397",
                "gaClientId", "1234567890.1730000000", "landingPath", "/product/lamp"));
        a.put("consent", Map.of("analytics", analytics, "marketing", marketing, "version", "2026-10-31"));
        return a;
    }

    /** Checks out one lamp on the store's host and confirms the fake payment. Returns the order id. */
    protected String order(Store s, Map<String, Object> attribution) throws Exception {
        Map<String, Object> checkout = new HashMap<>();
        checkout.put("items", List.of(Map.of("productId", s.product(), "quantity", 1)));
        if (attribution != null) {
            checkout.put("attribution", attribution);
        }
        String providerOrderId = body(mvc.perform(post("/api/v1/order/checkout").with(host(hostOf(s.tenant())))
                        .header("Authorization", bearer(s.shopper())).header("User-Agent", "Mozilla/5.0 test")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(checkout)))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        return body(mvc.perform(post("/api/v1/order/new").with(host(hostOf(s.tenant())))
                        .header("Authorization", bearer(s.shopper())).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("providerOrderId", providerOrderId,
                                "paymentId", "fake_pay_1", "signature", "fake",
                                "shippingInfo", Map.of("address", "12 MG Road", "city", "Bengaluru",
                                        "state", "Karnataka", "country", "India", "pinCode", "560001")))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    protected List<Document> outboxRows(Tenant t) {
        return mongo.getCollection("marketing_outbox").find(new Document("tenantId", t.id()))
                .sort(new Document("createdAt", 1)).into(new ArrayList<>());
    }

    protected Document outboxRow(Tenant t, String provider, String eventId) {
        return mongo.getCollection("marketing_outbox").find(new Document("tenantId", t.id())
                .append("provider", provider).append("eventId", eventId)).first();
    }

    /** Makes every queued row of the store due now, as if its backoff had passed. */
    protected void makeDue(Tenant t) {
        mongo.getCollection("marketing_outbox").updateMany(new Document("tenantId", t.id()),
                new Document("$set", new Document("nextAttemptAt", Date.from(Instant.now().minusSeconds(1)))));
    }

    protected static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Runs {@code task} on {@code n} threads released at the same moment and returns every result. */
    protected static <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
```

`backend/src/test/java/com/sixpack/it/MarketingOutboxIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** marketing_outbox: one row per store, provider and event; one claimant per row; fenced finishes; 30-day TTL. */
class MarketingOutboxIntegrationTest extends MarketingServerIntegrationTest {

    private static final Duration LEASE = Duration.ofMinutes(2);

    @Autowired
    Outbox outbox;

    Tenant acme;
    Tenant globex;

    @BeforeEach
    void stores() {
        acme = createTenant("acme", "Acme", "shop.acme.test");
        globex = createTenant("globex", "Globex", "shop.globex.test");
    }

    private boolean add(Tenant t, String eventId) {
        return TenantContext.callAs(ref(t), () ->
                outbox.addIfAbsent(Provider.META, eventId, eventId, "ORD-" + eventId, Instant.now()));
    }

    private Optional<OutboxRow> claim(Tenant t) {
        return TenantContext.callAs(ref(t), () -> outbox.claim(Provider.META, Instant.now(), LEASE));
    }

    @Test
    void addIfAbsentIsIdempotentUnderConcurrency() throws Exception {
        assertThat(add(acme, "o1")).isTrue();
        assertThat(add(acme, "o1")).isFalse();
        List<Boolean> results = concurrently(10, () -> add(acme, "o2"));
        assertThat(results).containsOnlyOnce(true);
        assertThat(outboxRows(acme)).hasSize(2);

        Document row = outboxRows(acme).get(0);
        assertThat(row.getString("status")).isEqualTo("PENDING");
        assertThat(row.getInteger("attempts")).isZero();
        assertThat(row.getString("orderNumber")).isEqualTo("ORD-o1");
        // The same event for another provider or another store is a different row.
        TenantContext.runAs(ref(acme), () -> outbox.addIfAbsent(Provider.GOOGLE, "o1", "o1", null, Instant.now()));
        add(globex, "o1");
        assertThat(outboxRows(acme)).hasSize(3);
        assertThat(outboxRows(globex)).hasSize(1);
    }

    @Test
    void concurrentClaimantsTakeEachRowOnce() throws Exception {
        IntStream.range(0, 20).forEach(i -> add(acme, "o" + i));
        List<List<String>> taken = concurrently(8, () -> {
            List<String> mine = new ArrayList<>();
            for (Optional<OutboxRow> r = claim(acme); r.isPresent(); r = claim(acme)) {
                mine.add(r.get().eventId());
            }
            return mine;
        });
        List<String> all = taken.stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(20).doesNotHaveDuplicates();
    }

    @Test
    void aRowIsDueOnlyFromItsNextAttemptAndTheOldestComesFirst() {
        add(acme, "later");
        add(acme, "first");
        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", "later"),
                new Document("$set", new Document("nextAttemptAt", Date.from(Instant.now().plusSeconds(600)))));
        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", "first"),
                new Document("$set", new Document("nextAttemptAt", Date.from(Instant.now().minusSeconds(600)))));

        assertThat(claim(acme)).get().extracting(OutboxRow::eventId).isEqualTo("first");
        assertThat(claim(acme)).isEmpty();
    }

    @Test
    void anExpiredLeaseCanBeClaimedAgainAndTheStaleClaimCannotOverwriteTheNewOwner() {
        add(acme, "o1");
        OutboxRow first = claim(acme).orElseThrow();
        assertThat(first.status()).isEqualTo(OutboxRow.Status.SENDING);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(claim(acme)).isEmpty(); // lease still live

        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", "o1"),
                new Document("$set", new Document("leaseUntil", Date.from(Instant.now().minusSeconds(1)))));
        OutboxRow second = claim(acme).orElseThrow();
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());

        boolean staleWon = TenantContext.callAs(ref(acme), () -> outbox.finish(first, OutboxRow.Status.FAILED,
                DeliveryCode.REJECTED, null, Instant.now()));
        assertThat(staleWon).isFalse();
        assertThat(outboxRow(acme, "META", "o1").getString("status")).isEqualTo("SENDING");

        boolean ownerWon = TenantContext.callAs(ref(acme), () -> outbox.finish(second, OutboxRow.Status.SENT, null,
                null, Instant.now()));
        assertThat(ownerWon).isTrue();
        Document done = outboxRow(acme, "META", "o1");
        assertThat(done.getString("status")).isEqualTo("SENT");
        assertThat(done).doesNotContainKeys("leaseUntil", "claimToken", "error");
    }

    @Test
    void aRetryKeepsTheErrorAndTheNextAttempt() {
        add(acme, "o1");
        OutboxRow row = claim(acme).orElseThrow();
        Instant next = Instant.now().plusSeconds(60);
        TenantContext.runAs(ref(acme), () -> outbox.finish(row, OutboxRow.Status.PENDING, DeliveryCode.TIMEOUT, next,
                Instant.now()));
        Document stored = outboxRow(acme, "META", "o1");
        assertThat(stored.getString("status")).isEqualTo("PENDING");
        assertThat(stored.getString("error")).isEqualTo("TIMEOUT");
        assertThat(stored.getDate("nextAttemptAt").toInstant()).isEqualTo(next.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void oneStoreNeverClaimsAnothersRows() {
        add(acme, "o1");
        assertThat(claim(globex)).isEmpty();
        assertThat(claim(acme)).isPresent();
    }

    @Test
    void theRecentPageIsNewestFirstAndCountsPages() {
        IntStream.range(0, 25).forEach(i -> {
            add(acme, "o" + i);
            mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", "o" + i),
                    new Document("$set", new Document("createdAt", Date.from(Instant.parse("2026-10-31T10:00:00Z")
                            .plusSeconds(i)))));
        });
        Outbox.Page first = TenantContext.callAs(ref(acme), () -> outbox.recent(Provider.META, 1, 20));
        Outbox.Page second = TenantContext.callAs(ref(acme), () -> outbox.recent(Provider.META, 2, 20));
        assertThat(first.totalPage()).isEqualTo(2);
        assertThat(first.rows()).hasSize(20).first().extracting(OutboxRow::eventId).isEqualTo("o24");
        assertThat(second.rows()).hasSize(5).last().extracting(OutboxRow::eventId).isEqualTo("o0");
        assertThat(TenantContext.callAs(ref(acme), () -> outbox.existingEventIds(Provider.META, List.of("o1", "zz"))))
                .containsExactly("o1");
    }

    @Test
    void rowsExpireThirtyDaysAfterTheyWereQueued() {
        add(acme, "o1");
        Document ttl = mongo.getCollection("marketing_outbox").listIndexes().into(new ArrayList<>()).stream()
                .filter(i -> i.containsKey("expireAfterSeconds")).findFirst().orElseThrow();
        assertThat(ttl.get("key", Document.class)).containsEntry("createdAt", 1);
        assertThat(((Number) ttl.get("expireAfterSeconds")).longValue()).isEqualTo(30L * 24 * 60 * 60);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=MarketingOutboxIntegrationTest`
Expected: FAIL — the context starts but `Outbox` has no bean: `NoSuchBeanDefinitionException: No qualifying bean of type 'com.sixpack.marketing.domain.Outbox'` (or, if the `server`/`saveServer` helpers are compiled first, still the missing bean).

- [ ] **Step 3: Implement**

`MarketingOutboxDocument.java`:

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A purchase queued for one provider (spec §5.4, without the payload: it is built from the order when sent). */
@Document("marketing_outbox")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_provider_event", def = "{'tenantId': 1, 'provider': 1, 'eventId': 1}",
                unique = true),
        @CompoundIndex(name = "tenant_provider_due", def = "{'tenantId': 1, 'provider': 1, 'status': 1, 'nextAttemptAt': 1}"),
        @CompoundIndex(name = "tenant_provider_created", def = "{'tenantId': 1, 'provider': 1, 'createdAt': -1}")
})
class MarketingOutboxDocument extends TenantDocument {

    @Id
    String id;
    /** {@code Provider} enum name. */
    String provider;
    String eventId;
    String eventName;
    String orderId;
    String orderNumber;
    String status;
    Integer attempts;
    Instant nextAttemptAt;
    Instant leaseUntil;
    String claimToken;
    /** {@code DeliveryCode} name of the last problem, if any. */
    String error;
    @Indexed(name = "created_ttl", expireAfter = "30d")
    Instant createdAt;
    Instant updatedAt;
}
```

`MongoOutbox.java`:

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoOutbox implements Outbox {

    private static final String PENDING = OutboxRow.Status.PENDING.name();
    private static final String SENDING = OutboxRow.Status.SENDING.name();

    private final TenantMongo mongo;

    MongoOutbox(TenantMongo mongo) {
        this.mongo = mongo;
    }

    /** One upsert on the unique key: concurrent callers (listener, sweep, replicas) end with exactly one row. */
    @Override
    public boolean addIfAbsent(Provider provider, String eventId, String orderId, String orderNumber, Instant now) {
        Update insert = new Update().setOnInsert("eventName", "purchase").setOnInsert("orderId", orderId)
                .setOnInsert("orderNumber", orderNumber).setOnInsert("status", PENDING).setOnInsert("attempts", 0)
                .setOnInsert("nextAttemptAt", now).setOnInsert("createdAt", now).setOnInsert("updatedAt", now);
        try {
            return mongo.upsert(byEvent(provider, eventId), insert, MarketingOutboxDocument.class)
                    .getUpsertedId() != null;
        } catch (DuplicateKeyException e) {
            return false; // a concurrent insert of the same event won
        }
    }

    @Override
    public Optional<OutboxRow> claim(Provider provider, Instant now, Duration lease) {
        Query due = Query.query(Criteria.where("provider").is(provider.name()).orOperator(
                        Criteria.where("status").is(PENDING).and("nextAttemptAt").lte(now),
                        Criteria.where("status").is(SENDING).and("leaseUntil").lt(now)))
                .with(Sort.by("nextAttemptAt"));
        Update take = new Update().set("status", SENDING).set("leaseUntil", now.plus(lease))
                .set("claimToken", UUID.randomUUID().toString()).set("updatedAt", now).inc("attempts", 1);
        return mongo.findAndModify(due, take, FindAndModifyOptions.options().returnNew(true),
                MarketingOutboxDocument.class).map(MongoOutbox::toDomain);
    }

    /** Fenced: only the holder of the current claim token may end the claim. */
    @Override
    public boolean finish(OutboxRow claimed, OutboxRow.Status status, DeliveryCode error, Instant nextAttemptAt,
                          Instant now) {
        Update update = new Update().set("status", status.name()).set("updatedAt", now)
                .unset("leaseUntil").unset("claimToken");
        if (error == null) {
            update.unset("error");
        } else {
            update.set("error", error.name());
        }
        if (nextAttemptAt != null) {
            update.set("nextAttemptAt", nextAttemptAt);
        }
        Query mine = Query.query(Criteria.where("id").is(claimed.id()).and("status").is(SENDING)
                .and("claimToken").is(claimed.claimToken()));
        return mongo.updateFirst(mine, update, MarketingOutboxDocument.class).getModifiedCount() == 1;
    }

    @Override
    public Set<String> existingEventIds(Provider provider, Collection<String> eventIds) {
        if (eventIds.isEmpty()) {
            return Set.of();
        }
        Query query = Query.query(Criteria.where("provider").is(provider.name()).and("eventId").in(eventIds));
        query.fields().include("eventId");
        return mongo.find(query, MarketingOutboxDocument.class).stream().map(d -> d.eventId)
                .collect(Collectors.toSet());
    }

    @Override
    public Page recent(Provider provider, int page, int size) {
        Query byProvider = Query.query(Criteria.where("provider").is(provider.name()));
        long total = mongo.count(byProvider, MarketingOutboxDocument.class);
        List<OutboxRow> rows = mongo.find(Query.of(byProvider)
                        .with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")))
                        .skip((long) (page - 1) * size).limit(size), MarketingOutboxDocument.class)
                .stream().map(MongoOutbox::toDomain).toList();
        return new Page(rows, (int) ((total + size - 1) / size));
    }

    private static Query byEvent(Provider provider, String eventId) {
        return Query.query(Criteria.where("provider").is(provider.name()).and("eventId").is(eventId));
    }

    private static OutboxRow toDomain(MarketingOutboxDocument d) {
        DeliveryCode error = d.error == null ? null : Arrays.stream(DeliveryCode.values())
                .filter(c -> c.name().equals(d.error)).findFirst().orElse(null);
        return new OutboxRow(d.id, Provider.valueOf(d.provider), d.eventId, d.eventName, d.orderId, d.orderNumber,
                OutboxRow.Status.valueOf(d.status), d.attempts == null ? 0 : d.attempts, d.nextAttemptAt, d.claimToken,
                error, d.createdAt, d.updatedAt);
    }
}
```

`OutboxBackfill.java` (a separate class so the feed branch's edits to `MarketingBackfill` don't collide):

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers marketing_outbox so offboarding purges it with the rest of the store (the export skips it). */
@Configuration
class OutboxBackfill {

    @Bean
    BackfillTarget marketingOutboxBackfill() {
        return () -> "marketing_outbox";
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest='MarketingOutboxIntegrationTest,TenantOffboardingIntegrationTest'`
Expected: PASS (`everyTenantOwnedCollectionIsRegistered` sees the new `@Document` and its `BackfillTarget`).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingOutboxDocument.java \
        backend/src/main/java/com/sixpack/marketing/infra/mongo/MongoOutbox.java \
        backend/src/main/java/com/sixpack/marketing/infra/mongo/OutboxBackfill.java \
        backend/src/test/java/com/sixpack/it/ProviderStub.java \
        backend/src/test/java/com/sixpack/it/MarketingServerIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/MarketingOutboxIntegrationTest.java
git commit -m "feat(marketing): per-store conversion outbox with lease claims and fenced finishes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A5 (phase plan 3.1, API): server settings on the card — write-only secret, `ERROR` status, delivery log on first load, export without secrets

**Files:**
- Modify: `marketing/infra/mongo/MarketingIntegrationDocument.java`, `MongoIntegrationRepository.java`, `marketing/domain/IntegrationRepository.java`, `marketing/application/IntegrationService.java`, `marketing/application/MarketingAudit.java`, `marketing/web/MarketingAdminController.java`, `tenant/application/TenantExport.java`
- Create: `marketing/application/ServerEventsService.java`, `marketing/web/DeliveryView.java`
- Test: create `it/MarketingServerSettingsIntegrationTest.java`; modify `it/MarketingAdminIntegrationTest.java`, `it/TenantOffboardingIntegrationTest.java`

**Interfaces:**
- Produces: `IntegrationService.save(Provider, BrowserTags tagsOrNull, ServerChange changeOrNull)`, `record ServerChange(boolean enabled, String secret, String testEventCode)`, `serverAvailable()`, package `find(Provider)`, `static idName(Provider)`; `IntegrationRepository.saveServer/recordFailure/recordSuccess`; `ServerEventsService.recent(Provider)`, `page(Provider, int)`; card JSON per the contract.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/sixpack/it/MarketingServerSettingsIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** PUT/GET of server settings: write-only secret, validation, ERROR status, audit without the secret. */
class MarketingServerSettingsIntegrationTest extends MarketingServerIntegrationTest {

    static final String TOKEN = "fake-meta-token-aaaaaaaa";

    Tenant acme;
    String admin;

    @BeforeEach
    void storeWithAPixel() throws Exception {
        acme = marketing(createTenant("acme", "Acme", "shop.acme.test"), true);
        admin = adminTokenFor(acme);
        save(acme, admin, "meta", browser("pixelId", "123456789012345")).andExpect(status().isOk());
    }

    private JsonNode metaCard() throws Exception {
        return body(list(acme, admin).andExpect(status().isOk()).andReturn()).get("integrations").get(1);
    }

    @Test
    void aSecretIsStoredEncryptedAndNeverReturned() throws Exception {
        String put = saveServer(acme, admin, "meta", server(true, TOKEN, "TEST123")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(put).doesNotContain(TOKEN);
        JsonNode card = json.readTree(put).get("integration");
        assertThat(card.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.convertValue(card.get("server"), Map.class)).isEqualTo(Map.of("enabled", true,
                "hasSecret", true, "testEventCode", "TEST123", "available", true));
        assertThat(card.get("lastError").isNull()).isTrue();
        assertThat(card.get("deliveries")).isEmpty();

        Document row = rows(acme).get(0);
        Document sealed = row.get("secrets", Document.class).get("server", Document.class);
        assertThat(sealed.getString("keyId")).isEqualTo("k1");
        assertThat(sealed.getString("ciphertext")).isNotBlank();
        assertThat(row.toJson()).doesNotContain(TOKEN);
        assertThat(list(acme, admin).andReturn().getResponse().getContentAsString()).doesNotContain(TOKEN);
    }

    @Test
    void aPhaseOneSaveKeepsTheServerSettingsAndAServerSaveKeepsTheIds() throws Exception {
        saveServer(acme, admin, "meta", server(true, TOKEN, "TEST123")).andExpect(status().isOk());
        String before = rows(acme).get(0).get("secrets", Document.class).toJson();

        save(acme, admin, "meta", browser("pixelId", "999999999999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.server.enabled").value(true))
                .andExpect(jsonPath("$.integration.server.hasSecret").value(true));
        assertThat(rows(acme).get(0).get("secrets", Document.class).toJson()).isEqualTo(before);

        saveServer(acme, admin, "meta", server(false, null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.browser.pixelId").value("999999999999"))
                .andExpect(jsonPath("$.integration.server.enabled").value(false))
                .andExpect(jsonPath("$.integration.server.hasSecret").value(true))
                .andExpect(jsonPath("$.integration.server.testEventCode").isEmpty());
    }

    @Test
    void turningOnNeedsTheIdAndASecretAndBadValuesAreRefusedNamingTheField() throws Exception {
        Tenant bare = marketing(createTenant("bare", "Bare", "shop.bare.test"), true);
        String bareAdmin = adminTokenFor(bare);
        saveServer(bare, bareAdmin, "meta", server(true, TOKEN, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server: save the Meta Pixel ID first"));
        saveServer(bare, bareAdmin, "google", server(false, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server: save the GA4 measurement ID first"));
        assertThat(rows(bare)).isEmpty();

        saveServer(acme, admin, "meta", server(true, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.secret: is required to turn on server events"));
        saveServer(acme, admin, "meta", server(true, "short", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.secret: use 16-512 letters, digits, _ or -"));
        saveServer(acme, admin, "meta", server(false, TOKEN, "TE ST")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.testEventCode: use 1-32 letters or digits"));
        assertThat(rows(acme).get(0)).doesNotContainKey("secrets");
        assertThat(auditEntries("INTEGRATION_UPDATED")).hasSize(1); // only the pixel save
    }

    @Test
    void googleIgnoresATestEventCode() throws Exception {
        save(acme, admin, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isOk());
        saveServer(acme, admin, "google", server(true, "fake-ga4-secret-aaaaaaa", "TEST123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.server.testEventCode").isEmpty())
                .andExpect(jsonPath("$.integration.server.enabled").value(true));
    }

    @Test
    void aFailingCardShowsErrorAndANewSecretClearsIt() throws Exception {
        saveServer(acme, admin, "meta", server(true, TOKEN, null)).andExpect(status().isOk());
        mongo.getCollection("marketing_integrations").updateOne(new Document("tenantId", acme.id()),
                new Document("$set", new Document("lastError", new Document("at", Date.from(Instant.now()))
                        .append("code", "AUTH_FAILED").append("message", "Meta rejected the saved secret."))));

        JsonNode failing = metaCard();
        assertThat(failing.get("status").asText()).isEqualTo("ERROR");
        assertThat(failing.get("lastError").get("code").asText()).isEqualTo("AUTH_FAILED");
        assertThat(failing.get("lastError").get("message").asText()).isEqualTo("Meta rejected the saved secret.");

        saveServer(acme, admin, "meta", server(true, "fake-meta-token-bbbbbbbb", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.status").value("ACTIVE"))
                .andExpect(jsonPath("$.integration.lastError").isEmpty());
        assertThat(rows(acme).get(0)).doesNotContainKey("lastError");
    }

    @Test
    void clearingTheIdsRemovesTheSecretToo() throws Exception {
        saveServer(acme, admin, "meta", server(true, TOKEN, null)).andExpect(status().isOk());
        save(acme, admin, "meta", browser("pixelId", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.integration.server.hasSecret").value(false));
        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void serverChangesAreAuditedWithoutTheSecret() throws Exception {
        saveServer(acme, admin, "meta", server(true, TOKEN, "TEST123")).andExpect(status().isOk());
        Document entry = auditEntries("INTEGRATION_UPDATED").get(0);
        assertThat(entry.toJson()).doesNotContain(TOKEN);
        assertThat(entry.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("serverEnabled", "testEventCode", "secret");
        assertThat(changeOf(entry, "serverEnabled").get("before")).isEqualTo(false);
        assertThat(changeOf(entry, "serverEnabled").get("after")).isEqualTo(true);
        assertThat(changeOf(entry, "testEventCode").get("after")).isEqualTo("TEST123");
        assertThat(changeOf(entry, "secret").getBoolean("redacted")).isTrue();
        assertThat(changeOf(entry, "secret")).doesNotContainKeys("before", "after");
    }

    @Test
    void theCardListsTheNewestTenDeliveriesWithoutPersonalData() throws Exception {
        for (int i = 0; i < 12; i++) {
            mongo.getCollection("marketing_outbox").insertOne(new Document("tenantId", acme.id())
                    .append("provider", "META").append("eventId", "o" + i).append("eventName", "purchase")
                    .append("orderId", "o" + i).append("orderNumber", "ORD-" + i).append("status", "SENT")
                    .append("attempts", 1).append("createdAt", Date.from(Instant.now().plusSeconds(i)))
                    .append("updatedAt", Date.from(Instant.now())).append("nextAttemptAt", Date.from(Instant.now())));
        }
        JsonNode deliveries = metaCard().get("deliveries");
        assertThat(deliveries).hasSize(10);
        assertThat(deliveries.get(0).get("eventId").asText()).isEqualTo("o11");
        assertThat(fieldNames(deliveries.get(0))).containsExactly("eventId", "orderNumber", "eventName", "status",
                "attempts", "createdAt", "updatedAt", "nextAttemptAt", "error");
        assertThat(deliveries.get(0).get("nextAttemptAt").isNull()).isTrue(); // only PENDING rows show it
    }

    @Test
    void anEmptyBodyIsStillRejectedAsInPhaseOne() throws Exception {
        mvc.perform(put(BASE + "/meta").with(host(hostOf(acme))).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("browser: is required"));
        assertThat(List.of(rows(acme).get(0).keySet())).isNotEmpty();
    }
}
```

In `MarketingAdminIntegrationTest` (default context: **no** key):
- In `bothCardsStartNotConnectedWithGoogleFirst` replace the field-name assertion with:
  ```java
          assertThat(fieldNames(google)).containsExactly("provider", "status", "browser", "updatedAt", "server",
                  "lastError", "lastSuccessAt", "deliveries");
  ```
- Add:

```java
    @Test
    void withoutEncryptionKeysServerEventsAreUnavailable() throws Exception {
        save(acme, admin, "meta", browser("pixelId", "123456789012345")).andExpect(status().isOk());
        JsonNode meta = body(list(acme, admin).andExpect(status().isOk()).andReturn()).get("integrations").get(1);
        assertThat(meta.get("server").get("available").asBoolean()).isFalse();
        assertThat(meta.get("server").get("hasSecret").asBoolean()).isFalse();
        assertThat(meta.get("deliveries")).isEmpty();

        mvc.perform(put(BASE + "/meta").with(host(hostOf(acme))).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"server\":{\"enabled\":true,\"secret\":\"fake-meta-token-aaaaaaaa\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server: not available on this platform yet"));
        mvc.perform(put(BASE + "/meta").with(host(hostOf(acme))).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"server\":{\"enabled\":false}}"))
                .andExpect(status().isOk());
        assertThat(rows(acme).get(0)).doesNotContainKey("secrets");
    }
```

In `TenantOffboardingIntegrationTest` add:

```java
    @Test
    void theExportLeavesOutSecretsAndTheOutbox() throws Exception {
        Tenant acme = populatedStore();
        mongo.getCollection("marketing_integrations").insertOne(new Document("tenantId", acme.id())
                .append("provider", "META").append("browser", new Document("pixelId", "123456789012345"))
                .append("secrets", new Document("server", new Document("keyId", "k1").append("iv", "aXY=")
                        .append("ciphertext", "c2VhbGVk"))));
        mongo.getCollection("marketing_outbox").insertOne(new Document("tenantId", acme.id())
                .append("provider", "META").append("eventId", "o1").append("status", "SENT"));

        byte[] zip = mvc.perform(get(API + acme.id() + "/export").header("Authorization", bearer(platformToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> files = unzip(zip);

        JsonNode integrations = json.readTree(files.get("marketing_integrations.json"));
        assertThat(integrations).hasSize(1);
        assertThat(integrations.get(0).has("secrets")).isFalse();
        assertThat(new String(files.get("marketing_integrations.json"))).doesNotContain("c2VhbGVk");
        assertThat(files).doesNotContainKey("marketing_outbox.json");
        assertThat(json.readTree(files.get("manifest.json")).get("omitted").toString())
                .contains("marketing_integrations.secrets", "marketing_outbox");
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='MarketingServerSettingsIntegrationTest,MarketingAdminIntegrationTest,TenantOffboardingIntegrationTest'`
Expected: FAIL — e.g. `aSecretIsStoredEncryptedAndNeverReturned`: `No value at JSON path "$.integration.server"` / NPE on `card.get("server")`; `bothCardsStartNotConnectedWithGoogleFirst` lists only 4 fields; the export still contains `secrets`.

- [ ] **Step 3: Implement**

`MarketingIntegrationDocument.java` — add nested classes and fields:

```java
    static class Server {
        Boolean enabled;
        String testEventCode;
    }

    static class Sealed {
        String keyId;
        String iv;
        String ciphertext;
    }

    /** Top-level so the tenant export can drop the whole field (TenantExport.SKIPPED_FIELDS). */
    static class Secrets {
        Sealed server;
    }

    static class LastErrorDoc {
        Instant at;
        String code;
        String message;
    }

    Server server;
    Secrets secrets;
    LastErrorDoc lastError;
    Instant lastSuccessAt;
```

`IntegrationRepository.java` — add:

```java
    /** Sets the server settings on the existing row (never creates one); a new secret also clears the last error. */
    void saveServer(Provider provider, ServerSettings server, boolean newSecret, Instant updatedAt);

    /** Dispatcher only. */
    void recordFailure(Provider provider, LastError error);

    /** Dispatcher only. */
    void recordSuccess(Provider provider, Instant at);
```

`MongoIntegrationRepository.java` — add the three methods and map the new fields in `toDomain` (`save` stays as in Phase 1: browser fields + `updatedAt` only):

```java
    @Override
    public void saveServer(Provider provider, ServerSettings server, boolean newSecret, Instant updatedAt) {
        Update update = new Update().set("server.enabled", server.enabled())
                .set("server.testEventCode", server.testEventCode()).set("updatedAt", updatedAt);
        if (newSecret) {
            SealedSecret s = server.secret();
            update.set("secrets.server.keyId", s.keyId()).set("secrets.server.iv", s.iv())
                    .set("secrets.server.ciphertext", s.ciphertext()).unset("lastError");
        }
        mongo.updateFirst(byProvider(provider), update, MarketingIntegrationDocument.class);
    }

    @Override
    public void recordFailure(Provider provider, LastError error) {
        mongo.updateFirst(byProvider(provider), new Update().set("lastError.at", error.at())
                .set("lastError.code", error.code()).set("lastError.message", error.message()),
                MarketingIntegrationDocument.class);
    }

    @Override
    public void recordSuccess(Provider provider, Instant at) {
        mongo.updateFirst(byProvider(provider), new Update().set("lastSuccessAt", at),
                MarketingIntegrationDocument.class);
    }

    /** Re-validates on read: a value edited by hand in the database never reaches a storefront script. */
    private static Integration toDomain(MarketingIntegrationDocument d) {
        MarketingIntegrationDocument.Browser b = d.browser == null ? new MarketingIntegrationDocument.Browser()
                : d.browser;
        MarketingIntegrationDocument.Sealed s = d.secrets == null ? null : d.secrets.server;
        ServerSettings server = new ServerSettings(d.server != null && Boolean.TRUE.equals(d.server.enabled),
                d.server == null ? null : d.server.testEventCode,
                s == null ? null : new SealedSecret(s.keyId, s.iv, s.ciphertext));
        LastError error = d.lastError == null ? null
                : new LastError(d.lastError.at, d.lastError.code, d.lastError.message);
        return new Integration(Provider.valueOf(d.provider), new BrowserTags(b.ga4MeasurementId, b.adsConversionId,
                b.adsPurchaseLabel, b.gtmContainerId, b.pixelId), server, error, d.lastSuccessAt, d.updatedAt);
    }
```

`MarketingAudit.java` — replace `POLICY` and `view`:

```java
    static final FieldPolicy POLICY = Changes.policy(List.of("ga4MeasurementId", "adsConversionId",
            "adsPurchaseLabel", "gtmContainerId", "pixelId", "serverEnabled", "testEventCode"), List.of("secret"));

    static Map<String, Object> view(Integration i) {
        BrowserTags t = i.browser();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ga4MeasurementId", t.ga4MeasurementId());
        m.put("adsConversionId", t.adsConversionId());
        m.put("adsPurchaseLabel", t.adsPurchaseLabel());
        m.put("gtmContainerId", t.gtmContainerId());
        m.put("pixelId", t.pixelId());
        m.put("serverEnabled", i.server().enabled());
        m.put("testEventCode", i.server().testEventCode());
        // Compared only: "secret" is a personal field, so the entry says it changed and never holds a value.
        m.put("secret", i.server().hasSecret() ? i.server().secret().ciphertext() : null);
        return m;
    }
```

`IntegrationService.java` (replace the file):

```java
package com.sixpack.marketing.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.config.CacheConfig;
import com.sixpack.shared.tenant.MarketingTags;
import com.sixpack.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The store's marketing integrations. Callers check {@code Feature.MARKETING} first. */
@Service
public class IntegrationService implements MarketingTags {

    /** A change of the server-side settings. {@code secret} null keeps the stored one. */
    public record ServerChange(boolean enabled, String secret, String testEventCode) {
    }

    private final IntegrationRepository integrations;
    private final AuditTrail audit;
    private final SecretSealer secrets;

    IntegrationService(IntegrationRepository integrations, AuditTrail audit, SecretSealer secrets) {
        this.integrations = integrations;
        this.audit = audit;
        this.secrets = secrets;
    }

    /** False without MARKETING_KEYS: the cards say server events are unavailable. */
    public boolean serverAvailable() {
        return secrets.available();
    }

    /** One entry per provider in {@link Provider} order; a provider with nothing saved is NOT_CONNECTED. */
    public List<Integration> all() {
        Map<Provider, Integration> saved = new EnumMap<>(Provider.class);
        integrations.findAll().forEach(i -> saved.put(i.provider(), i));
        return Arrays.stream(Provider.values())
                .map(p -> saved.getOrDefault(p, Integration.notConnected(p))).toList();
    }

    /** Phase 1 entry point: IDs only, server settings kept. */
    public Integration save(Provider provider, BrowserTags tags) {
        return save(provider, tags, null);
    }

    /**
     * Saves the provider's IDs ({@code tags} null keeps the saved ones) and, when {@code change} is given, its server
     * settings. With no IDs left the row, secret included, is removed and the card is NOT_CONNECTED again.
     */
    @CacheEvict(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
    public Integration save(Provider provider, BrowserTags tags, ServerChange change) {
        Integration before = find(provider);
        BrowserTags browser = tags != null ? tags : before.browser();
        Integration after;
        if (browser.isEmpty()) {
            if (tags == null) {
                throw ApiException.badRequest("server: save the " + idName(provider) + " first");
            }
            integrations.delete(provider);
            after = Integration.notConnected(provider);
        } else {
            ServerSettings server = change == null ? before.server() : server(provider, browser, before.server(), change);
            boolean newSecret = change != null && change.secret() != null;
            // Millisecond precision: what Mongo keeps, so the response matches the next GET.
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            if (tags != null) {
                integrations.save(new Integration(provider, browser, now));
            }
            if (change != null) {
                integrations.saveServer(provider, server, newSecret, now);
            }
            after = new Integration(provider, browser, server, newSecret ? null : before.lastError(),
                    before.lastSuccessAt(), now);
        }
        audit.record(AuditEvent.of(AuditAction.INTEGRATION_UPDATED).on(MarketingAudit.ENTITY, provider.key())
                .changing(Changes.diff(MarketingAudit.view(before), MarketingAudit.view(after), MarketingAudit.POLICY)));
        return after;
    }

    /** Removes the provider's row (and its secret); audited only when there was one. */
    @CacheEvict(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
    public Integration disconnect(Provider provider) {
        Integration before = find(provider);
        Integration after = Integration.notConnected(provider);
        if (integrations.delete(provider)) {
            audit.record(AuditEvent.of(AuditAction.INTEGRATION_DISCONNECTED).on(MarketingAudit.ENTITY, provider.key())
                    .changing(Changes.diff(MarketingAudit.view(before), MarketingAudit.view(after),
                            MarketingAudit.POLICY)));
        }
        return after;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
    public Tags current() {
        Map<Provider, BrowserTags> by = new EnumMap<>(Provider.class);
        all().forEach(i -> by.put(i.provider(), i.browser()));
        BrowserTags google = by.get(Provider.GOOGLE);
        BrowserTags meta = by.get(Provider.META);
        return new Tags(google.ga4MeasurementId(), google.adsConversionId(), google.adsPurchaseLabel(),
                google.gtmContainerId(), meta.pixelId());
    }

    Integration find(Provider provider) {
        return all().stream().filter(i -> i.provider() == provider).findFirst().orElseThrow();
    }

    static String idName(Provider provider) {
        return provider == Provider.META ? "Meta Pixel ID" : "GA4 measurement ID";
    }

    private ServerSettings server(Provider provider, BrowserTags browser, ServerSettings current, ServerChange change) {
        try {
            String secret = change.secret() == null ? null : ServerSettings.checkSecret(change.secret());
            boolean wantsServer = change.enabled() || secret != null;
            if (wantsServer && !secrets.available()) {
                throw ApiException.badRequest("server: not available on this platform yet");
            }
            if (wantsServer && Integration.serverId(provider, browser) == null) {
                throw ApiException.badRequest("server: save the " + idName(provider) + " first");
            }
            SealedSecret sealed = secret == null ? current.secret() : secrets.seal(secret,
                    SecretSealer.aad(TenantContext.require().id(), provider, ServerSettings.SECRET_FIELD));
            if (change.enabled() && sealed == null) {
                throw ApiException.badRequest("server.secret: is required to turn on server events");
            }
            return new ServerSettings(change.enabled(), provider == Provider.META ? change.testEventCode() : null,
                    sealed);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage()); // our own field checks only
        }
    }
}
```

`ServerEventsService.java` (grows in Task A12):

```java
package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import java.util.List;
import org.springframework.stereotype.Service;

/** The owner's view of server events: the delivery log (and, from Task A12, the test-event button). */
@Service
public class ServerEventsService {

    static final int CARD_DELIVERIES = 10;
    static final int PAGE_SIZE = 20;

    private final Outbox outbox;

    ServerEventsService(Outbox outbox) {
        this.outbox = outbox;
    }

    /** The newest rows for the card on first load. */
    public List<OutboxRow> recent(Provider provider) {
        return outbox.recent(provider, 1, CARD_DELIVERIES).rows();
    }

    public Outbox.Page page(Provider provider, int page) {
        return outbox.recent(provider, page, PAGE_SIZE);
    }
}
```

`marketing/web/DeliveryView.java`:

```java
package com.sixpack.marketing.web;

import com.sixpack.marketing.domain.OutboxRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One delivery in the owner's log. Ids and state only: never an email, hash, IP, user agent or click ID. */
record DeliveryView(String eventId, String orderNumber, String eventName, String status, int attempts,
                    Instant createdAt, Instant updatedAt, Instant nextAttemptAt, Map<String, String> error) {

    static DeliveryView from(OutboxRow r) {
        Map<String, String> error = null;
        if (r.error() != null) {
            error = new LinkedHashMap<>();
            error.put("code", r.error().name());
            error.put("message", r.error().message(r.provider()));
        }
        return new DeliveryView(r.eventId(), r.orderNumber(), r.eventName(), r.status().name(), r.attempts(),
                r.createdAt(), r.updatedAt(), r.status() == OutboxRow.Status.PENDING ? r.nextAttemptAt() : null, error);
    }
}
```

`MarketingAdminController.java` — the request gains `server`, the view gains four keys, and every card goes through `view(…)`:

```java
    record SaveRequest(BrowserFields browser, ServerFields server) {
    }

    record BrowserFields(String ga4MeasurementId, String adsConversionId, String adsPurchaseLabel,
                         String gtmContainerId, String pixelId) {
    }

    /** {@code secret} is write-only: null keeps the saved one. {@code testEventCode} is Meta's only. */
    record ServerFields(Boolean enabled, String secret, String testEventCode) {
    }

    /** One card. Only the provider's own fields appear in {@code browser}; unset ones are null. Never a secret. */
    record IntegrationView(String provider, String status, Map<String, String> browser, Instant updatedAt,
                           Map<String, Object> server, Map<String, Object> lastError, Instant lastSuccessAt,
                           List<DeliveryView> deliveries) {
        static IntegrationView from(Integration i, boolean available, List<DeliveryView> deliveries) {
            BrowserTags t = i.browser();
            Map<String, String> browser = new LinkedHashMap<>();
            if (i.provider() == Provider.GOOGLE) {
                browser.put("ga4MeasurementId", t.ga4MeasurementId());
                browser.put("adsConversionId", t.adsConversionId());
                browser.put("adsPurchaseLabel", t.adsPurchaseLabel());
                browser.put("gtmContainerId", t.gtmContainerId());
            } else {
                browser.put("pixelId", t.pixelId());
            }
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("enabled", i.server().enabled());
            server.put("hasSecret", i.server().hasSecret());
            server.put("testEventCode", i.server().testEventCode());
            server.put("available", available);
            Map<String, Object> lastError = null;
            if (i.lastError() != null) {
                lastError = new LinkedHashMap<>();
                lastError.put("at", i.lastError().at());
                lastError.put("code", i.lastError().code());
                lastError.put("message", i.lastError().message());
            }
            return new IntegrationView(i.provider().key(), i.status().name(), browser, i.updatedAt(), server,
                    lastError, i.lastSuccessAt(), deliveries);
        }
    }

    private final IntegrationService service;
    private final ServerEventsService events;
    private final TenantFeatures features;

    MarketingAdminController(IntegrationService service, ServerEventsService events, TenantFeatures features) {
        this.service = service;
        this.events = events;
        this.features = features;
    }

    @GetMapping
    Map<String, Object> all() {
        requireEnabled();
        return ApiResponse.of("integrations", service.all().stream().map(this::view).toList());
    }

    @PutMapping("/{provider}")
    Map<String, Object> save(@PathVariable String provider, @RequestBody SaveRequest request) {
        Provider p = provider(provider);
        BrowserFields b = request.browser();
        ServerFields s = request.server();
        if (b == null && s == null) {
            throw ApiException.badRequest("browser: is required");
        }
        BrowserTags tags = null;
        if (b != null) {
            try {
                tags = BrowserTags.of(p, b.ga4MeasurementId(), b.adsConversionId(), b.adsPurchaseLabel(),
                        b.gtmContainerId(), b.pixelId());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
        }
        IntegrationService.ServerChange change = s == null ? null
                : new IntegrationService.ServerChange(Boolean.TRUE.equals(s.enabled()), s.secret(), s.testEventCode());
        return ApiResponse.of("integration", view(service.save(p, tags, change)));
    }

    @DeleteMapping("/{provider}")
    Map<String, Object> disconnect(@PathVariable String provider) {
        return ApiResponse.of("integration", view(service.disconnect(provider(provider))));
    }

    private IntegrationView view(Integration i) {
        return IntegrationView.from(i, service.serverAvailable(),
                events.recent(i.provider()).stream().map(DeliveryView::from).toList());
    }
```

(Add imports `com.sixpack.marketing.application.ServerEventsService`, `java.util.List`.)

`TenantExport.java` — skip the collection and the field, and list them:

```java
    static final Set<String> SKIPPED_COLLECTIONS = Set.of("password_reset_tokens", "payment_intents", "marketing_outbox");
    private static final Map<String, Set<String>> SKIPPED_FIELDS = Map.of("users", Set.of("password"),
            "marketing_integrations", Set.of("secrets"));
…
            manifest.put("omitted", List.of("users.password", "password_reset_tokens", "payment_intents",
                    "marketing_integrations.secrets", "marketing_outbox"));
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='MarketingServerSettingsIntegrationTest,MarketingAdminIntegrationTest,MarketingCrossTenantIntegrationTest,MarketingTagsConfigIntegrationTest,TenantOffboardingIntegrationTest,ModularityTests'`
Expected: PASS (Phase 1 tests unchanged apart from the field-name list).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing backend/src/main/java/com/sixpack/tenant/application/TenantExport.java \
        backend/src/test/java/com/sixpack/it/MarketingServerSettingsIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/MarketingAdminIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/TenantOffboardingIntegrationTest.java
git commit -m "feat(marketing): owners save server-event secrets; cards show status, errors and deliveries

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A6 (phase plan 3.2): attribution captured at checkout, kept on the order, erased with the account

**Files:**
- Create: `backend/src/main/java/com/sixpack/order/OrderAttribution.java`
- Modify: `order/domain/OrderDetails.java`, `order/domain/Order.java`, `order/domain/PaymentIntent.java`, `order/infra/mongo/OrderDocument.java`, `order/infra/mongo/PaymentIntentDocument.java`, `order/infra/mongo/MongoOrderRepository.java`, `order/infra/mongo/MongoPaymentIntentRepository.java`, `order/application/CheckoutService.java`, `order/web/OrderController.java`
- Test: create `order/OrderAttributionTest.java`, `it/CheckoutAttributionIntegrationTest.java`; modify `it/OrderErasureIntegrationTest.java`

**Interfaces:**
- Produces: `public record OrderAttribution(utmSource, utmMedium, utmCampaign, utmContent, utmTerm, gclid, fbclid, fbp, fbc, gaClientId, landingPath, userAgent, sourceUrl, Consent consent)` with `record Consent(boolean analytics, boolean marketing, String version, Instant at)`, `record Raw(…11 strings, Boolean analytics, Boolean marketing, String version)` and `static OrderAttribution from(Raw, String userAgent, String sourceUrl, Instant now)` (null without a valid consent snapshot); `OrderDetails.attribution()`; `CheckoutService.checkout(userId, lines, coupon, attribution)`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/sixpack/order/OrderAttributionTest.java`:

```java
package com.sixpack.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderAttributionTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final String UA = "Mozilla/5.0 test";
    private static final String URL = "https://shop.acme.test/shipping";

    private static OrderAttribution.Raw raw(Boolean analytics, Boolean marketing, String version) {
        return new OrderAttribution.Raw("google", "cpc", "diwali", "ad-1", "lamps", "Cj0KCQ-abc", "IwAR-xyz",
                "fb.1.1730000000000.1098115397", "fb.1.1730000000000.IwAR-xyz", "1234567890.1730000000",
                "/product/lamp", analytics, marketing, version);
    }

    @Test
    void withoutAConsentSnapshotNothingIsKept() {
        assertThat(OrderAttribution.from(null, UA, URL, NOW)).isNull();
        assertThat(OrderAttribution.from(raw(null, true, "2026-10-31"), UA, URL, NOW)).isNull();
        assertThat(OrderAttribution.from(raw(true, true, null), UA, URL, NOW)).isNull();
        assertThat(OrderAttribution.from(raw(true, true, "<script>"), UA, URL, NOW)).isNull();
    }

    @Test
    void marketingConsentKeepsCampaignDataClickIdsAndTheBrowserButNotTheGaId() {
        OrderAttribution a = OrderAttribution.from(raw(false, true, "2026-10-31"), UA, URL, NOW);
        assertThat(a.utmSource()).isEqualTo("google");
        assertThat(a.utmTerm()).isEqualTo("lamps");
        assertThat(a.gclid()).isEqualTo("Cj0KCQ-abc");
        assertThat(a.fbp()).isEqualTo("fb.1.1730000000000.1098115397");
        assertThat(a.fbc()).isEqualTo("fb.1.1730000000000.IwAR-xyz");
        assertThat(a.userAgent()).isEqualTo(UA);
        assertThat(a.sourceUrl()).isEqualTo(URL);
        assertThat(a.landingPath()).isEqualTo("/product/lamp");
        assertThat(a.gaClientId()).isNull();
        assertThat(a.consent()).isEqualTo(new OrderAttribution.Consent(false, true, "2026-10-31", NOW));
    }

    @Test
    void analyticsOnlyKeepsTheGaIdAndLandingPathOnly() {
        OrderAttribution a = OrderAttribution.from(raw(true, false, "2026-10-31"), UA, URL, NOW);
        assertThat(a.gaClientId()).isEqualTo("1234567890.1730000000");
        assertThat(a.landingPath()).isEqualTo("/product/lamp");
        assertThat(a.utmSource()).isNull();
        assertThat(a.gclid()).isNull();
        assertThat(a.fbp()).isNull();
        assertThat(a.userAgent()).isNull();
        assertThat(a.sourceUrl()).isNull();
    }

    @Test
    void declinedEverythingKeepsOnlyTheChoice() {
        OrderAttribution a = OrderAttribution.from(raw(false, false, "2026-10-31"), UA, URL, NOW);
        assertThat(a.landingPath()).isNull();
        assertThat(a.utmSource()).isNull();
        assertThat(a.consent().marketing()).isFalse();
    }

    @Test
    void junkIsDroppedOrCutNeverRejected() {
        OrderAttribution a = OrderAttribution.from(new OrderAttribution.Raw("x".repeat(500), " tab\tand\nnewline ",
                "  ", null, "दिवाली सेल", "<script>", "IwAR xyz", "fb.1.abc", "javascript:alert(1)", "GA1.1.1.2",
                "https://evil.example/path", true, true, "2026-10-31"), "u".repeat(900), URL, NOW);
        assertThat(a.utmSource()).hasSize(150);
        assertThat(a.utmMedium()).isEqualTo("tab and newline");
        assertThat(a.utmCampaign()).isNull();
        assertThat(a.utmTerm()).isEqualTo("दिवाली सेल"); // non-ASCII campaign names are fine
        assertThat(a.gclid()).isNull();
        assertThat(a.fbclid()).isNull();
        assertThat(a.fbp()).isNull();
        assertThat(a.fbc()).isNull();
        assertThat(a.gaClientId()).isNull();
        assertThat(a.landingPath()).isNull();
        assertThat(a.userAgent()).hasSize(512);
        assertThat(OrderAttribution.from(new OrderAttribution.Raw(null, null, null, null, null, null, null, null, null,
                null, "//evil.example", true, true, "2026-10-31"), UA, URL, NOW).landingPath()).isNull();
    }
}
```

`backend/src/test/java/com/sixpack/it/CheckoutAttributionIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** POST /order/checkout with an attribution snapshot (marketing spec §5.5). */
class CheckoutAttributionIntegrationTest extends IntegrationTest {

    private String orderWith(String token, String product, Map<String, Object> attribution) throws Exception {
        Map<String, Object> checkout = new HashMap<>(Map.of("items", List.of(Map.of("productId", product, "quantity", 1))));
        checkout.put("attribution", attribution);
        String intent = body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                        .header("User-Agent", "Mozilla/5.0 test").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(checkout)))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        return body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping()))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    private Document stored(String orderId) {
        return mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
    }

    static Map<String, Object> attribution(boolean analytics, boolean marketing) {
        Map<String, Object> a = new HashMap<>(Map.of("utmSource", "google", "utmCampaign", "diwali",
                "gclid", "Cj0KCQ-abc", "fbp", "fb.1.1730000000000.1098115397", "gaClientId", "1234567890.1730000000",
                "landingPath", "/product/lamp"));
        a.put("consent", Map.of("analytics", analytics, "marketing", marketing, "version", "2026-10-31"));
        return a;
    }

    @Test
    void theAttributionTravelsFromCheckoutToTheOrder() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        String orderId = orderWith(register("a@test.dev"), product, attribution(true, true));

        Document a = stored(orderId).get("attribution", Document.class);
        assertThat(a.getString("utmSource")).isEqualTo("google");
        assertThat(a.getString("gclid")).isEqualTo("Cj0KCQ-abc");
        assertThat(a.getString("gaClientId")).isEqualTo("1234567890.1730000000");
        assertThat(a.getString("userAgent")).isEqualTo("Mozilla/5.0 test");
        assertThat(a.getString("sourceUrl")).isEqualTo("https://localhost/shipping");
        Document consent = a.get("consent", Document.class);
        assertThat(consent.getBoolean("marketing")).isTrue();
        assertThat(consent.getString("version")).isEqualTo("2026-10-31");
        assertThat(consent.getDate("at")).isNotNull();
        // Never shown on the order itself.
        String view = mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(view).doesNotContain("attribution", "gclid");
    }

    @Test
    void withoutAttributionCheckoutIsUnchanged() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        String orderId = placeOrder(register("b@test.dev"), product);
        assertThat(stored(orderId)).doesNotContainKey("attribution");
    }

    @Test
    void junkAttributionNeverFailsCheckout() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        Map<String, Object> junk = attribution(true, true);
        junk.put("utmSource", "x".repeat(1000));
        junk.put("gclid", "<script>alert(1)</script>");
        junk.put("landingPath", "https://evil.example");
        String orderId = orderWith(register("c@test.dev"), product, junk);

        Document a = stored(orderId).get("attribution", Document.class);
        assertThat(a.getString("utmSource")).hasSize(150);
        assertThat(a).doesNotContainKeys("gclid", "landingPath");
    }

    @Test
    void aDeclinedChoiceKeepsNoCampaignData() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        Document a = stored(orderWith(register("d@test.dev"), product, attribution(false, false)))
                .get("attribution", Document.class);
        assertThat(a).doesNotContainKeys("utmSource", "gclid", "fbp", "gaClientId", "landingPath", "userAgent");
        assertThat(a.get("consent", Document.class).getBoolean("marketing")).isFalse();
    }
}
```

In `OrderErasureIntegrationTest` add:

```java
    @Test
    void attributionSurvivesStatusChangesAndIsErasedWithTheAccount() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Lamp", 500, 5, "home");
        String user = register("attr@test.dev");
        Map<String, Object> checkout = new HashMap<>(Map.of("items", List.of(Map.of("productId", product, "quantity", 1))));
        checkout.put("attribution", CheckoutAttributionIntegrationTest.attribution(true, true));
        String intent = body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(checkout)))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        String orderId = body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "providerOrderId", intent, "paymentId", "fake_pay_1", "signature", "fake",
                                "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping()))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
        Document byId = new Document("_id", new ObjectId(orderId));

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());
        assertThat(mongo.getCollection("orders").find(byId).first()).containsKey("attribution");

        mvc.perform(delete("/api/v1/user/me").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        assertThat(mongo.getCollection("orders").find(byId).first()).doesNotContainKey("attribution");
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='OrderAttributionTest,CheckoutAttributionIntegrationTest,OrderErasureIntegrationTest'`
Expected: FAIL — compilation error `cannot find symbol … class OrderAttribution` (the ITs then fail on the missing `attribution` field).

- [ ] **Step 3: Implement**

`backend/src/main/java/com/sixpack/order/OrderAttribution.java`:

```java
package com.sixpack.order;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Where a paid order came from and what the shopper allowed (marketing spec §5.5). Public API of {@code order}: the
 * {@code marketing} module reads it through {@link PaidOrders}. Built only by {@link #from}, which enforces consent
 * server-side: campaign data, click IDs and the browser only with marketing consent; the GA client id only with
 * analytics consent.
 */
public record OrderAttribution(String utmSource, String utmMedium, String utmCampaign, String utmContent,
                               String utmTerm, String gclid, String fbclid, String fbp, String fbc, String gaClientId,
                               String landingPath, String userAgent, String sourceUrl, Consent consent) {

    /** The shopper's cookie choice at checkout; {@code at} is server time. */
    public record Consent(boolean analytics, boolean marketing, String version, Instant at) {
    }

    /** What the browser sent, unchecked. */
    public record Raw(String utmSource, String utmMedium, String utmCampaign, String utmContent, String utmTerm,
                      String gclid, String fbclid, String fbp, String fbc, String gaClientId, String landingPath,
                      Boolean analytics, Boolean marketing, String version) {
    }

    private static final Pattern CLICK_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,512}$");
    private static final Pattern FBP = Pattern.compile("^fb\\.\\d\\.\\d{1,15}\\.\\d{1,25}$");
    private static final Pattern FBC = Pattern.compile("^fb\\.\\d\\.\\d{1,15}\\.[A-Za-z0-9_.-]{1,512}$");
    private static final Pattern GA_CLIENT = Pattern.compile("^\\d{1,20}\\.\\d{1,20}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9.-]{1,20}$");

    /**
     * Never throws: a value that fails its check is dropped, so marketing data can never fail a checkout.
     *
     * @return null without a usable consent snapshot
     */
    public static OrderAttribution from(Raw raw, String userAgent, String sourceUrl, Instant now) {
        if (raw == null || raw.analytics() == null || raw.marketing() == null) {
            return null;
        }
        String version = match(raw.version(), VERSION);
        if (version == null) {
            return null;
        }
        boolean m = raw.marketing();
        boolean a = raw.analytics();
        return new OrderAttribution(
                m ? text(raw.utmSource(), 150) : null, m ? text(raw.utmMedium(), 150) : null,
                m ? text(raw.utmCampaign(), 150) : null, m ? text(raw.utmContent(), 150) : null,
                m ? text(raw.utmTerm(), 150) : null,
                m ? match(raw.gclid(), CLICK_ID) : null, m ? match(raw.fbclid(), CLICK_ID) : null,
                m ? match(raw.fbp(), FBP) : null, m ? match(raw.fbc(), FBC) : null,
                a ? match(raw.gaClientId(), GA_CLIENT) : null,
                a || m ? path(raw.landingPath()) : null,
                m ? text(userAgent, 512) : null, m ? sourceUrl : null,
                new Consent(a, m, version, now));
    }

    private static String match(String value, Pattern pattern) {
        String v = value == null ? null : value.strip();
        return v != null && pattern.matcher(v).matches() ? v : null;
    }

    /** Control characters become spaces, then strip; blank is absent; cut to {@code max}. */
    private static String text(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").strip();
        return v.isEmpty() ? null : v.length() > max ? v.substring(0, max) : v;
    }

    /** A path on this store only: one leading slash, no scheme or host. */
    private static String path(String value) {
        String v = text(value, 512);
        return v != null && v.startsWith("/") && !v.startsWith("//") ? v : null;
    }
}
```

`OrderDetails.java` — add the component and a compatibility constructor:

```java
public record OrderDetails(
        String orderNumber,
        ShippingInfo billingInfo,
        Customer customer,
        List<StatusEntry> statusHistory,
        String couponCode,
        String notes,
        OrderAttribution attribution) {

    public static final OrderDetails EMPTY = new OrderDetails(null, null, null, null, null, null);

    public OrderDetails {
        statusHistory = statusHistory == null ? List.of() : List.copyOf(statusHistory);
    }

    /** Pre-attribution shape, kept so existing callers compile unchanged. */
    public OrderDetails(String orderNumber, ShippingInfo billingInfo, Customer customer,
                        List<StatusEntry> statusHistory, String couponCode, String notes) {
        this(orderNumber, billingInfo, customer, statusHistory, couponCode, notes, null);
    }
}
```

(import `com.sixpack.order.OrderAttribution`.)

`Order.withStatus` — keep the attribution:

```java
        return new Order(id, userId, shippingInfo, items, quote, next, payment, termsVersion, createdAt,
                new OrderDetails(details.orderNumber(), details.billingInfo(), details.customer(), history,
                        details.couponCode(), details.notes(), details.attribution()));
```

`PaymentIntent.java`:

```java
/** Server-side record of a priced cart awaiting payment (ADR-009). {@code attribution} may be null. */
public record PaymentIntent(String id, String userId, String provider, String providerOrderId, List<OrderItem> items,
                            String coupon, Quote quote, String currency, OrderAttribution attribution) {

    /** Pre-attribution shape, kept so existing callers compile unchanged. */
    public PaymentIntent(String id, String userId, String provider, String providerOrderId, List<OrderItem> items,
                         String coupon, Quote quote, String currency) {
        this(id, userId, provider, providerOrderId, items, coupon, quote, currency, null);
    }
}
```

`OrderDocument.java` — field `AttributionDoc attribution;` and:

```java
    static class AttributionDoc {
        String utmSource;
        String utmMedium;
        String utmCampaign;
        String utmContent;
        String utmTerm;
        String gclid;
        String fbclid;
        String fbp;
        String fbc;
        String gaClientId;
        String landingPath;
        String userAgent;
        String sourceUrl;
        ConsentDoc consent;
    }

    static class ConsentDoc {
        Boolean analytics;
        Boolean marketing;
        String version;
        Instant at;
    }
```

`PaymentIntentDocument.java` — field `OrderDocument.AttributionDoc attribution;`.

`MongoOrderRepository.java`:
- in `save`, after `doc.notes = d.notes();`:
  ```java
        // Written once at checkout; erasure removes it with an atomic unset, never through save.
        if (d.attribution() != null) {
            doc.attribution = toDoc(d.attribution());
        }
  ```
- in `toDomain(OrderDocument d)`, pass `toDomain(d.attribution)` as the seventh `OrderDetails` argument.
- in `anonymiseShippingForUser`, add `.unset("attribution")` to the update.
- add the mappers (package-visible statics, reused by the intent repository):

```java
    static OrderDocument.AttributionDoc toDoc(OrderAttribution a) {
        if (a == null) {
            return null;
        }
        OrderDocument.AttributionDoc d = new OrderDocument.AttributionDoc();
        d.utmSource = a.utmSource();
        d.utmMedium = a.utmMedium();
        d.utmCampaign = a.utmCampaign();
        d.utmContent = a.utmContent();
        d.utmTerm = a.utmTerm();
        d.gclid = a.gclid();
        d.fbclid = a.fbclid();
        d.fbp = a.fbp();
        d.fbc = a.fbc();
        d.gaClientId = a.gaClientId();
        d.landingPath = a.landingPath();
        d.userAgent = a.userAgent();
        d.sourceUrl = a.sourceUrl();
        d.consent = new OrderDocument.ConsentDoc();
        d.consent.analytics = a.consent().analytics();
        d.consent.marketing = a.consent().marketing();
        d.consent.version = a.consent().version();
        d.consent.at = a.consent().at();
        return d;
    }

    static OrderAttribution toDomain(OrderDocument.AttributionDoc d) {
        if (d == null || d.consent == null) {
            return null;
        }
        return new OrderAttribution(d.utmSource, d.utmMedium, d.utmCampaign, d.utmContent, d.utmTerm, d.gclid,
                d.fbclid, d.fbp, d.fbc, d.gaClientId, d.landingPath, d.userAgent, d.sourceUrl,
                new OrderAttribution.Consent(Boolean.TRUE.equals(d.consent.analytics),
                        Boolean.TRUE.equals(d.consent.marketing), d.consent.version, d.consent.at));
    }
```

`MongoPaymentIntentRepository.java` — `d.attribution = MongoOrderRepository.toDoc(intent.attribution());` in `save`, and the 9-argument constructor with `MongoOrderRepository.toDomain(doc.attribution)` in `findByProviderOrderId`.

`CheckoutService.java`:

```java
    public CheckoutResult checkout(String userId, List<CartLine> lines, String coupon) {
        return checkout(userId, lines, coupon, null);
    }

    /** @param attribution already sanitised ({@link OrderAttribution#from}); null when the browser sent none */
    public CheckoutResult checkout(String userId, List<CartLine> lines, String coupon, OrderAttribution attribution) {
        …unchanged up to the intent…
        intents.save(new PaymentIntent(null, userId, gateway.provider(), providerOrder.id(), priced.items(),
                priced.coupon(), quote, providerOrder.currency(), attribution));
        …
    }
```

and in `confirm`, the `OrderDetails` gets `intent.attribution()` as its seventh argument (after `details.notes()`).

`OrderController.java`:

```java
    /** First touch and the shopper's cookie choice (spec §5.5). Not bean-validated on purpose: bad values are dropped. */
    record AttributionRequest(String utmSource, String utmMedium, String utmCampaign, String utmContent,
                              String utmTerm, String gclid, String fbclid, String fbp, String fbc, String gaClientId,
                              String landingPath, ConsentRequest consent) {
    }

    record ConsentRequest(Boolean analytics, Boolean marketing, String version) {
    }

    record CheckoutRequest(@NotEmpty @Size(max = 50) List<@Valid CartLineRequest> items,
                           @Size(max = 32) String coupon, AttributionRequest attribution) {
    }
…
    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> checkout(@Valid @RequestBody CheckoutRequest request, @AuthenticationPrincipal Jwt jwt,
                                 HttpServletRequest http) {
        List<CartLine> lines = request.items().stream()
                .map(i -> new CartLine(i.productId(), i.variantId(), i.quantity())).toList();
        CheckoutResult r = checkout.checkout(AuthUser.from(jwt).id(), lines, request.coupon(),
                attribution(request.attribution(), http));
        …unchanged…
    }

    private static OrderAttribution attribution(AttributionRequest a, HttpServletRequest http) {
        if (a == null) {
            return null;
        }
        ConsentRequest c = a.consent();
        return OrderAttribution.from(new OrderAttribution.Raw(a.utmSource(), a.utmMedium(), a.utmCampaign(),
                        a.utmContent(), a.utmTerm(), a.gclid(), a.fbclid(), a.fbp(), a.fbc(), a.gaClientId(),
                        a.landingPath(), c == null ? null : c.analytics(), c == null ? null : c.marketing(),
                        c == null ? null : c.version()),
                http.getHeader("User-Agent"),
                // The host already matched one of the store's domains (TenantResolutionFilter): not shopper input.
                "https://" + http.getServerName() + "/shipping", Instant.now());
    }
```

(imports `com.sixpack.order.OrderAttribution`, `jakarta.servlet.http.HttpServletRequest`, `java.time.Instant`.)

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='OrderAttributionTest,CheckoutAttributionIntegrationTest,OrderErasureIntegrationTest,OrderDomainTest,OrderDetailsPersistenceIntegrationTest,OrderCheckoutDetailsIntegrationTest,ModularityTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/order backend/src/test/java/com/sixpack/order/OrderAttributionTest.java \
        backend/src/test/java/com/sixpack/it/CheckoutAttributionIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/OrderErasureIntegrationTest.java
git commit -m "feat(order): keep a consent-gated attribution snapshot on each order

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A7: `PaidOrders` — the read port `marketing` uses

**Files:**
- Create: `backend/src/main/java/com/sixpack/order/PaidOrders.java`, `backend/src/main/java/com/sixpack/order/application/OrderPaidOrders.java`
- Modify: `order/domain/OrderRepository.java`, `order/infra/mongo/MongoOrderRepository.java`
- Test: `backend/src/test/java/com/sixpack/it/CheckoutAttributionIntegrationTest.java`

- [ ] **Step 1: Write the failing test** — add to `CheckoutAttributionIntegrationTest` (`@Autowired com.sixpack.order.PaidOrders paidOrders;`, imports `java.time.Instant`):

```java
    @Test
    void paidOrdersShowsAPaidOrderAndListsOnlyConsentedOnes() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        String withConsent = orderWith(register("p@test.dev"), product, attribution(true, false));
        String declined = orderWith(register("q@test.dev"), product, attribution(false, false));
        String without = placeOrder(register("r@test.dev"), product);

        var paid = paidOrders.find(withConsent).orElseThrow();
        assertThat(paid.orderNumber()).startsWith("ORD-");
        assertThat(paid.total()).isEqualTo(790.0);
        assertThat(paid.tax()).isEqualTo(90.0);
        assertThat(paid.shipping()).isEqualTo(200.0);
        assertThat(paid.currency()).isEqualTo("INR");
        assertThat(paid.email()).isEqualTo("p@test.dev");
        assertThat(paid.lines()).singleElement().satisfies(l -> {
            assertThat(l.productId()).isEqualTo(product);
            assertThat(l.quantity()).isEqualTo(1);
        });
        assertThat(paid.attribution().consent().analytics()).isTrue();
        assertThat(paid.placedAt()).isNotNull();
        assertThat(paidOrders.find("6720f0c2a1b2c3d4e5f60718")).isEmpty();

        Instant now = Instant.now();
        assertThat(paidOrders.consentedBetween(now.minusSeconds(600), now.plusSeconds(60), 10))
                .containsExactly(withConsent).doesNotContain(declined, without);
        assertThat(paidOrders.consentedBetween(now.plusSeconds(60), now.plusSeconds(120), 10)).isEmpty();
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=CheckoutAttributionIntegrationTest`
Expected: FAIL — compilation error `cannot find symbol … class PaidOrders`.

- [ ] **Step 3: Implement**

`backend/src/main/java/com/sixpack/order/PaidOrders.java`:

```java
package com.sixpack.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only view of the current store's paid orders, for modules that report sales elsewhere (marketing). */
public interface PaidOrders {

    /** The order as it is now (after any erasure), or empty when it is gone or was never paid. */
    Optional<PaidOrder> find(String orderId);

    /** Ids of paid orders placed in [from, to) whose shopper allowed analytics or marketing, oldest first. */
    List<String> consentedBetween(Instant from, Instant to, int limit);

    /** {@code email}/{@code phone} are the buyer snapshot (null once erased); {@code attribution} may be null. */
    record PaidOrder(String id, String orderNumber, String userId, Instant placedAt, double total, double tax,
                     double shipping, String currency, String coupon, List<Line> lines, String email, String phone,
                     OrderAttribution attribution) {
    }

    record Line(String productId, String name, String variant, double price, int quantity) {
    }
}
```

`OrderRepository.java` — add:

```java
    /** Ids of paid orders created in [from, to) with an analytics or marketing consent, oldest first. */
    List<String> findConsentedIdsBetween(Instant from, Instant to, int limit);
```

`MongoOrderRepository.java` — add:

```java
    @Override
    public List<String> findConsentedIdsBetween(Instant from, Instant to, int limit) {
        Query query = Query.query(Criteria.where("createdAt").gte(from).lt(to).and("payment.status").ne(null)
                        .orOperator(Criteria.where("attribution.consent.analytics").is(true),
                                Criteria.where("attribution.consent.marketing").is(true)))
                .with(Sort.by("createdAt")).limit(limit);
        query.fields().include("id");
        return mongo.find(query, OrderDocument.class).stream().map(d -> d.id).toList();
    }
```

`backend/src/main/java/com/sixpack/order/application/OrderPaidOrders.java`:

```java
package com.sixpack.order.application;

import com.sixpack.order.PaidOrders;
import com.sixpack.order.domain.Customer;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class OrderPaidOrders implements PaidOrders {

    private final OrderRepository orders;

    OrderPaidOrders(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Optional<PaidOrder> find(String orderId) {
        return orders.findById(orderId).filter(o -> o.payment() != null && o.payment().status() != null)
                .map(OrderPaidOrders::paid);
    }

    @Override
    public List<String> consentedBetween(Instant from, Instant to, int limit) {
        return orders.findConsentedIdsBetween(from, to, limit);
    }

    private static PaidOrder paid(Order o) {
        Customer c = o.details().customer();
        return new PaidOrder(o.id(), o.details().orderNumber(), o.userId(), o.createdAt(), o.quote().total(),
                o.quote().tax(), o.quote().shippingCharges(), o.payment().currency(), o.details().couponCode(),
                o.items().stream().map(i -> new PaidOrders.Line(i.productId(), i.name(), i.variantLabel(), i.price(),
                        i.quantity())).toList(),
                c == null ? null : c.email(), c == null ? null : c.phone(), o.details().attribution());
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest='CheckoutAttributionIntegrationTest,ModularityTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/order backend/src/test/java/com/sixpack/it/CheckoutAttributionIntegrationTest.java
git commit -m "feat(order): read port for paid orders with their attribution

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A8: PII hashing and the conversion built from an order

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/application/PiiHasher.java`, `Conversions.java`
- Test: `backend/src/test/java/com/sixpack/marketing/application/PiiHasherTest.java`, `ConversionsTest.java`

- [ ] **Step 1: Write the failing tests**

`PiiHasherTest.java`:

```java
package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Normalisation and SHA-256 per Meta's customer-information rules; vectors from Meta's docs and sha256sum. */
class PiiHasherTest {

    @Test
    void anEmailIsTrimmedLowerCasedThenHashed() {
        assertThat(PiiHasher.email("  Mary@Example.com ")).isEqualTo(
                "f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79");
        assertThat(PiiHasher.email(" ")).isNull();
        assertThat(PiiHasher.email(null)).isNull();
    }

    @Test
    void aPhoneBecomesDigitsWithItsCountryCode() {
        assertThat(PiiHasher.phone("+1 (555) 987-6543", "91")).isEqualTo(
                "1ef970831d7963307784fa8688e8fce101a15685d62aa765fed23f3a2c576a4e");
        String india = "92b5072176e723878b5e06ff3ca61898e4eb74e8c46642a0f2db800b17364ab0"; // sha256("919876543210")
        assertThat(PiiHasher.phone("98765 43210", "91")).isEqualTo(india);
        assertThat(PiiHasher.phone("098765-43210", "91")).isEqualTo(india);
        assertThat(PiiHasher.phone("+91 98765 43210", "91")).isEqualTo(india);
        assertThat(PiiHasher.phone("123", "91")).isNull();
        assertThat(PiiHasher.phone(null, "91")).isNull();
    }
}
```

`ConversionsTest.java`:

```java
package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.order.OrderAttribution;
import com.sixpack.order.PaidOrders.Line;
import com.sixpack.order.PaidOrders.PaidOrder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversionsTest {

    private static final Instant AT = Instant.parse("2026-10-31T10:00:00Z");

    private static PaidOrder order(String consentVersion, boolean marketing, String currency) {
        OrderAttribution a = new OrderAttribution(null, null, null, null, null, null, null,
                "fb.1.1730000000000.1098115397", null, "1234567890.1730000000", "/", "Mozilla/5.0 test",
                "https://shop.acme.test/shipping", new OrderAttribution.Consent(true, marketing, consentVersion, AT));
        return new PaidOrder("o1", "ORD-1", "u1", AT, 790, 90, 200, currency, "DIWALI",
                List.of(new Line("p1", "Lamp", "Red", 500, 1)), "Mary@Example.com", "98765 43210", a);
    }

    @Test
    void metaGetsHashedContactDetailsWhenTheShopperWasToldAboutIt() {
        Conversion c = Conversions.of(order("2026-10-31", true, "INR"), Provider.META, "USD", "2026-10-31", "91");
        assertThat(c.eventId()).isEqualTo("o1");
        assertThat(c.at()).isEqualTo(AT);
        assertThat(c.currency()).isEqualTo("INR");
        assertThat(c.value()).isEqualTo(790);
        assertThat(c.items()).containsExactly(new Conversion.Item("p1", "Lamp", "Red", 500, 1));
        assertThat(c.identity().emailSha256())
                .isEqualTo("f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79");
        assertThat(c.identity().phoneSha256())
                .isEqualTo("92b5072176e723878b5e06ff3ca61898e4eb74e8c46642a0f2db800b17364ab0");
        assertThat(c.identity().externalIdSha256()).isEqualTo(PiiHasher.sha256("u1"));
        assertThat(c.identity().fbp()).isEqualTo("fb.1.1730000000000.1098115397");
        assertThat(c.identity().adConsent()).isTrue();
    }

    @Test
    void anOlderConsentSendsNoContactDetails() {
        Conversion c = Conversions.of(order("2026-10-30", true, "INR"), Provider.META, "INR", "2026-10-31", "91");
        assertThat(c.identity().emailSha256()).isNull();
        assertThat(c.identity().phoneSha256()).isNull();
        assertThat(c.identity().externalIdSha256()).isNull();
        assertThat(c.identity().fbp()).isEqualTo("fb.1.1730000000000.1098115397"); // already covered by the old text
    }

    @Test
    void googleNeverGetsContactDetailsButGetsItsClientId() {
        Conversion c = Conversions.of(order("2026-10-31", true, "INR"), Provider.GOOGLE, "INR", "2026-10-31", "91");
        assertThat(c.identity().emailSha256()).isNull();
        assertThat(c.identity().phoneSha256()).isNull();
        assertThat(c.identity().gaClientId()).isEqualTo("1234567890.1730000000");
    }

    @Test
    void theStoreCurrencyIsTheFallback() {
        assertThat(Conversions.of(order("2026-10-31", false, null), Provider.GOOGLE, "AED", "2026-10-31", "91")
                .currency()).isEqualTo("AED");
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='PiiHasherTest,ConversionsTest'`
Expected: FAIL — compilation errors (`PiiHasher`, `Conversions` not found).

- [ ] **Step 3: Implement**

`PiiHasher.java`:

```java
package com.sixpack.marketing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** SHA-256 (lower-case hex) of normalised email and phone, as Meta's customer-information rules ask. */
final class PiiHasher {

    private PiiHasher() {
    }

    static String email(String raw) {
        return raw == null || raw.isBlank() ? null : sha256(raw.strip().toLowerCase(Locale.ROOT));
    }

    /** Digits only, no leading zeros, with the country code; a 10-digit number without one gets {@code callingCode}. */
    static String phone(String raw, String callingCode) {
        if (raw == null) {
            return null;
        }
        boolean international = raw.strip().startsWith("+");
        String digits = raw.replaceAll("\\D", "").replaceFirst("^0+", "");
        if (!international && digits.length() == 10) {
            digits = callingCode + digits;
        }
        return digits.length() < 8 ? null : sha256(digits);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM has SHA-256
        }
    }
}
```

`Conversions.java`:

```java
package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.order.OrderAttribution;
import com.sixpack.order.PaidOrders.PaidOrder;

/** What a provider is sent for one paid order, built from the order as it is now (so erasure and consent apply). */
final class Conversions {

    private Conversions() {
    }

    /** Callers check consent first ({@link ConversionRecorder#allowed}); {@code order.attribution()} is not null. */
    static Conversion of(PaidOrder order, Provider provider, String storeCurrency, String piiConsentVersion,
                         String callingCode) {
        OrderAttribution a = order.attribution();
        boolean marketing = a.consent().marketing();
        // Hashed contact details go to Meta only, and only to shoppers whose cookie notice said so.
        boolean pii = provider == Provider.META && marketing && a.consent().version() != null
                && a.consent().version().compareTo(piiConsentVersion) >= 0;
        Conversion.Identity identity = new Conversion.Identity(
                pii ? PiiHasher.email(order.email()) : null,
                pii ? PiiHasher.phone(order.phone(), callingCode) : null,
                pii && order.userId() != null ? PiiHasher.sha256(order.userId()) : null,
                a.fbp(), a.fbc(), a.gaClientId(), a.userAgent(), a.sourceUrl(), marketing);
        return new Conversion(order.id(), order.placedAt(),
                order.currency() != null ? order.currency() : storeCurrency, order.total(), order.tax(),
                order.shipping(), order.coupon(), order.lines().stream().map(l -> new Conversion.Item(l.productId(),
                        l.name(), l.variant(), l.price(), l.quantity())).toList(), identity);
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='PiiHasherTest,ConversionsTest'`
Expected: PASS. (`ConversionRecorder#allowed` in the Javadoc is created in A10; a dangling `{@link}` does not break compilation.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/application/PiiHasher.java \
        backend/src/main/java/com/sixpack/marketing/application/Conversions.java \
        backend/src/test/java/com/sixpack/marketing/application/PiiHasherTest.java \
        backend/src/test/java/com/sixpack/marketing/application/ConversionsTest.java
git commit -m "feat(marketing): hash contact details only where consent and notice allow

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A9: Adapters — Meta Conversions API and GA4 Measurement Protocol

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/infra/http/ProviderHttp.java`, `infra/meta/MetaConversionsApi.java`, `infra/google/Ga4MeasurementProtocol.java`
- Test: `backend/src/test/java/com/sixpack/marketing/infra/meta/MetaConversionsApiTest.java`, `infra/google/Ga4MeasurementProtocolTest.java`

- [ ] **Step 1: Write the failing tests**

`MetaConversionsApiTest.java`:

```java
package com.sixpack.marketing.infra.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.MarketingDestination.Credentials;
import com.sixpack.marketing.domain.MarketingDestination.TestVerdict;
import com.sixpack.marketing.domain.Provider;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class MetaConversionsApiTest {

    private static final String TOKEN = "fake-meta-token-aaaaaaaa";
    private static final String EVENTS = "https://graph.test/v25.0/123456789012345/events";
    private final Credentials creds = new Credentials("123456789012345", TOKEN);

    private MockRestServiceServer server;
    private MetaConversionsApi meta;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://graph.test");
        server = MockRestServiceServer.bindTo(builder).build();
        meta = new MetaConversionsApi(builder.build(), "v25.0");
    }

    static Conversion purchase() {
        return new Conversion("6720f0c2a1b2c3d4e5f60718", Instant.parse("2026-10-31T10:00:00Z"), "INR", 790.0, 90.0,
                200.0, null, List.of(new Conversion.Item("p1", "Lamp", "Red", 500.0, 1)),
                new Conversion.Identity("f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79", null, null,
                        "fb.1.1730000000000.1098115397", null, null, "Mozilla/5.0 test",
                        "https://shop.acme.test/shipping", true));
    }

    @Test
    void sendsThePurchaseWithTheOrderIdAsEventIdAndTheTokenInTheBody() {
        server.expect(requestTo(EVENTS)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.access_token").value(TOKEN))
                .andExpect(jsonPath("$.test_event_code").doesNotExist())
                .andExpect(jsonPath("$.data[0].event_name").value("Purchase"))
                .andExpect(jsonPath("$.data[0].event_id").value("6720f0c2a1b2c3d4e5f60718"))
                .andExpect(jsonPath("$.data[0].event_time").value(1793440800))
                .andExpect(jsonPath("$.data[0].action_source").value("website"))
                .andExpect(jsonPath("$.data[0].event_source_url").value("https://shop.acme.test/shipping"))
                .andExpect(jsonPath("$.data[0].user_data.em[0]")
                        .value("f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79"))
                .andExpect(jsonPath("$.data[0].user_data.ph").doesNotExist())
                .andExpect(jsonPath("$.data[0].user_data.fbp").value("fb.1.1730000000000.1098115397"))
                .andExpect(jsonPath("$.data[0].user_data.client_user_agent").value("Mozilla/5.0 test"))
                .andExpect(jsonPath("$.data[0].custom_data.currency").value("INR"))
                .andExpect(jsonPath("$.data[0].custom_data.value").value(790.0))
                .andExpect(jsonPath("$.data[0].custom_data.order_id").value("6720f0c2a1b2c3d4e5f60718"))
                .andExpect(jsonPath("$.data[0].custom_data.contents[0].id").value("p1"))
                .andExpect(jsonPath("$.data[0].custom_data.num_items").value(1))
                .andRespond(withSuccess("{\"events_received\":1,\"fbtrace_id\":\"x\"}", MediaType.APPLICATION_JSON));
        meta.send(creds, purchase());
        server.verify();
    }

    private DeliveryException failure(ResponseCreator response) {
        server.reset();
        server.expect(requestTo(EVENTS)).andRespond(response);
        try {
            meta.send(creds, purchase());
        } catch (DeliveryException e) {
            return e;
        }
        throw new AssertionError("expected a DeliveryException");
    }

    private static ResponseCreator metaError(int code, String message) {
        return withBadRequest().contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"" + message
                + "\",\"type\":\"OAuthException\",\"code\":" + code + ",\"fbtrace_id\":\"t\"}}");
    }

    @Test
    void outagesThrottlingAndTimeoutsAreRetryable() {
        assertThat(failure(withServerError()).code()).isEqualTo(DeliveryCode.PROVIDER_DOWN);
        DeliveryException throttled = failure(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120"));
        assertThat(throttled.code()).isEqualTo(DeliveryCode.RATE_LIMITED);
        assertThat(throttled.retryAfter()).isEqualTo(Duration.ofSeconds(120));
        assertThat(failure(metaError(17, "User request limit reached")).code()).isEqualTo(DeliveryCode.RATE_LIMITED);
        assertThat(failure(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Temporary\",\"code\":2,\"is_transient\":true}}")).code())
                .isEqualTo(DeliveryCode.PROVIDER_DOWN);
        assertThat(failure(withException(new SocketTimeoutException("Read timed out"))).code())
                .isEqualTo(DeliveryCode.TIMEOUT);
        assertThat(DeliveryCode.TIMEOUT.retryable()).isTrue();
    }

    @Test
    void aBadTokenOrParameterFailsOnceWithAnOwnerMessageThatHidesTheToken() {
        DeliveryException auth = failure(metaError(190, "Invalid OAuth access token"));
        assertThat(auth.code()).isEqualTo(DeliveryCode.AUTH_FAILED);
        assertThat(auth.ownerMessage(Provider.META)).isEqualTo(
                "Meta rejected the saved secret. Create a new one and save it here.");
        assertThat(failure(withUnauthorizedRequest()).code()).isEqualTo(DeliveryCode.AUTH_FAILED);

        DeliveryException rejected = failure(metaError(100, "Invalid parameter near " + TOKEN));
        assertThat(rejected.code()).isEqualTo(DeliveryCode.REJECTED);
        assertThat(rejected.ownerMessage(Provider.META)).isEqualTo(
                "Meta rejected the event: Invalid parameter near [hidden]");
        assertThat(rejected.getMessage()).isEqualTo("REJECTED");
    }

    @Test
    void theTestEventUsesTheTestCodeAndReportsMetasAnswer() {
        server.expect(requestTo(EVENTS)).andExpect(jsonPath("$.test_event_code").value("TEST123"))
                .andExpect(jsonPath("$.access_token").value(TOKEN))
                .andExpect(jsonPath("$.data[0].event_name").value("Purchase"))
                .andRespond(withSuccess("{\"events_received\":1}", MediaType.APPLICATION_JSON));
        TestVerdict ok = meta.test(creds, "TEST123", "INR");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).contains("Test events");

        server.reset();
        server.expect(requestTo(EVENTS)).andRespond(metaError(190, "Invalid OAuth access token"));
        TestVerdict bad = meta.test(creds, "TEST123", "INR");
        assertThat(bad).isEqualTo(new TestVerdict(false, "AUTH_FAILED",
                "Meta rejected the saved secret. Create a new one and save it here."));
    }

    @Test
    void nothingLogsTheToken(CapturedOutput output) {
        failure(withServerError());
        failure(withException(new SocketTimeoutException("Read timed out")));
        failure(metaError(100, "Invalid parameter near " + TOKEN));
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }
}
```

`Ga4MeasurementProtocolTest.java`:

```java
package com.sixpack.marketing.infra.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.MarketingDestination.Credentials;
import com.sixpack.marketing.domain.MarketingDestination.TestVerdict;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class Ga4MeasurementProtocolTest {

    private static final String SECRET = "fake-ga4-secret-aaaaaaa";
    private static final String COLLECT = "https://ga.test/mp/collect?measurement_id=G-AB12CD3&api_secret=" + SECRET;
    private static final String DEBUG = "https://ga.test/debug/mp/collect?measurement_id=G-AB12CD3&api_secret=" + SECRET;
    private final Credentials creds = new Credentials("G-AB12CD3", SECRET);

    private MockRestServiceServer server;
    private Ga4MeasurementProtocol ga4;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ga.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ga4 = new Ga4MeasurementProtocol(builder.build());
    }

    static Conversion purchase(String gaClientId, boolean adConsent) {
        return new Conversion("6720f0c2a1b2c3d4e5f60718", Instant.parse("2026-10-31T10:00:00Z"), "INR", 790.0, 90.0,
                200.0, "DIWALI", List.of(new Conversion.Item("p1", "Lamp", "Red", 500.0, 1)),
                new Conversion.Identity(null, null, null, null, null, gaClientId, null, null, adConsent));
    }

    @Test
    void sendsThePurchaseWithTheOrderIdAsTransactionIdAndTheBrowsersClientId() {
        server.expect(requestTo(COLLECT)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.client_id").value("1234567890.1730000000"))
                .andExpect(jsonPath("$.timestamp_micros").value(1793440800000000L))
                .andExpect(jsonPath("$.consent.ad_user_data").value("GRANTED"))
                .andExpect(jsonPath("$.consent.ad_personalization").value("GRANTED"))
                .andExpect(jsonPath("$.user_data").doesNotExist())
                .andExpect(jsonPath("$.events[0].name").value("purchase"))
                .andExpect(jsonPath("$.events[0].params.transaction_id").value("6720f0c2a1b2c3d4e5f60718"))
                .andExpect(jsonPath("$.events[0].params.currency").value("INR"))
                .andExpect(jsonPath("$.events[0].params.value").value(790.0))
                .andExpect(jsonPath("$.events[0].params.coupon").value("DIWALI"))
                .andExpect(jsonPath("$.events[0].params.items[0].item_id").value("p1"))
                .andExpect(jsonPath("$.events[0].params.items[0].item_variant").value("Red"))
                .andRespond(withNoContent());
        ga4.send(creds, purchase("1234567890.1730000000", true));
        server.verify();
    }

    @Test
    void withoutTheBrowsersIdAStableOneIsMadeAndAdConsentFollowsTheChoice() {
        server.expect(requestTo(COLLECT))
                .andExpect(jsonPath("$.client_id").value(org.hamcrest.Matchers.matchesPattern("^\\d+\\.1793440800$")))
                .andExpect(jsonPath("$.consent.ad_user_data").value("DENIED"))
                .andRespond(withNoContent());
        ga4.send(creds, purchase(null, false));
        server.verify();
    }

    private DeliveryException failure(ResponseCreator response) {
        server.reset();
        server.expect(requestTo(COLLECT)).andRespond(response);
        try {
            ga4.send(creds, purchase(null, true));
        } catch (DeliveryException e) {
            return e;
        }
        throw new AssertionError("expected a DeliveryException");
    }

    @Test
    void statusesMapToRetryOrFail() {
        assertThat(failure(withServerError()).code()).isEqualTo(DeliveryCode.PROVIDER_DOWN);
        assertThat(failure(withStatus(HttpStatus.TOO_MANY_REQUESTS)).code()).isEqualTo(DeliveryCode.RATE_LIMITED);
        assertThat(failure(withException(new SocketTimeoutException("Read timed out"))).code())
                .isEqualTo(DeliveryCode.TIMEOUT);
        assertThat(failure(withStatus(HttpStatus.FORBIDDEN)).code()).isEqualTo(DeliveryCode.AUTH_FAILED);
        DeliveryException rejected = failure(withBadRequest());
        assertThat(rejected.code()).isEqualTo(DeliveryCode.REJECTED);
        assertThat(rejected.ownerMessage(com.sixpack.marketing.domain.Provider.GOOGLE))
                .isEqualTo("Google rejected the event: HTTP 400");
    }

    @Test
    void theTestUsesTheValidationServerAndReportsItsFirstProblem() {
        server.expect(requestTo(DEBUG)).andExpect(jsonPath("$.events[0].name").value("purchase"))
                .andRespond(withSuccess("{\"validationMessages\":[]}", MediaType.APPLICATION_JSON));
        TestVerdict ok = ga4.test(creds, null, "INR");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).contains("does not check the API secret");

        server.reset();
        server.expect(requestTo(DEBUG)).andRespond(withSuccess("{\"validationMessages\":[{\"fieldPath\":\"events\","
                + "\"description\":\"Event at index: [0] has invalid name\",\"validationCode\":\"NAME_INVALID\"}]}",
                MediaType.APPLICATION_JSON));
        TestVerdict bad = ga4.test(creds, null, "INR");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.code()).isEqualTo("REJECTED");
        assertThat(bad.message()).isEqualTo("Google found a problem: Event at index: [0] has invalid name");
    }

    @Test
    void nothingLogsOrThrowsTheSecret(CapturedOutput output) {
        DeliveryException timeout = failure(withException(new SocketTimeoutException("Read timed out")));
        failure(withServerError());
        failure(withBadRequest());
        assertThat(timeout.getMessage()).doesNotContain(SECRET);
        assertThat(timeout.ownerMessage(com.sixpack.marketing.domain.Provider.GOOGLE)).doesNotContain(SECRET);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='MetaConversionsApiTest,Ga4MeasurementProtocolTest'`
Expected: FAIL — compilation errors (`MetaConversionsApi`, `Ga4MeasurementProtocol` not found).

- [ ] **Step 3: Implement**

`infra/http/ProviderHttp.java`:

```java
package com.sixpack.marketing.infra.http;

import com.sixpack.marketing.domain.DeliveryCode;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Shared plumbing for the provider adapters: a client per provider host with fixed timeouts, and error mapping. */
public final class ProviderHttp {

    private ProviderHttp() {
    }

    /**
     * A plain builder on purpose: Spring's auto-configured one records the full request URL on trace spans, and the
     * GA4 secret travels in the query string.
     */
    public static RestClient client(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Seconds from a Retry-After header, or null. */
    public static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return value == null ? null : Duration.ofSeconds(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A provider's own words, safe to show the owner: no secret, no control characters, at most 200 characters. */
    public static String clean(String text, String secret) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = secret == null || secret.isEmpty() ? text : text.replace(secret, "[hidden]");
        s = s.replaceAll("\\p{Cntrl}", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Status-only mapping; Meta refines its 4xx with error codes. */
    public static DeliveryCode byStatus(int status) {
        if (status == 429) {
            return DeliveryCode.RATE_LIMITED;
        }
        if (status >= 500) {
            return DeliveryCode.PROVIDER_DOWN;
        }
        if (status == 401 || status == 403) {
            return DeliveryCode.AUTH_FAILED;
        }
        return DeliveryCode.REJECTED;
    }
}
```

`infra/meta/MetaConversionsApi.java`:

```java
package com.sixpack.marketing.infra.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.marketing.application.MarketingProperties;
import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.infra.http.ProviderHttp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Meta Conversions API, Graph API version from configuration (v25.0 when written). */
@Component
class MetaConversionsApi implements MarketingDestination {

    private static final Logger log = LoggerFactory.getLogger(MetaConversionsApi.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Graph API error codes meaning "slow down", "try again" or "bad token". */
    private static final Set<Integer> THROTTLED = Set.of(4, 17, 32, 613, 80004);
    private static final Set<Integer> TRANSIENT = Set.of(1, 2);
    private static final Set<Integer> AUTH = Set.of(102, 190);

    private final RestClient client;
    private final String apiVersion;

    @Autowired
    MetaConversionsApi(MarketingProperties props) {
        this(ProviderHttp.client(props.meta().baseUrl(), props.http().connectTimeout(), props.http().readTimeout()),
                props.meta().apiVersion());
    }

    /** Takes a ready client, so tests can put a mock server behind it. */
    MetaConversionsApi(RestClient client, String apiVersion) {
        this.client = client;
        this.apiVersion = apiVersion;
    }

    @Override
    public Provider provider() {
        return Provider.META;
    }

    @Override
    public void send(Credentials credentials, Conversion conversion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", List.of(event(conversion)));
        body.put("access_token", credentials.secret());
        post(credentials, body);
    }

    @Override
    public TestVerdict test(Credentials credentials, String testEventCode, String currency) {
        Conversion sample = new Conversion("test-" + UUID.randomUUID(), Instant.now(), currency, 1.0, 0, 0, null,
                List.of(new Conversion.Item("test-item", "Test item", null, 1.0, 1)),
                new Conversion.Identity(null, null, null, null, null, null, "6Pack test event", null, true));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", List.of(event(sample)));
        body.put("test_event_code", testEventCode);
        body.put("access_token", credentials.secret());
        try {
            Map<?, ?> reply = post(credentials, body);
            return reply != null && reply.get("events_received") instanceof Number n && n.intValue() > 0
                    ? new TestVerdict(true, null, "Meta received the test event. Open Events Manager → Test events to see it.")
                    : new TestVerdict(false, DeliveryCode.REJECTED.name(), "Meta answered but did not count the event.");
        } catch (DeliveryException e) {
            return new TestVerdict(false, e.code().name(), e.ownerMessage(Provider.META));
        }
    }

    private Map<?, ?> post(Credentials credentials, Map<String, Object> body) {
        try {
            // The token travels in the JSON body, never in the URL.
            return client.post().uri("/{version}/{pixel}/events", apiVersion, credentials.id())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw refused(e, credentials.secret());
        } catch (ResourceAccessException e) {
            throw new DeliveryException(DeliveryCode.TIMEOUT, null, null);
        } catch (RestClientException e) {
            log.warn("Meta Conversions API call failed: {}", e.getClass().getSimpleName());
            throw new DeliveryException(DeliveryCode.PROVIDER_DOWN, null, null);
        }
    }

    private static DeliveryException refused(RestClientResponseException e, String secret) {
        Map<?, ?> error = errorOf(e);
        int code = error.get("code") instanceof Number n ? n.intValue() : -1;
        int status = e.getStatusCode().value();
        DeliveryCode kind;
        if (status == 429 || THROTTLED.contains(code)) {
            kind = DeliveryCode.RATE_LIMITED;
        } else if (status >= 500 || TRANSIENT.contains(code) || Boolean.TRUE.equals(error.get("is_transient"))) {
            kind = DeliveryCode.PROVIDER_DOWN;
        } else if (AUTH.contains(code)) {
            kind = DeliveryCode.AUTH_FAILED;
        } else {
            kind = ProviderHttp.byStatus(status);
        }
        String detail = kind == DeliveryCode.REJECTED
                ? ProviderHttp.clean(error.get("message") instanceof String s ? s : null, secret) : null;
        return new DeliveryException(kind, detail, ProviderHttp.retryAfter(e.getResponseHeaders()));
    }

    private static Map<?, ?> errorOf(RestClientResponseException e) {
        try {
            Map<?, ?> body = JSON.readValue(e.getResponseBodyAsByteArray(), Map.class);
            return body != null && body.get("error") instanceof Map<?, ?> m ? m : Map.of();
        } catch (Exception ignored) {
            return Map.of(); // not JSON: map by status only
        }
    }

    static Map<String, Object> event(Conversion c) {
        Conversion.Identity id = c.identity();
        Map<String, Object> user = new LinkedHashMap<>();
        if (id.emailSha256() != null) {
            user.put("em", List.of(id.emailSha256()));
        }
        if (id.phoneSha256() != null) {
            user.put("ph", List.of(id.phoneSha256()));
        }
        if (id.externalIdSha256() != null) {
            user.put("external_id", List.of(id.externalIdSha256()));
        }
        putIf(user, "fbp", id.fbp());
        putIf(user, "fbc", id.fbc());
        putIf(user, "client_user_agent", id.userAgent());
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("currency", c.currency());
        custom.put("value", c.value());
        custom.put("order_id", c.eventId());
        custom.put("content_type", "product");
        custom.put("content_ids", c.items().stream().map(Conversion.Item::id).toList());
        custom.put("contents", c.items().stream().map(i -> Map.of("id", i.id(), "quantity", i.quantity(),
                "item_price", i.price())).toList());
        custom.put("num_items", c.items().stream().mapToInt(Conversion.Item::quantity).sum());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_name", "Purchase");
        event.put("event_time", c.at().getEpochSecond());
        event.put("event_id", c.eventId()); // = the Pixel's eventID, so Meta counts the purchase once
        event.put("action_source", "website");
        putIf(event, "event_source_url", id.sourceUrl());
        event.put("user_data", user);
        event.put("custom_data", custom);
        return event;
    }

    private static void putIf(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
```

`infra/google/Ga4MeasurementProtocol.java`:

```java
package com.sixpack.marketing.infra.google;

import com.sixpack.marketing.application.MarketingProperties;
import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.infra.http.ProviderHttp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** GA4 Measurement Protocol. The API secret is a query parameter (Google's design), so nothing here logs a URL. */
@Component
class Ga4MeasurementProtocol implements MarketingDestination {

    private static final Logger log = LoggerFactory.getLogger(Ga4MeasurementProtocol.class);

    private final RestClient client;

    @Autowired
    Ga4MeasurementProtocol(MarketingProperties props) {
        this(ProviderHttp.client(props.google().baseUrl(), props.http().connectTimeout(), props.http().readTimeout()));
    }

    /** Takes a ready client, so tests can put a mock server behind it. */
    Ga4MeasurementProtocol(RestClient client) {
        this.client = client;
    }

    @Override
    public Provider provider() {
        return Provider.GOOGLE;
    }

    @Override
    public void send(Credentials credentials, Conversion conversion) {
        call("/mp/collect", credentials, payload(conversion, clientId(conversion)), Void.class);
    }

    @Override
    public TestVerdict test(Credentials credentials, String testEventCode, String currency) {
        Conversion sample = new Conversion("test-" + UUID.randomUUID(), Instant.now(), currency, 1.0, 0, 0, null,
                List.of(new Conversion.Item("test-item", "Test item", null, 1.0, 1)),
                new Conversion.Identity(null, null, null, null, null, null, null, null, false));
        try {
            Map<?, ?> reply = call("/debug/mp/collect", credentials, payload(sample, "test.1"), Map.class);
            List<?> problems = reply != null && reply.get("validationMessages") instanceof List<?> l ? l : List.of();
            if (problems.isEmpty()) {
                return new TestVerdict(true, null, "Google accepted the event. Google does not check the API secret "
                        + "here, so look for your next order in GA4 → Realtime.");
            }
            String first = problems.get(0) instanceof Map<?, ?> m && m.get("description") instanceof String s ? s
                    : "the event was not valid";
            return new TestVerdict(false, DeliveryCode.REJECTED.name(),
                    "Google found a problem: " + ProviderHttp.clean(first, credentials.secret()));
        } catch (DeliveryException e) {
            return new TestVerdict(false, e.code().name(), e.ownerMessage(Provider.GOOGLE));
        }
    }

    private <T> T call(String path, Credentials credentials, Map<String, Object> body, Class<T> type) {
        try {
            return client.post().uri(path + "?measurement_id={id}&api_secret={secret}", credentials.id(),
                    credentials.secret()).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            DeliveryCode kind = ProviderHttp.byStatus(status);
            throw new DeliveryException(kind, kind == DeliveryCode.REJECTED ? "HTTP " + status : null,
                    ProviderHttp.retryAfter(e.getResponseHeaders()));
        } catch (ResourceAccessException e) {
            // Never log e: its message holds the request URL, and with it the API secret.
            throw new DeliveryException(DeliveryCode.TIMEOUT, null, null);
        } catch (RestClientException e) {
            log.warn("GA4 Measurement Protocol call failed: {}", e.getClass().getSimpleName());
            throw new DeliveryException(DeliveryCode.PROVIDER_DOWN, null, null);
        }
    }

    static Map<String, Object> payload(Conversion c, String clientId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("transaction_id", c.eventId()); // = the browser's transaction_id, so GA4 keeps one purchase
        params.put("currency", c.currency());
        params.put("value", c.value());
        params.put("tax", c.tax());
        params.put("shipping", c.shipping());
        if (c.coupon() != null) {
            params.put("coupon", c.coupon());
        }
        params.put("items", c.items().stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_id", i.id());
            item.put("item_name", i.name());
            if (i.variant() != null) {
                item.put("item_variant", i.variant());
            }
            item.put("price", i.price());
            item.put("quantity", i.quantity());
            return item;
        }).toList());
        String ads = c.identity().adConsent() ? "GRANTED" : "DENIED";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("timestamp_micros", c.at().toEpochMilli() * 1000);
        body.put("consent", Map.of("ad_user_data", ads, "ad_personalization", ads));
        body.put("events", List.of(Map.of("name", "purchase", "params", params)));
        return body;
    }

    /** The browser's GA client id when the shopper allowed analytics cookies, else a stable one made from the order. */
    static String clientId(Conversion c) {
        return c.identity().gaClientId() != null ? c.identity().gaClientId()
                : Integer.toUnsignedString(c.eventId().hashCode()) + "." + c.at().getEpochSecond();
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='MetaConversionsApiTest,Ga4MeasurementProtocolTest,ModularityTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/infra/http backend/src/main/java/com/sixpack/marketing/infra/meta \
        backend/src/main/java/com/sixpack/marketing/infra/google \
        backend/src/test/java/com/sixpack/marketing/infra/meta backend/src/test/java/com/sixpack/marketing/infra/google
git commit -m "feat(marketing): Meta Conversions API and GA4 Measurement Protocol adapters

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A10: Recorder (listener) and reconciliation sweep

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/application/ConversionRecorder.java`, `ConversionSweep.java`
- Test: `backend/src/test/java/com/sixpack/it/ConversionRecorderIntegrationTest.java`, `backend/src/test/java/com/sixpack/marketing/application/ConversionRecorderTest.java`

- [ ] **Step 1: Write the failing tests**

`ConversionRecorderIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.marketing.application.ConversionRecorder;
import com.sixpack.marketing.application.ConversionSweep;
import com.sixpack.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A paid order is queued once per provider the shopper allowed and the store set up; never a network call. */
class ConversionRecorderIntegrationTest extends MarketingServerIntegrationTest {

    @Autowired
    ConversionRecorder recorder;
    @Autowired
    ConversionSweep sweep;

    Store acme;

    @BeforeEach
    void store() throws Exception {
        acme = configuredStore("acme", "123456789012345", "fake-meta-token-aaaaaaaa", "G-AB12CD3",
                "fake-ga4-secret-aaaaaaa");
        STUB.reset();
    }

    @Test
    void aPaidOrderIsQueuedOncePerProviderTheShopperAllowed() throws Exception {
        String orderId = order(acme, consented(true, true));
        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("provider"))
                .containsExactlyInAnyOrder("META", "GOOGLE");
        Document meta = outboxRow(acme.tenant(), "META", orderId);
        assertThat(meta.getString("status")).isEqualTo("PENDING");
        assertThat(meta.getString("eventName")).isEqualTo("purchase");
        assertThat(meta.getString("orderNumber")).startsWith("ORD-");
        assertThat(meta.getInteger("attempts")).isZero();
        assertThat(meta.keySet()).doesNotContain("email", "phone", "payload", "userAgent");
        assertThat(STUB.calls()).isEmpty(); // queueing never calls a provider
    }

    @Test
    void eachProviderNeedsItsOwnConsent() throws Exception {
        String marketingOnly = order(acme, consented(false, true));
        String analyticsOnly = order(acme, consented(true, false));
        String declined = order(acme, consented(false, false));
        String none = order(acme, null);
        assertThat(outboxRow(acme.tenant(), "META", marketingOnly)).isNotNull();
        assertThat(outboxRow(acme.tenant(), "GOOGLE", marketingOnly)).isNull();
        assertThat(outboxRow(acme.tenant(), "GOOGLE", analyticsOnly)).isNotNull();
        assertThat(outboxRow(acme.tenant(), "META", analyticsOnly)).isNull();
        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("eventId"))
                .doesNotContain(declined, none);
    }

    @Test
    void theSameOrderIsQueuedOnceHoweverOftenItIsSeen() throws Exception {
        String orderId = order(acme, consented(true, true));
        for (int i = 0; i < 2; i++) { // each status change publishes OrderChangedEvent again
            mvc.perform(put("/api/v1/order/" + orderId).with(host(hostOf(acme.tenant())))
                    .header("Authorization", bearer(acme.admin()))).andExpect(status().isOk());
        }
        concurrently(10, () -> TenantContext.callAs(ref(acme.tenant()), () -> recorder.record(orderId)));
        assertThat(outboxRows(acme.tenant())).hasSize(2);
    }

    @Test
    void nothingIsQueuedWhenServerEventsOrTheFeatureAreOff() throws Exception {
        saveServer(acme.tenant(), acme.admin(), "meta", server(false, null, null)).andExpect(status().isOk());
        String metaOff = order(acme, consented(true, true));
        assertThat(outboxRow(acme.tenant(), "META", metaOff)).isNull();
        assertThat(outboxRow(acme.tenant(), "GOOGLE", metaOff)).isNotNull();

        marketing(acme.tenant(), false);
        String featureOff = order(acme, consented(true, true));
        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("eventId")).doesNotContain(featureOff);
    }

    @Test
    void theSweepQueuesAPaidOrderTheListenerMissed() throws Exception {
        saveServer(acme.tenant(), acme.admin(), "meta", server(false, null, null)).andExpect(status().isOk());
        saveServer(acme.tenant(), acme.admin(), "google", server(false, null, null)).andExpect(status().isOk());
        String missed = order(acme, consented(true, true)); // as if the process died before queueing
        String old = order(acme, consented(true, true));
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(old)),
                new Document("$set", new Document("createdAt", Date.from(Instant.now().minusSeconds(4 * 24 * 3600)))));
        assertThat(outboxRows(acme.tenant())).isEmpty();

        saveServer(acme.tenant(), acme.admin(), "meta", server(true, null, null)).andExpect(status().isOk());
        saveServer(acme.tenant(), acme.admin(), "google", server(true, null, null)).andExpect(status().isOk());
        sweep.sweep(Instant.now().plusSeconds(120));
        sweep.sweep(Instant.now().plusSeconds(120)); // running again adds nothing

        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("eventId")).containsOnly(missed).hasSize(2);
        assertThat(STUB.calls()).isEmpty();
    }
}
```

`ConversionRecorderTest.java` (unit, Mockito):

```java
package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.PaidOrders;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ConversionRecorderTest {

    @Test
    void aFailingRecorderNeverFailsCheckout() {
        TenantFeatures features = mock(TenantFeatures.class);
        when(features.enabled(Feature.MARKETING)).thenReturn(true);
        SecretSealer secrets = mock(SecretSealer.class);
        when(secrets.available()).thenReturn(true);
        PaidOrders orders = mock(PaidOrders.class);
        when(orders.find(anyString())).thenThrow(new IllegalStateException("database down"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ConversionRecorder recorder = new ConversionRecorder(features, orders, mock(IntegrationRepository.class),
                mock(Outbox.class), secrets, meters);

        assertThatCode(() -> recorder.onOrderChanged(new OrderChangedEvent("o1"))).doesNotThrowAnyException();
        assertThat(meters.counter("marketing.record.failed", "tenant", "none").count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='ConversionRecorderTest,ConversionRecorderIntegrationTest'`
Expected: FAIL — compilation errors (`ConversionRecorder`, `ConversionSweep` not found).

- [ ] **Step 3: Implement**

`ConversionRecorder.java`:

```java
package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.order.OrderAttribution;
import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.PaidOrders;
import com.sixpack.order.PaidOrders.PaidOrder;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantFeatures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Queues each paid order for the providers the shopper allowed and the store set up (spec §6 server purchase).
 * Runs inside checkout's thread, so it makes no network call and never throws; {@link ConversionSweep} catches what
 * a crash between saving the order and queueing it would lose.
 */
@Service
public class ConversionRecorder {

    private static final Logger log = LoggerFactory.getLogger(ConversionRecorder.class);

    private final TenantFeatures features;
    private final PaidOrders orders;
    private final IntegrationRepository integrations;
    private final Outbox outbox;
    private final SecretSealer secrets;
    private final MeterRegistry meters;

    ConversionRecorder(TenantFeatures features, PaidOrders orders, IntegrationRepository integrations, Outbox outbox,
                       SecretSealer secrets, MeterRegistry meters) {
        this.features = features;
        this.orders = orders;
        this.integrations = integrations;
        this.outbox = outbox;
        this.secrets = secrets;
        this.meters = meters;
    }

    /** Idempotent: the outbox keeps one row per provider and order. @return rows added */
    public int record(String orderId) {
        if (!secrets.available() || !features.enabled(Feature.MARKETING)) {
            return 0;
        }
        PaidOrder order = orders.find(orderId).orElse(null);
        if (order == null || order.attribution() == null) {
            return 0;
        }
        int added = 0;
        for (Integration i : integrations.findAll()) {
            if (i.server().enabled() && i.server().hasSecret() && allowed(i.provider(), order.attribution().consent())
                    && outbox.addIfAbsent(i.provider(), order.id(), order.id(), order.orderNumber(), Instant.now())) {
                added++;
            }
        }
        return added;
    }

    @EventListener
    void onOrderChanged(OrderChangedEvent event) {
        try {
            record(event.orderId());
        } catch (RuntimeException e) {
            log.warn("Could not queue marketing conversions for order {}; the sweep retries", event.orderId(), e);
            meters.counter("marketing.record.failed", Tags.of(TenantContext.metricTag())).increment();
        }
    }

    /** Meta follows the marketing choice, GA4 the analytics choice (as the browser tags do). */
    static boolean allowed(Provider provider, OrderAttribution.Consent consent) {
        return consent != null && (provider == Provider.META ? consent.marketing() : consent.analytics());
    }
}
```

`ConversionSweep.java`:

```java
package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.order.PaidOrders;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.StoreDirectory;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.tenant.TenantRef;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciliation for {@link ConversionRecorder} (no Mongo transactions: the order is saved, then queued). Every few
 * minutes it re-queues consented paid orders of the last 72 h (GA4's limit) that have no row. Safe on every replica:
 * queueing is idempotent.
 */
@Component
public class ConversionSweep {

    private static final Logger log = LoggerFactory.getLogger(ConversionSweep.class);
    static final Duration WINDOW = Duration.ofHours(72);
    /** Leaves the newest minute to the listener, which normally queues within milliseconds. */
    static final Duration GRACE = Duration.ofMinutes(1);
    /** shortcut: at most this many orders per store per run, oldest first; switch to keyset paging past it. */
    static final int LIMIT = 2000;

    private final StoreDirectory stores;
    private final TenantFeatures features;
    private final IntegrationRepository integrations;
    private final PaidOrders orders;
    private final Outbox outbox;
    private final SecretSealer secrets;
    private final ConversionRecorder recorder;

    ConversionSweep(StoreDirectory stores, TenantFeatures features, IntegrationRepository integrations,
                    PaidOrders orders, Outbox outbox, SecretSealer secrets, ConversionRecorder recorder) {
        this.stores = stores;
        this.features = features;
        this.integrations = integrations;
        this.orders = orders;
        this.outbox = outbox;
        this.secrets = secrets;
        this.recorder = recorder;
    }

    @Scheduled(initialDelayString = "${app.marketing.sweep-interval:PT5M}",
            fixedDelayString = "${app.marketing.sweep-interval:PT5M}")
    void scheduled() {
        sweep(Instant.now());
    }

    public void sweep(Instant now) {
        if (!secrets.available()) {
            return;
        }
        for (TenantRef store : stores.stores()) {
            try {
                TenantContext.runAs(store, () -> sweepStore(now));
            } catch (RuntimeException e) {
                log.warn("Marketing sweep failed for store {}", store.slug(), e);
            }
        }
    }

    private void sweepStore(Instant now) {
        if (!features.enabled(Feature.MARKETING)) {
            return;
        }
        List<Provider> on = integrations.findAll().stream().filter(i -> i.server().enabled())
                .map(Integration::provider).toList();
        if (on.isEmpty()) {
            return;
        }
        List<String> ids = orders.consentedBetween(now.minus(WINDOW), now.minus(GRACE), LIMIT);
        Set<String> complete = new HashSet<>(ids);
        for (Provider p : on) {
            complete.retainAll(outbox.existingEventIds(p, ids));
        }
        ids.stream().filter(id -> !complete.contains(id)).forEach(recorder::record);
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='ConversionRecorderTest,ConversionRecorderIntegrationTest,CheckoutAttributionIntegrationTest,ModularityTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/application/ConversionRecorder.java \
        backend/src/main/java/com/sixpack/marketing/application/ConversionSweep.java \
        backend/src/test/java/com/sixpack/it/ConversionRecorderIntegrationTest.java \
        backend/src/test/java/com/sixpack/marketing/application/ConversionRecorderTest.java
git commit -m "feat(marketing): queue paid orders for server events, with a reconciliation sweep

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A11: Dispatcher — claim, send, backoff, give up, breaker, bulkhead, shutdown, metrics

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/application/Backoff.java`, `ProviderBreaker.java`, `ConversionDispatcher.java`
- Test: `marketing/application/BackoffTest.java`, `ProviderBreakerTest.java`, `ConversionDispatcherLifecycleTest.java`; `it/ConversionDispatcherIntegrationTest.java`

- [ ] **Step 1: Write the failing tests**

`BackoffTest.java`:

```java
package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void followsTheStepsWithTwentyPercentJitter() {
        assertThat(Backoff.after(1, null, 0.0)).isEqualTo(Duration.ofSeconds(48));
        assertThat(Backoff.after(1, null, 0.999)).isBetween(Duration.ofSeconds(71), Duration.ofSeconds(72));
        assertThat(Backoff.after(2, null, 0.5)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Backoff.after(3, null, 0.5)).isEqualTo(Duration.ofMinutes(30));
        assertThat(Backoff.after(4, null, 0.5)).isEqualTo(Duration.ofHours(2));
        assertThat(Backoff.after(5, null, 0.0)).isEqualTo(Duration.ofMinutes(576)); // 0.8 × 12 h
    }

    @Test
    void neverWaitsMoreThanTwelveHoursAndHonoursRetryAfter() {
        assertThat(Backoff.after(5, null, 0.999)).isEqualTo(Duration.ofHours(12));
        assertThat(Backoff.after(9, null, 0.5)).isEqualTo(Duration.ofHours(12));
        assertThat(Backoff.after(1, Duration.ofMinutes(10), 0.5)).isEqualTo(Duration.ofMinutes(10));
        assertThat(Backoff.after(1, Duration.ofDays(2), 0.5)).isEqualTo(Duration.ofHours(12));
        assertThat(Backoff.after(3, Duration.ofSeconds(5), 0.5)).isEqualTo(Duration.ofMinutes(30));
    }
}
```

`ProviderBreakerTest.java`:

```java
package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderBreakerTest {

    private Instant time = Instant.parse("2026-10-31T10:00:00Z");
    private final ProviderBreaker breaker = new ProviderBreaker(Duration.ofSeconds(60), () -> time);

    @Test
    void opensAfterFiveFailuresInARowAndAFailedProbeReopensIt() {
        for (int i = 0; i < 4; i++) {
            breaker.failure();
        }
        assertThat(breaker.isOpen()).isFalse();
        breaker.failure();
        assertThat(breaker.isOpen()).isTrue();

        time = time.plusSeconds(61);
        assertThat(breaker.isOpen()).isFalse();
        breaker.failure(); // the first call after the pause failed too
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void aSuccessResetsTheCount() {
        for (int i = 0; i < 4; i++) {
            breaker.failure();
        }
        breaker.success();
        for (int i = 0; i < 4; i++) {
            breaker.failure();
        }
        assertThat(breaker.isOpen()).isFalse();
    }
}
```

`ConversionDispatcherLifecycleTest.java`:

```java
package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.order.OrderAttribution;
import com.sixpack.order.PaidOrders;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.tenant.TenantRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Graceful shutdown (D10): the send in flight finishes, nothing more is claimed. */
class ConversionDispatcherLifecycleTest {

    @Test
    void stopFinishesTheSendInFlightAndClaimsNothingMore() throws Exception {
        Instant now = Instant.now();
        TenantFeatures features = mock(TenantFeatures.class);
        when(features.enabled(Feature.MARKETING)).thenReturn(true);
        Outbox outbox = mock(Outbox.class);
        when(outbox.claim(eq(Provider.META), any(), any())).thenReturn(Optional.of(new OutboxRow("r1", Provider.META,
                "o1", "purchase", "o1", "ORD-1", OutboxRow.Status.SENDING, 1, now, "c1", null, now, now)));
        when(outbox.finish(any(), any(), any(), any(), any())).thenReturn(true);
        PaidOrders orders = mock(PaidOrders.class);
        when(orders.find("o1")).thenReturn(Optional.of(new PaidOrders.PaidOrder("o1", "ORD-1", "u1", now, 790, 90,
                200, "INR", null, List.of(), null, null, new OrderAttribution(null, null, null, null, null, null, null,
                null, null, null, null, "UA", null, new OrderAttribution.Consent(true, true, "2026-10-31", now)))));
        IntegrationRepository integrations = mock(IntegrationRepository.class);
        when(integrations.findAll()).thenReturn(List.of(new Integration(Provider.META,
                new BrowserTags(null, null, null, null, "1234567890"),
                new ServerSettings(true, null, new SealedSecret("k1", "aXY=", "Yw==")), null, null, now)));
        SecretSealer secrets = mock(SecretSealer.class);
        when(secrets.available()).thenReturn(true);
        when(secrets.open(any(), anyString())).thenReturn("fake-meta-token-aaaaaaaa");
        StoreConfig store = mock(StoreConfig.class);
        when(store.currency()).thenReturn("INR");

        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MarketingDestination meta = mock(MarketingDestination.class);
        when(meta.provider()).thenReturn(Provider.META);
        doAnswer(inv -> {
            sending.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(meta).send(any(), any());

        ConversionDispatcher dispatcher = new ConversionDispatcher(() -> List.of(new TenantRef("t1", "acme")),
                features, outbox, integrations, orders, secrets, List.of(meta), store,
                new MarketingProperties("", "2026-10-31", "91", Duration.ofSeconds(60),
                        new MarketingProperties.Meta("https://graph.test", "v25.0"),
                        new MarketingProperties.Google("https://ga.test"),
                        new MarketingProperties.Http(Duration.ofSeconds(1), Duration.ofSeconds(1))),
                new SimpleMeterRegistry());
        dispatcher.start();
        dispatcher.scheduled();
        assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();

        Thread stopper = Thread.ofVirtual().start(dispatcher::stop);
        Thread.sleep(200);
        assertThat(stopper.isAlive()).isTrue(); // waiting for the send in flight
        release.countDown();
        stopper.join(5000);

        assertThat(stopper.isAlive()).isFalse();
        assertThat(dispatcher.isRunning()).isFalse();
        verify(meta, times(1)).send(any(), any());
        verify(outbox, times(1)).claim(any(), any(), any());
    }
}
```

`ConversionDispatcherIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.marketing.application.ConversionDispatcher;
import com.sixpack.marketing.domain.Provider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Real adapters against the provider stub: delivery, retries, give-up, fencing, leases, skips, tenant binding. */
class ConversionDispatcherIntegrationTest extends MarketingServerIntegrationTest {

    static final String META_TOKEN = "fake-meta-token-aaaaaaaa";
    static final String GA_SECRET = "fake-ga4-secret-aaaaaaa";

    @Autowired
    ConversionDispatcher dispatcher;
    @Autowired
    MeterRegistry meters;

    Store acme;

    @BeforeEach
    void store() throws Exception {
        acme = configuredStore("acme", "123456789012345", META_TOKEN, "G-AB12CD3", GA_SECRET);
        STUB.reset();
    }

    private JsonNode card(Store s, int index) throws Exception {
        return body(list(s.tenant(), s.admin()).andReturn()).get("integrations").get(index);
    }

    @Test
    void sendsEachStoresOwnCredentialsWithTheOrderIdAsEventId() throws Exception {
        Store globex = configuredStore("globex", "999999999999999", "fake-meta-token-gggggggg", "G-GLOBEX1",
                "fake-ga4-secret-ggggggg");
        String acmeOrder = order(acme, consented(true, true));
        String globexOrder = order(globex, consented(true, true));
        STUB.reset();

        dispatcher.dispatch(Provider.META);
        dispatcher.dispatch(Provider.GOOGLE);

        ProviderStub.Call acmeMeta = STUB.callsTo(META_EVENTS + "123456789012345/").get(0);
        assertThat(json.readTree(acmeMeta.body()).get("access_token").asText()).isEqualTo(META_TOKEN);
        assertThat(json.readTree(acmeMeta.body()).at("/data/0/event_id").asText()).isEqualTo(acmeOrder);
        ProviderStub.Call globexMeta = STUB.callsTo(META_EVENTS + "999999999999999/").get(0);
        assertThat(globexMeta.body()).contains(globexOrder, "fake-meta-token-gggggggg").doesNotContain(META_TOKEN);

        List<ProviderStub.Call> collects = STUB.callsTo("/mp/collect");
        assertThat(collects).hasSize(2);
        assertThat(collects).anySatisfy(c -> {
            assertThat(c.query()).contains("measurement_id=G-AB12CD3", "api_secret=" + GA_SECRET);
            assertThat(json.readTree(c.body()).at("/events/0/params/transaction_id").asText()).isEqualTo(acmeOrder);
        });
        assertThat(collects).anySatisfy(c -> assertThat(c.query()).contains("G-GLOBEX1", "fake-ga4-secret-ggggggg"));

        assertThat(outboxRow(acme.tenant(), "META", acmeOrder).getString("status")).isEqualTo("SENT");
        assertThat(card(acme, 1).get("lastSuccessAt").isNull()).isFalse();
        assertThat(meters.find("marketing.events").tags("provider", "meta", "outcome", "sent", "tenant", "acme")
                .counter().count()).isEqualTo(1);
        assertThat(meters.find("marketing.outbox.due.age").tag("provider", "meta").gauge()).isNotNull();
    }

    @Test
    void hashedEmailGoesOnlyToMeta() throws Exception {
        order(acme, consented(true, true));
        dispatcher.dispatch(Provider.META);
        dispatcher.dispatch(Provider.GOOGLE);
        JsonNode metaBody = json.readTree(STUB.callsTo(META_EVENTS).get(0).body());
        assertThat(metaBody.at("/data/0/user_data/em/0").asText()).isEqualTo(sha256("buyer@acme.test"));
        assertThat(metaBody.at("/data/0/user_data/client_user_agent").asText()).isEqualTo("Mozilla/5.0 test");
        assertThat(metaBody.at("/data/0/event_source_url").asText()).isEqualTo("https://shop.acme.test/shipping");
        String googleBody = STUB.callsTo("/mp/collect").get(0).body();
        assertThat(googleBody).doesNotContain("buyer@acme.test", sha256("buyer@acme.test"), "\"em\"");
        assertThat(json.readTree(googleBody).get("client_id").asText()).isEqualTo("1234567890.1730000000");
    }

    @Test
    void retryableFailuresBackOffThenGiveUpAndFlagTheCard() throws Exception {
        String orderId = order(acme, consented(false, true));
        STUB.reply(META_EVENTS, ProviderStub.Reply.of(500, "{}"));

        Instant before = Instant.now();
        dispatcher.dispatch(Provider.META);
        Document first = outboxRow(acme.tenant(), "META", orderId);
        assertThat(first.getString("status")).isEqualTo("PENDING");
        assertThat(first.getInteger("attempts")).isEqualTo(1);
        assertThat(first.getString("error")).isEqualTo("PROVIDER_DOWN");
        assertThat(first.getDate("nextAttemptAt").toInstant())
                .isBetween(before.plusSeconds(47), Instant.now().plusSeconds(73));
        assertThat(card(acme, 1).get("status").asText()).isEqualTo("ACTIVE"); // retrying is not an error yet

        for (int i = 0; i < 5; i++) {
            makeDue(acme.tenant());
            dispatcher.dispatch(Provider.META);
        }
        Document last = outboxRow(acme.tenant(), "META", orderId);
        assertThat(last.getString("status")).isEqualTo("FAILED");
        assertThat(last.getInteger("attempts")).isEqualTo(6);
        assertThat(last.getString("error")).isEqualTo("GAVE_UP");
        assertThat(STUB.callsTo(META_EVENTS)).hasSize(6);
        JsonNode meta = card(acme, 1);
        assertThat(meta.get("status").asText()).isEqualTo("ERROR");
        assertThat(meta.get("lastError").get("code").asText()).isEqualTo("GAVE_UP");
        assertThat(meta.get("deliveries").get(0).get("status").asText()).isEqualTo("FAILED");

        makeDue(acme.tenant());
        dispatcher.dispatch(Provider.META);
        assertThat(STUB.callsTo(META_EVENTS)).hasSize(6); // a dead letter is never retried on its own
    }

    @Test
    void throttlingHonoursRetryAfter() throws Exception {
        String orderId = order(acme, consented(false, true));
        STUB.reply(META_EVENTS, new ProviderStub.Reply(429, "{}", Map.of("Retry-After", "600"), 0));
        dispatcher.dispatch(Provider.META);
        Document row = outboxRow(acme.tenant(), "META", orderId);
        assertThat(row.getString("error")).isEqualTo("RATE_LIMITED");
        assertThat(row.getDate("nextAttemptAt").toInstant()).isAfter(Instant.now().plusSeconds(590));
    }

    @Test
    void aTimeoutIsRetried() throws Exception {
        String orderId = order(acme, consented(false, true));
        STUB.reply(META_EVENTS, new ProviderStub.Reply(200, "{\"events_received\":1}", Map.of(), 1500));
        dispatcher.dispatch(Provider.META);
        Document row = outboxRow(acme.tenant(), "META", orderId);
        assertThat(row.getString("status")).isEqualTo("PENDING");
        assertThat(row.getString("error")).isEqualTo("TIMEOUT");
    }

    @Test
    void aRejectedEventFailsAtOnceAndShowsTheReason() throws Exception {
        String orderId = order(acme, consented(false, true));
        STUB.reply(META_EVENTS, ProviderStub.Reply.of(400,
                "{\"error\":{\"message\":\"Invalid parameter\",\"type\":\"OAuthException\",\"code\":100}}"));
        dispatcher.dispatch(Provider.META);

        Document row = outboxRow(acme.tenant(), "META", orderId);
        assertThat(row.getString("status")).isEqualTo("FAILED");
        assertThat(row.getInteger("attempts")).isEqualTo(1);
        assertThat(row.getString("error")).isEqualTo("REJECTED");
        JsonNode meta = card(acme, 1);
        assertThat(meta.get("status").asText()).isEqualTo("ERROR");
        assertThat(meta.get("lastError").get("message").asText()).isEqualTo("Meta rejected the event: Invalid parameter");
        assertThat(list(acme.tenant(), acme.admin()).andReturn().getResponse().getContentAsString())
                .doesNotContain(META_TOKEN);
    }

    @Test
    void aRejectedTokenAsksTheOwnerForANewOne() throws Exception {
        order(acme, consented(false, true));
        STUB.reply(META_EVENTS, ProviderStub.Reply.of(400,
                "{\"error\":{\"message\":\"Invalid OAuth access token\",\"type\":\"OAuthException\",\"code\":190}}"));
        dispatcher.dispatch(Provider.META);
        JsonNode meta = card(acme, 1);
        assertThat(meta.get("lastError").get("code").asText()).isEqualTo("AUTH_FAILED");
        assertThat(meta.get("lastError").get("message").asText())
                .isEqualTo("Meta rejected the saved secret. Create a new one and save it here.");
    }

    @Test
    void concurrentDispatchersSendEachRowOnce() throws Exception {
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            orders.add(order(acme, consented(false, true)));
        }
        STUB.reset();
        concurrently(6, () -> {
            dispatcher.dispatch(Provider.META);
            return null;
        });
        List<String> sent = new ArrayList<>();
        for (ProviderStub.Call c : STUB.callsTo(META_EVENTS)) {
            sent.add(json.readTree(c.body()).at("/data/0/event_id").asText());
        }
        assertThat(sent).hasSize(12).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(orders);
        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("status")).containsOnly("SENT");
    }

    @Test
    void anExpiredLeaseIsSentAgainAndALiveOneIsLeftAlone() throws Exception {
        String crashed = order(acme, consented(false, true));
        String busy = order(acme, consented(false, true));
        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", crashed), new Document("$set",
                new Document("status", "SENDING").append("attempts", 1).append("claimToken", "dead")
                        .append("leaseUntil", Date.from(Instant.now().minusSeconds(1)))));
        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", busy), new Document("$set",
                new Document("status", "SENDING").append("attempts", 1).append("claimToken", "alive")
                        .append("leaseUntil", Date.from(Instant.now().plus(Duration.ofMinutes(2))))));

        dispatcher.dispatch(Provider.META);

        assertThat(STUB.callsTo(META_EVENTS)).singleElement()
                .satisfies(c -> assertThat(c.body()).contains(crashed).doesNotContain(busy));
        assertThat(outboxRow(acme.tenant(), "META", crashed).getInteger("attempts")).isEqualTo(2);
        assertThat(outboxRow(acme.tenant(), "META", busy).getString("claimToken")).isEqualTo("alive");
    }

    @Test
    void rowsAreSkippedWhenConsentOrSettingsAreGone() throws Exception {
        String erased = order(acme, consented(true, true));
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(erased)),
                new Document("$unset", new Document("attribution", ""))); // what account erasure does
        saveServer(acme.tenant(), acme.admin(), "google", server(false, null, null)).andExpect(status().isOk());

        dispatcher.dispatch(Provider.META);
        dispatcher.dispatch(Provider.GOOGLE);

        assertThat(STUB.calls()).isEmpty();
        assertThat(outboxRow(acme.tenant(), "META", erased).getString("status")).isEqualTo("SKIPPED");
        assertThat(outboxRow(acme.tenant(), "META", erased).getString("error")).isEqualTo("NO_CONSENT");
        assertThat(outboxRow(acme.tenant(), "GOOGLE", erased).getString("error")).isEqualTo("NO_CONSENT");
    }

    @Test
    void serverEventsTurnedOffAfterQueueingSkipTheRow() throws Exception {
        String orderId = order(acme, consented(true, true));
        saveServer(acme.tenant(), acme.admin(), "google", server(false, null, null)).andExpect(status().isOk());
        dispatcher.dispatch(Provider.GOOGLE);
        assertThat(outboxRow(acme.tenant(), "GOOGLE", orderId).getString("error")).isEqualTo("SERVER_OFF");
        assertThat(STUB.callsTo("/mp/")).isEmpty();
    }

    @Test
    void purchasesOlderThanAProviderAcceptsAreNotSent() throws Exception {
        String orderId = order(acme, consented(true, true));
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(orderId)),
                new Document("$set", new Document("createdAt", Date.from(Instant.now().minus(Duration.ofDays(4))))));
        dispatcher.dispatch(Provider.GOOGLE);
        dispatcher.dispatch(Provider.META);
        assertThat(outboxRow(acme.tenant(), "GOOGLE", orderId).getString("error")).isEqualTo("TOO_OLD");
        assertThat(outboxRow(acme.tenant(), "META", orderId).getString("status")).isEqualTo("SENT"); // 7 days for Meta
    }

    @Test
    void aStoreWithTheFeatureOffIsLeftAlone() throws Exception {
        String orderId = order(acme, consented(true, true));
        marketing(acme.tenant(), false);
        dispatcher.dispatch(Provider.META);
        assertThat(STUB.calls()).isEmpty();
        assertThat(outboxRow(acme.tenant(), "META", orderId).getString("status")).isEqualTo("PENDING");
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='BackoffTest,ProviderBreakerTest,ConversionDispatcherLifecycleTest,ConversionDispatcherIntegrationTest'`
Expected: FAIL — compilation errors (`Backoff`, `ProviderBreaker`, `ConversionDispatcher` not found).

- [ ] **Step 3: Implement**

`Backoff.java`:

```java
package com.sixpack.marketing.application;

import java.time.Duration;
import java.util.List;

/** When to try a transient failure again (D6): fixed steps × jitter U(0.8, 1.2), Retry-After honoured, 12 h cap. */
final class Backoff {

    static final List<Duration> STEPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12));
    private static final Duration CAP = Duration.ofHours(12);

    private Backoff() {
    }

    /** @param attempts attempts made so far (≥ 1) @param random uniform in [0, 1) */
    static Duration after(int attempts, Duration retryAfter, double random) {
        Duration step = STEPS.get(Math.min(Math.max(attempts, 1), STEPS.size()) - 1);
        long wanted = Math.round(step.toMillis() * (0.8 + 0.4 * random));
        if (retryAfter != null) {
            wanted = Math.max(wanted, retryAfter.toMillis());
        }
        return Duration.ofMillis(Math.min(wanted, CAP.toMillis()));
    }
}
```

`ProviderBreaker.java`:

```java
package com.sixpack.marketing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Per provider, per instance (D8): five transient failures in a row open it for {@code openFor}; after the pause the
 * first failure opens it again at once, a success closes it. While open the dispatcher claims nothing for the provider.
 */
final class ProviderBreaker {

    static final int THRESHOLD = 5;

    private final Duration openFor;
    private final Supplier<Instant> now;
    private int failures;
    private Instant openUntil = Instant.MIN;

    ProviderBreaker(Duration openFor, Supplier<Instant> now) {
        this.openFor = openFor;
        this.now = now;
    }

    synchronized boolean isOpen() {
        return now.get().isBefore(openUntil);
    }

    synchronized void success() {
        failures = 0;
    }

    synchronized void failure() {
        if (++failures >= THRESHOLD) {
            openUntil = now.get().plus(openFor);
            failures = THRESHOLD - 1;
        }
    }
}
```

`ConversionDispatcher.java`:

```java
package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.LastError;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.MarketingDestination.Credentials;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.order.PaidOrders;
import com.sixpack.order.PaidOrders.PaidOrder;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.StoreDirectory;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.tenant.TenantRef;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends queued purchases (Consistency & failure D3–D11). One thread per provider (bulkhead); each pass walks the
 * stores, claims one row at a time with a lease, builds the payload from the order as it is now and calls the
 * provider. Safe to run on several replicas at once.
 */
@Service
public class ConversionDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConversionDispatcher.class);
    static final int MAX_ATTEMPTS = 6;
    static final int PER_STORE_PASS = 50;
    static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(20);

    private final StoreDirectory stores;
    private final TenantFeatures features;
    private final Outbox outbox;
    private final IntegrationRepository integrations;
    private final PaidOrders orders;
    private final SecretSealer secrets;
    private final StoreConfig store;
    private final MarketingProperties props;
    private final MeterRegistry meters;
    private final Map<Provider, MarketingDestination> destinations = new EnumMap<>(Provider.class);
    private final Map<Provider, ExecutorService> lanes = new EnumMap<>(Provider.class);
    private final Map<Provider, AtomicBoolean> busy = new EnumMap<>(Provider.class);
    private final Map<Provider, ProviderBreaker> breakers = new EnumMap<>(Provider.class);
    private final Map<Provider, AtomicLong> dueAge = new EnumMap<>(Provider.class);
    private volatile boolean running;

    ConversionDispatcher(StoreDirectory stores, TenantFeatures features, Outbox outbox,
                         IntegrationRepository integrations, PaidOrders orders, SecretSealer secrets,
                         List<MarketingDestination> destinations, StoreConfig store, MarketingProperties props,
                         MeterRegistry meters) {
        this.stores = stores;
        this.features = features;
        this.outbox = outbox;
        this.integrations = integrations;
        this.orders = orders;
        this.secrets = secrets;
        this.store = store;
        this.props = props;
        this.meters = meters;
        for (MarketingDestination d : destinations) {
            Provider p = d.provider();
            this.destinations.put(p, d);
            lanes.put(p, Executors.newSingleThreadExecutor(Thread.ofVirtual().name("marketing-" + p.key()).factory()));
            busy.put(p, new AtomicBoolean());
            breakers.put(p, new ProviderBreaker(props.breakerOpenFor(), Instant::now));
            AtomicLong age = new AtomicLong();
            dueAge.put(p, age);
            Gauge.builder("marketing.outbox.due.age", age, AtomicLong::get).baseUnit("seconds")
                    .description("How long the oldest due conversion waited before it was claimed")
                    .tag("provider", p.key()).register(meters);
        }
    }

    @Scheduled(initialDelayString = "${app.marketing.dispatch-interval:PT30S}",
            fixedDelayString = "${app.marketing.dispatch-interval:PT30S}")
    void scheduled() {
        for (Provider p : destinations.keySet()) {
            if (!running || !busy.get(p).compareAndSet(false, true)) {
                continue; // stopping, or the previous pass for p is still going
            }
            try {
                lanes.get(p).execute(() -> {
                    try {
                        dispatch(p);
                    } finally {
                        busy.get(p).set(false);
                    }
                });
            } catch (RejectedExecutionException e) {
                busy.get(p).set(false); // shutting down
            }
        }
    }

    /** One pass over every store for one provider. Public for tests; concurrent calls are safe (D3). */
    public void dispatch(Provider provider) {
        if (!running || !secrets.available()) {
            return;
        }
        long oldest = 0;
        for (TenantRef s : stores.stores()) {
            if (!running || breakers.get(provider).isOpen()) {
                break;
            }
            try {
                oldest = Math.max(oldest, TenantContext.callAs(s, () -> drainStore(provider)));
            } catch (RuntimeException e) {
                log.warn("Marketing dispatch failed for store {}", s.slug(), e);
            }
        }
        dueAge.get(provider).set(oldest);
    }

    /** @return seconds the oldest row claimed in this pass had been due */
    private long drainStore(Provider provider) {
        if (!features.enabled(Feature.MARKETING)) {
            return 0;
        }
        long oldest = 0;
        for (int n = 0; n < PER_STORE_PASS && running && !breakers.get(provider).isOpen(); n++) {
            Instant now = Instant.now();
            Optional<OutboxRow> row = outbox.claim(provider, now, LEASE);
            if (row.isEmpty()) {
                break;
            }
            oldest = Math.max(oldest, Duration.between(row.get().nextAttemptAt(), now).toSeconds());
            deliver(row.get());
        }
        return oldest;
    }

    private void deliver(OutboxRow row) {
        Provider p = row.provider();
        Optional<PaidOrder> order = orders.find(row.orderId());
        Integration integration = integrations.findAll().stream().filter(i -> i.provider() == p).findFirst()
                .orElse(null);
        DeliveryCode skip = skipReason(p, order, integration);
        if (skip != null) {
            finish(row, OutboxRow.Status.SKIPPED, skip, null);
            count(p, "skipped");
            return;
        }
        String secret;
        try {
            secret = secrets.open(integration.server().secret(),
                    SecretSealer.aad(TenantContext.require().id(), p, ServerSettings.SECRET_FIELD));
        } catch (SecretSealer.UnreadableSecretException e) {
            fail(row, new DeliveryException(DeliveryCode.SECRET_UNREADABLE, null, null));
            return;
        }
        try {
            destinations.get(p).send(new Credentials(integration.serverId(), secret), Conversions.of(order.get(), p,
                    store.currency(), props.piiConsentVersion(), props.callingCode()));
            finish(row, OutboxRow.Status.SENT, null, null);
            integrations.recordSuccess(p, Instant.now());
            breakers.get(p).success();
            count(p, "sent");
        } catch (DeliveryException e) {
            if (e.code().retryable()) {
                breakers.get(p).failure();
                retryOrGiveUp(row, e);
            } else {
                fail(row, e);
            }
        } catch (RuntimeException e) {
            // A bug of ours (payload mapping): retry rather than lose the row; never log the exception's text.
            log.warn("Unexpected {} sending {} conversion {}", e.getClass().getSimpleName(), p.key(), row.eventId());
            retryOrGiveUp(row, new DeliveryException(DeliveryCode.PROVIDER_DOWN, null, null));
        }
    }

    private static DeliveryCode skipReason(Provider p, Optional<PaidOrder> order, Integration integration) {
        if (order.isEmpty()) {
            return DeliveryCode.ORDER_GONE;
        }
        if (order.get().attribution() == null
                || !ConversionRecorder.allowed(p, order.get().attribution().consent())) {
            return DeliveryCode.NO_CONSENT; // also after account erasure removed the attribution
        }
        if (integration == null || !integration.server().enabled() || !integration.server().hasSecret()
                || integration.serverId() == null) {
            return DeliveryCode.SERVER_OFF;
        }
        Duration maxAge = p == Provider.META ? Duration.ofDays(7) : Duration.ofHours(72);
        return order.get().placedAt() == null || order.get().placedAt().isBefore(Instant.now().minus(maxAge))
                ? DeliveryCode.TOO_OLD : null;
    }

    private void retryOrGiveUp(OutboxRow row, DeliveryException e) {
        if (row.attempts() >= MAX_ATTEMPTS) {
            fail(row, new DeliveryException(DeliveryCode.GAVE_UP, null, null));
            return;
        }
        Duration wait = Backoff.after(row.attempts(), e.retryAfter(), ThreadLocalRandom.current().nextDouble());
        finish(row, OutboxRow.Status.PENDING, e.code(), Instant.now().plus(wait));
        count(row.provider(), "retry");
    }

    /** Dead letter: the row stays FAILED in the log and the card shows why (D9). */
    private void fail(OutboxRow row, DeliveryException e) {
        finish(row, OutboxRow.Status.FAILED, e.code(), null);
        integrations.recordFailure(row.provider(), new LastError(Instant.now(), e.code().name(),
                e.ownerMessage(row.provider())));
        count(row.provider(), "failed");
    }

    private void finish(OutboxRow row, OutboxRow.Status status, DeliveryCode error, Instant next) {
        if (!outbox.finish(row, status, error, next, Instant.now())) {
            log.warn("Lost the claim on {} conversion {}: its lease ran out and another dispatcher took it",
                    row.provider().key(), row.eventId());
        }
    }

    private void count(Provider p, String outcome) {
        meters.counter("marketing.events", Tags.of("provider", p.key(), "outcome", outcome)
                .and(TenantContext.metricTag())).increment();
    }

    @Override
    public void start() {
        running = true;
    }

    /** Stops claiming, then waits up to 20 s for the sends in flight; a row left SENDING is recovered by its lease. */
    @Override
    public void stop() {
        running = false;
        lanes.values().forEach(ExecutorService::shutdown);
        long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
        for (ExecutorService lane : lanes.values()) {
            try {
                if (!lane.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    log.warn("A marketing send was still running at shutdown; its lease expires within 2 minutes");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='BackoffTest,ProviderBreakerTest,ConversionDispatcherLifecycleTest,ConversionDispatcherIntegrationTest,ConversionRecorderIntegrationTest,ModularityTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/application/Backoff.java \
        backend/src/main/java/com/sixpack/marketing/application/ProviderBreaker.java \
        backend/src/main/java/com/sixpack/marketing/application/ConversionDispatcher.java \
        backend/src/test/java/com/sixpack/marketing/application/BackoffTest.java \
        backend/src/test/java/com/sixpack/marketing/application/ProviderBreakerTest.java \
        backend/src/test/java/com/sixpack/marketing/application/ConversionDispatcherLifecycleTest.java \
        backend/src/test/java/com/sixpack/it/ConversionDispatcherIntegrationTest.java
git commit -m "feat(marketing): dispatch queued purchases with leases, backoff, breaker and graceful stop

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A12 (phase plan 3.3, admin): test-event button and delivery log endpoints

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/application/FixedWindowLimiter.java`, `backend/src/main/java/com/sixpack/marketing/web/MarketingServerController.java`
- Modify: `marketing/application/ServerEventsService.java`, `audit/AuditAction.java`
- Test: `marketing/application/FixedWindowLimiterTest.java`, `it/MarketingServerEventsIntegrationTest.java`

- [ ] **Step 1: Write the failing tests**

`FixedWindowLimiterTest.java`:

```java
package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixedWindowLimiterTest {

    @Test
    void allowsTheLimitPerKeyPerMinute() {
        FixedWindowLimiter limiter = new FixedWindowLimiter(10);
        long t = 1_793_440_800_000L; // a minute boundary
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("t_a", t + i)).isTrue();
        }
        assertThat(limiter.tryAcquire("t_a", t + 59_999)).isFalse();
        assertThat(limiter.tryAcquire("t_b", t)).isTrue();
        assertThat(limiter.tryAcquire("t_a", t + 60_000)).isTrue();
    }
}
```

`MarketingServerEventsIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** POST /{provider}/test and GET /{provider}/deliveries. */
class MarketingServerEventsIntegrationTest extends MarketingServerIntegrationTest {

    static final String TOKEN = "fake-meta-token-aaaaaaaa";

    Store acme;

    @BeforeEach
    void store() throws Exception {
        acme = configuredStore("acme", "123456789012345", TOKEN, "G-AB12CD3", "fake-ga4-secret-aaaaaaa");
        STUB.reset();
    }

    private ResultActions test(Tenant t, String token, String provider) throws Exception {
        return mvc.perform(post(BASE + "/" + provider + "/test").with(host(hostOf(t)))
                .header("Authorization", bearer(token)));
    }

    private ResultActions deliveries(Tenant t, String token, String provider, String page) throws Exception {
        var request = get(BASE + "/" + provider + "/deliveries").with(host(hostOf(t)))
                .header("Authorization", bearer(token));
        return mvc.perform(page == null ? request : request.param("page", page));
    }

    @Test
    void aMetaTestSendsTheTestCodeAndReportsTheVerdict() throws Exception {
        String response = test(acme.tenant(), acme.admin(), "meta").andExpect(status().isOk())
                .andExpect(jsonPath("$.test.ok").value(true))
                .andExpect(jsonPath("$.test.code").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(TOKEN);
        JsonNode sent = json.readTree(STUB.callsTo(META_EVENTS).get(0).body());
        assertThat(sent.get("test_event_code").asText()).isEqualTo("TEST123");
        assertThat(sent.get("access_token").asText()).isEqualTo(TOKEN);
        assertThat(outboxRows(acme.tenant())).isEmpty(); // a test never goes through the queue
        Document audit = auditEntries("INTEGRATION_TESTED").get(0);
        assertThat(audit.getString("outcome")).isEqualTo("SUCCESS");
        assertThat(audit.getString("entityId")).isEqualTo("meta");
    }

    @Test
    void aRefusedTestIsReportedWithoutChangingTheCard() throws Exception {
        STUB.reply(META_EVENTS, ProviderStub.Reply.of(400,
                "{\"error\":{\"message\":\"Invalid OAuth access token\",\"code\":190}}"));
        test(acme.tenant(), acme.admin(), "meta").andExpect(status().isOk())
                .andExpect(jsonPath("$.test.ok").value(false))
                .andExpect(jsonPath("$.test.code").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.test.message")
                        .value("Meta rejected the saved secret. Create a new one and save it here."));
        assertThat(auditEntries("INTEGRATION_TESTED").get(0).getString("outcome")).isEqualTo("FAILURE");
        list(acme.tenant(), acme.admin()).andExpect(jsonPath("$.integrations[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.integrations[1].lastError").isEmpty());
    }

    @Test
    void aGoogleTestUsesTheValidationServer() throws Exception {
        STUB.reply("/debug/", ProviderStub.Reply.of(200, "{\"validationMessages\":[{\"fieldPath\":\"events\","
                + "\"description\":\"Event at index: [0] has invalid name\",\"validationCode\":\"NAME_INVALID\"}]}"));
        test(acme.tenant(), acme.admin(), "google").andExpect(status().isOk())
                .andExpect(jsonPath("$.test.ok").value(false))
                .andExpect(jsonPath("$.test.code").value("REJECTED"))
                .andExpect(jsonPath("$.test.message").value("Google found a problem: Event at index: [0] has invalid name"));
        assertThat(STUB.callsTo("/debug/mp/collect")).hasSize(1);
        assertThat(STUB.callsTo("/mp/collect")).isEmpty();
    }

    @Test
    void aTestNeedsTheSecretAndForMetaATestCode() throws Exception {
        saveServer(acme.tenant(), acme.admin(), "meta", server(true, null, null)).andExpect(status().isOk());
        test(acme.tenant(), acme.admin(), "meta").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.testEventCode: set a test event code from Events Manager first"));

        Tenant bare = marketing(createTenant("bare", "Bare", "shop.bare.test"), true);
        String admin = adminTokenFor(bare);
        test(bare, admin, "google").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server: save the GA4 measurement ID first"));
        save(bare, admin, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isOk());
        test(bare, admin, "google").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.secret: save the secret first"));
        assertThat(STUB.calls()).isEmpty();
    }

    @Test
    void theEleventhTestInAMinuteIsRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            test(acme.tenant(), acme.admin(), "google").andExpect(status().isOk());
        }
        test(acme.tenant(), acme.admin(), "google").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many test events, please wait a minute"));
    }

    @Test
    void anotherStoresCopiedSecretCannotBeRead() throws Exception {
        Tenant globex = marketing(createTenant("globex", "Globex", "shop.globex.test"), true);
        String admin = adminTokenFor(globex);
        save(globex, admin, "meta", browser("pixelId", "999999999999999")).andExpect(status().isOk());
        Document acmeSecrets = rows(acme.tenant()).stream().filter(r -> "META".equals(r.getString("provider")))
                .findFirst().orElseThrow().get("secrets", Document.class);
        mongo.getCollection("marketing_integrations").updateOne(new Document("tenantId", globex.id()),
                new Document("$set", new Document("secrets", acmeSecrets)
                        .append("server", new Document("enabled", true).append("testEventCode", "TEST123"))));

        test(globex, admin, "meta").andExpect(status().isOk())
                .andExpect(jsonPath("$.test.ok").value(false))
                .andExpect(jsonPath("$.test.code").value("SECRET_UNREADABLE"));
        assertThat(STUB.calls()).isEmpty();
    }

    @Test
    void theDeliveryLogIsPagedNewestFirstAndBoundToTheStore() throws Exception {
        for (int i = 0; i < 25; i++) {
            mongo.getCollection("marketing_outbox").insertOne(new Document("tenantId", acme.tenant().id())
                    .append("provider", "META").append("eventId", "o" + i).append("eventName", "purchase")
                    .append("orderId", "o" + i).append("orderNumber", "ORD-" + i).append("status", "PENDING")
                    .append("attempts", 1).append("error", "TIMEOUT")
                    .append("nextAttemptAt", Date.from(Instant.now().plusSeconds(60)))
                    .append("createdAt", Date.from(Instant.parse("2026-10-31T10:00:00Z").plusSeconds(i)))
                    .append("updatedAt", Date.from(Instant.now())));
        }
        JsonNode first = body(deliveries(acme.tenant(), acme.admin(), "meta", null).andExpect(status().isOk())
                .andReturn());
        assertThat(first.get("page").asInt()).isEqualTo(1);
        assertThat(first.get("totalPage").asInt()).isEqualTo(2);
        assertThat(first.get("deliveries")).hasSize(20);
        JsonNode newest = first.get("deliveries").get(0);
        assertThat(newest.get("eventId").asText()).isEqualTo("o24");
        assertThat(newest.get("nextAttemptAt").isNull()).isFalse();
        assertThat(newest.get("error").get("code").asText()).isEqualTo("TIMEOUT");
        assertThat(newest.get("error").get("message").asText()).isEqualTo("Meta did not answer in time. We will try again.");

        deliveries(acme.tenant(), acme.admin(), "meta", "2").andExpect(jsonPath("$.deliveries.length()").value(5));
        deliveries(acme.tenant(), acme.admin(), "meta", "9").andExpect(jsonPath("$.deliveries.length()").value(0));
        deliveries(acme.tenant(), acme.admin(), "meta", "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page: must be a positive number"));
        deliveries(acme.tenant(), acme.admin(), "meta", "x").andExpect(status().isBadRequest());

        Tenant globex = marketing(createTenant("globex", "Globex", "shop.globex.test"), true);
        deliveries(globex, adminTokenFor(globex), "meta", null).andExpect(jsonPath("$.deliveries.length()").value(0));
    }

    @Test
    void bothEndpointsAreNotFoundWithTheFeatureOffOrAnUnknownProvider() throws Exception {
        test(acme.tenant(), acme.admin(), "tiktok").andExpect(status().isNotFound());
        deliveries(acme.tenant(), acme.admin(), "tiktok", null).andExpect(status().isNotFound());
        marketing(acme.tenant(), false);
        test(acme.tenant(), acme.admin(), "meta").andExpect(status().isNotFound());
        deliveries(acme.tenant(), acme.admin(), "meta", null).andExpect(status().isNotFound());
        assertThat(STUB.calls()).isEmpty();
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='FixedWindowLimiterTest,MarketingServerEventsIntegrationTest'`
Expected: FAIL — `FixedWindowLimiter` does not compile; the IT gets 404/405 for `/test` and `/deliveries`.

- [ ] **Step 3: Implement**

`AuditAction.java` — on its own line after the integration actions:

```java
    INTEGRATION_UPDATED, INTEGRATION_DISCONNECTED,
    /** An owner sent a test event to Meta or Google from Admin → Marketing. */
    INTEGRATION_TESTED,
```

`FixedWindowLimiter.java`:

```java
package com.sixpack.marketing.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed one-minute window per key.
 * shortcut: in memory per instance, so N replicas allow N × the limit; move to a shared store if that matters.
 */
final class FixedWindowLimiter {

    private record Window(long minute, AtomicInteger count) {
    }

    private final int perMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    FixedWindowLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    boolean tryAcquire(String key, long nowMillis) {
        long minute = nowMillis / 60_000;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != minute
                ? new Window(minute, new AtomicInteger()) : old);
        if (windows.size() > 10_000) {
            windows.values().removeIf(x -> x.minute() != minute);
        }
        return w.count().incrementAndGet() <= perMinute;
    }
}
```

`ServerEventsService.java` (replace the file):

```java
package com.sixpack.marketing.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.MarketingDestination.Credentials;
import com.sixpack.marketing.domain.MarketingDestination.TestVerdict;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.TenantContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The owner's view of server events: the delivery log and the test-event button. */
@Service
public class ServerEventsService {

    static final int CARD_DELIVERIES = 10;
    static final int PAGE_SIZE = 20;
    static final int TESTS_PER_MINUTE = 10;

    private final Outbox outbox;
    private final IntegrationService integrations;
    private final SecretSealer secrets;
    private final Map<Provider, MarketingDestination> destinations = new EnumMap<>(Provider.class);
    private final StoreConfig store;
    private final AuditTrail audit;
    private final FixedWindowLimiter limiter = new FixedWindowLimiter(TESTS_PER_MINUTE);

    ServerEventsService(Outbox outbox, IntegrationService integrations, SecretSealer secrets,
                        List<MarketingDestination> destinations, StoreConfig store, AuditTrail audit) {
        this.outbox = outbox;
        this.integrations = integrations;
        this.secrets = secrets;
        destinations.forEach(d -> this.destinations.put(d.provider(), d));
        this.store = store;
        this.audit = audit;
    }

    public List<OutboxRow> recent(Provider provider) {
        return outbox.recent(provider, 1, CARD_DELIVERIES).rows();
    }

    public Outbox.Page page(Provider provider, int page) {
        return outbox.recent(provider, page, PAGE_SIZE);
    }

    /** One synthetic purchase to the provider's test channel, synchronously: the owner waits for the verdict. */
    public TestVerdict test(Provider provider) {
        if (!secrets.available()) {
            throw ApiException.badRequest("server: not available on this platform yet");
        }
        Integration i = integrations.find(provider);
        if (i.serverId() == null) {
            throw ApiException.badRequest("server: save the " + IntegrationService.idName(provider) + " first");
        }
        if (!i.server().hasSecret()) {
            throw ApiException.badRequest("server.secret: save the secret first");
        }
        if (provider == Provider.META && i.server().testEventCode() == null) {
            throw ApiException.badRequest("server.testEventCode: set a test event code from Events Manager first");
        }
        String tenantId = TenantContext.require().id();
        if (!limiter.tryAcquire(tenantId, System.currentTimeMillis())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many test events, please wait a minute");
        }
        TestVerdict verdict;
        try {
            String secret = secrets.open(i.server().secret(),
                    SecretSealer.aad(tenantId, provider, ServerSettings.SECRET_FIELD));
            verdict = destinations.get(provider).test(new Credentials(i.serverId(), secret),
                    i.server().testEventCode(), store.currency());
        } catch (SecretSealer.UnreadableSecretException e) {
            verdict = new TestVerdict(false, DeliveryCode.SECRET_UNREADABLE.name(),
                    DeliveryCode.SECRET_UNREADABLE.message(provider));
        }
        AuditEvent event = AuditEvent.of(AuditAction.INTEGRATION_TESTED).on(MarketingAudit.ENTITY, provider.key());
        audit.record(verdict.ok() ? event : event.failed());
        return verdict;
    }
}
```

`MarketingServerController.java`:

```java
package com.sixpack.marketing.web;

import com.sixpack.marketing.application.ServerEventsService;
import com.sixpack.marketing.domain.MarketingDestination.TestVerdict;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.web.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Server events on Admin → Marketing: test event and delivery log. Same guards as MarketingAdminController. */
@RestController
@RequestMapping("/api/v1/admin/marketing/{provider}")
class MarketingServerController {

    private final ServerEventsService events;
    private final TenantFeatures features;

    MarketingServerController(ServerEventsService events, TenantFeatures features) {
        this.events = events;
        this.features = features;
    }

    @PostMapping("/test")
    Map<String, Object> test(@PathVariable String provider) {
        TestVerdict verdict = events.test(provider(provider));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", verdict.ok());
        body.put("code", verdict.code());
        body.put("message", verdict.message());
        return ApiResponse.of("test", body);
    }

    @GetMapping("/deliveries")
    Map<String, Object> deliveries(@PathVariable String provider, @RequestParam(defaultValue = "1") String page) {
        Provider p = provider(provider);
        int n;
        try {
            n = Integer.parseInt(page.strip());
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n < 1) {
            throw ApiException.badRequest("page: must be a positive number");
        }
        Outbox.Page result = events.page(p, n);
        return ApiResponse.of("deliveries", result.rows().stream().map(DeliveryView::from).toList(), "page", n,
                "totalPage", result.totalPage());
    }

    private Provider provider(String key) {
        if (!features.enabled(Feature.MARKETING)) {
            throw ApiException.notFound("Not found");
        }
        return Provider.fromKey(key).orElseThrow(() -> ApiException.notFound("Not found"));
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='FixedWindowLimiterTest,MarketingServerEventsIntegrationTest,MarketingAdminIntegrationTest,AuditApiIntegrationTest,ModularityTests'`
Expected: PASS (the audit API accepts `integration_tested` as a filter because it parses `AuditAction`).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing backend/src/main/java/com/sixpack/audit/AuditAction.java \
        backend/src/test/java/com/sixpack/marketing/application/FixedWindowLimiterTest.java \
        backend/src/test/java/com/sixpack/it/MarketingServerEventsIntegrationTest.java
git commit -m "feat(marketing): test-event button and paged delivery log for server events

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A13: Alerts — failed deliveries and a stuck outbox

**Files:**
- Modify: `infra/observability/k8s/alerts.yaml`

- [ ] **Step 1: Add the rules** — after `SixpackAuditWriteFailing`, same group and style:

```yaml
        - alert: SixpackMarketingDeliveriesFailing
          # Purchases Meta or Google refused, or that we gave up on after 6 attempts. Each store's Admin → Marketing
          # card shows the reason (usually an expired token). Not a page: the store's sales are unaffected.
          expr: |
            sum by (namespace, provider) (increase(marketing_events_total{job="backend-service", outcome="failed"}[15m]))
              /
            sum by (namespace, provider) (increase(marketing_events_total{job="backend-service", outcome=~"sent|failed"}[15m]))
              > 0.05
          for: 15m
          labels:
            severity: warning
          annotations:
            summary: "{{ $value | humanizePercentage }} of {{ $labels.provider }} conversions failed in 15m ({{ $labels.namespace }})"
            runbook: "Grafana → marketing_events_total by tenant; open that store's Marketing card for lastError. Re-queue FAILED rows only after the owner fixed the secret (docs/foundations/test-rollout.md, Runbooks)."

        - alert: SixpackMarketingOutboxStuck
          # The oldest due conversion waited more than 30 min: the provider is down (breaker open), or dispatch is stuck.
          expr: max by (namespace, provider) (marketing_outbox_due_age_seconds{job="backend-service"}) > 1800
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "{{ $labels.provider }} conversions have waited {{ $value | humanizeDuration }} to be sent ({{ $labels.namespace }})"
            runbook: "Check the provider's status page, then backend logs for 'Marketing dispatch failed' and 'Lost the claim'."
```

- [ ] **Step 2: Validate** (same check as CI; needs Docker and `yq`):

Run (repo root): `mkdir -p "$TMPDIR/rules" && yq '{"groups": .spec.groups}' infra/observability/k8s/alerts.yaml > "$TMPDIR/rules/rules.yaml" && docker run --rm -v "$TMPDIR/rules:/r:ro" --entrypoint promtool prom/prometheus:v3.5.0 check rules /r/rules.yaml`
Expected: `SUCCESS: N rules found` (N = previous count + 2). Without `yq` locally, rely on the CI `manifests` job and say so.

- [ ] **Step 3: Commit**

```bash
git add infra/observability/k8s/alerts.yaml
git commit -m "feat(infra): alert when marketing conversions fail or wait too long

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A14: Foundations docs — ADR-029, Consistency & failure, contracts, requirements, consent, tests

**Files:**
- Modify: `docs/foundations/architecture.md`, `contracts.md`, `requirements.md`, `compliance-consent.md`, `test-rollout.md`, `k8s/README.md`, `k8s/scripts/seal-backend-secrets.sh` (comment only)

- [ ] **Step 1: `architecture.md`**
  - Module table, `marketing` row: replace "Phase 1 is browser tags only; feed, server conversions, OAuth and reporting follow" with "Phase 3 adds server-side conversions: sealed owner secrets (ADR-029), the per-store `marketing_outbox`, `ConversionRecorder`/`ConversionSweep`/`ConversionDispatcher` and the Meta CAPI / GA4 MP adapters; reads `order.PaidOrders`".
  - Observability → Metrics: add `marketing.events{provider,outcome}` (outcome `sent|retry|failed|skipped`, with `tenant`), `marketing.outbox.due.age{provider}` (seconds), `marketing.record.failed`. Alerts list: add "marketing conversions failing > 5 % for 15 min, oldest due conversion > 30 min (warning)".
  - New section after "Observability plan", heading `### Consistency & failure (distributed-systems checklist)`: one line "Single backend replica in prod today (ADR-008); these hold for N." then the table D1–D14 from this plan's "Distributed-systems decisions", each row ending with its test class/method name.
  - Key decisions, newest first (above ADR-028):

```markdown
- 2026-10-31: ADR-029 **Owner-set marketing secrets are encrypted in MongoDB.** Store owners paste a Meta
  Conversions API access token and a GA4 Measurement Protocol API secret on Admin → Marketing; phase 4 adds OAuth
  refresh tokens that rotate on their own. The T+ rule for tenant secrets (a sealed secret per store, referenced by
  name like `TENANT_SECRET_<REF>`) needs a platform admin and a redeploy per change, so it does not fit. `SecretBox`
  (marketing infra) seals each value with AES-256-GCM, a random 12-byte IV and a 128-bit tag, with
  `tenantId|PROVIDER|field` as associated data, so a ciphertext copied to another store's or provider's row does not
  decrypt. Keys come from `MARKETING_KEYS` (`k1:<base64 of 32 bytes>,k0:…`, newest first), delivered like every
  platform secret (Sealed Secret in k8s, `.env` locally, where `.env.example` carries an all-zero dev key that logs a
  warning). New values use the first key and record its id; rotating means putting a new key first and keeping the
  old one until every store has saved again (a re-encrypt job is deferred). Without a key the server-side capability
  is unavailable and the API says so (fail closed); browser tags and the feed are unaffected. Secrets are write-only:
  responses carry `hasSecret`, the audit diff marks `secret` as a redacted change, the tenant export drops the
  `secrets` field and the outbox, adapters never log a `RestClientException` (GA4's secret is a query parameter) and
  use a client without trace observation. Rejected: plaintext in Mongo (one database leak exposes every store's ad
  accounts), a cloud KMS call per use (new dependency and latency), a sealed secret per store (redeploy per change).
  Server conversions themselves go through the per-store outbox described under "Consistency & failure". Still active.
```

- [ ] **Step 2: `contracts.md`**
  - Data model, after the `marketing_integrations` row (keep the feed branch's rows if it added some here): extend that row with "`server{enabled, testEventCode?}`, `secrets{server{keyId, iv, ciphertext}}` (AES-256-GCM, ADR-029; never returned or exported), `lastError?{at, code, message}`, `lastSuccessAt?`" and add:
    `| marketing_outbox (new, marketing phase 3) | _id, tenantId, provider (META|GOOGLE), eventId (= order _id), eventName ("purchase"), orderId, orderNumber, status PENDING|SENDING|SENT|FAILED|SKIPPED, attempts, nextAttemptAt, leaseUntil?, claimToken?, error? (delivery code), createdAt (TTL 30 d), updatedAt; unique (tenantId, provider, eventId). No personal data: the payload is built from the order when sent. Not exported; purged with the store |`
    and on `orders`: "`attribution?` `{utmSource, utmMedium, utmCampaign, utmContent, utmTerm, gclid, fbclid, fbp, fbc, gaClientId, landingPath, userAgent, sourceUrl, consent{analytics, marketing, version, at}}` (all optional but `consent`; set at checkout, erased with the account, never returned)".
  - Order → `POST /checkout` request: append "`attribution?` (see Marketing server events; bad values are dropped, never a 400)".
  - Audit action list: add `INTEGRATION_TESTED` after `INTEGRATION_DISCONNECTED`.
  - New subsection right after the Marketing call-budget table, heading `### Marketing server events — phase 3 (ADR-029)`: copy this plan's **Fixed API contract** (card shape, PUT rules and messages, `/test`, `/deliveries`, checkout `attribution`, delivery statuses and codes, call budgets). Replace the line "Later phases (feed, test event, delivery log, OAuth) add endpoints listed in the design spec §7." with "Server events: see the next section. OAuth comes in phase 4."
- [ ] **Step 3: `requirements.md`**
  - FR13: add `INTEGRATION_TESTED` to the marketing audit list.
  - New line after FR-marketing: "- [ ] FR-marketing-server (phase 3, ADR-029): A store owner may also send purchases from the server: Meta Conversions API (access token, optional test event code) and GA4 Measurement Protocol (API secret). Secrets are encrypted, write-only and never leave the server except to the provider. A paid order of a shopper who allowed marketing (Meta) or analytics (GA4) cookies is sent once per provider with the order id as event id, so the browser and server events count once; Meta also gets the hashed email and phone of shoppers who consented under the 2026-10-31 cookie notice; GA4 gets no contact details. Transient provider errors are retried for about 15 hours, refusals stop at once and the card shows the reason. The card shows the last 10 deliveries, older ones page in, and a test button sends one test event (10 per minute). Checkout is never slowed or failed by marketing."
- [ ] **Step 4: `compliance-consent.md`**
  - §2 "Advertising partners": add "With marketing consent the store's server also sends each purchase to Meta (Conversions API) with the shopper's email and phone hashed (SHA-256) and the browser identifiers `_fbp`/`_fbc` and user agent; with analytics consent it sends the purchase to GA4 (Measurement Protocol) with the `_ga` client id and no contact details. Hashing is applied only to consents given under cookie-notice version 2026-10-31 or later, the version that discloses it. No IP address is sent."
  - §7 retention table: add rows "Order attribution (`orders.attribution`: campaign tags, click ids, cookie ids, user agent, consent snapshot) | until the account is deleted (erased with it) | Erased with the account" and "`marketing_outbox` (ids and delivery state only) | 30 days (TTL) | Deleted automatically".
- [ ] **Step 5: `test-rollout.md`** — add one sentence to the integration list: "Marketing server events (phase 3): `SecretBoxTest`, `MarketingOutboxIntegrationTest`, `MarketingServerSettingsIntegrationTest`, `CheckoutAttributionIntegrationTest`, `ConversionRecorderIntegrationTest`, `ConversionDispatcherIntegrationTest` (provider stub: retries, give-up, concurrent dispatchers, expired leases, cross-store credentials), `MarketingServerEventsIntegrationTest`, adapter tests against `MockRestServiceServer`." and a runbook entry "**A store's conversions stopped:** card status and `lastError` on Admin → Marketing → delivery log → ask the owner for a new token → re-queue its FAILED rows of the last 72 h if wanted: `db.marketing_outbox.updateMany({tenantId, provider, status:'FAILED', createdAt:{$gte: <now-72h>}}, {$set:{status:'PENDING', attempts:0, nextAttemptAt: new Date()}})`."
- [ ] **Step 6: `k8s/README.md` and the seal script comment** — list `MARKETING_KEYS=k1:<openssl rand -base64 32>   # optional: enables server-side marketing conversions (ADR-029); keep old keys listed when rotating` next to `CONSENT_IP_SALT`, and add `MARKETING_KEYS` to the "extra KEY=value lines" example in `seal-backend-secrets.sh` (comment only; it stays optional).
- [ ] **Step 7: Commit**

```bash
git add docs/foundations k8s/README.md k8s/scripts/seal-backend-secrets.sh
git commit -m "docs: ADR-029, consistency and failure table, server-event contracts and consent

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A15: Full verification (Part A)

The only consent-related backend value is `app.marketing.pii-consent-version: "2026-10-31"` (Task A1); it must equal the cookie notice date Part B sets (Task B7). `LEGAL_PRIVACY_VERSION` is **not** bumped here (owner decision, see Changes).

- [ ] **Step 1:** Run: `cd backend && mvn -q verify` — Expected: BUILD SUCCESS, every test green (Docker running).
- [ ] **Step 2:** `git status --short` → clean; `git diff --stat feat/marketing--server-events -- ecommerce-frontend` → empty. Report the commit list; ask before merging into `feat/marketing--server-events`.

---

# Part B — Frontend

Worktree `../6Pack-marketing-events-fe`, branch `feat/marketing--server-events--frontend`. All commands from `ecommerce-frontend/`. **Depends only on the Fixed API contract above**: every test stubs `fetch`; nothing here needs the Part A backend. Global Constraints from the Phase 1 frontend plan apply (no new dependencies, `src/test/dom.tsx` for components, `.tsx` files export only components, storage access in `try/catch`, ADR-013 tokens/mixins only).

### Task B0: Baseline

- [ ] **Step 1:** Create the worktree (Global Constraints), `cd ../6Pack-marketing-events-fe/ecommerce-frontend && ([ -d node_modules ] || npm ci)`.
- [ ] **Step 2:** Run: `npm test && npm run lint && npm run build` — Expected: all green (record the test count). If not, stop and report.

---

### Task B1: API slice — card types, server save, test event, delivery pages; audit action

**Files:**
- Modify: `src/redux/api/marketingAPI.ts`, `src/redux/api/auditAPI.ts`, `src/redux/api/auditAPI.test.ts`

No separate test for the slice: B3's component tests drive every endpoint through a real RTK Query store and count each `fetch`.

- [ ] **Step 1: Write the failing test** — in `auditAPI.test.ts`, extend the existing assertion:

```ts
    expect(AUDIT_ACTIONS).toEqual(expect.arrayContaining(["INTEGRATION_UPDATED", "INTEGRATION_DISCONNECTED", "INTEGRATION_TESTED"]));
```

- [ ] **Step 2:** Run: `npm test -- src/redux/api/auditAPI.test.ts` — Expected: FAIL (`INTEGRATION_TESTED` missing).
- [ ] **Step 3: Implement**

`auditAPI.ts` — a new line after `"INTEGRATION_UPDATED", "INTEGRATION_DISCONNECTED",` (own line: the feed branch adds its action nearby):

```ts
  "INTEGRATION_TESTED",
```

`marketingAPI.ts` — replace the type block and add three endpoints (existing endpoints unchanged):

```ts
export type IntegrationStatus = "NOT_CONNECTED" | "ACTIVE" | "ERROR";
export type ServerSettings = { enabled: boolean; hasSecret: boolean; testEventCode: string | null; available: boolean };
export type DeliveryStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "SKIPPED";
export type Delivery = {
  eventId: string; orderNumber: string | null; eventName: string; status: DeliveryStatus; attempts: number;
  createdAt: string; updatedAt: string; nextAttemptAt: string | null; error: { code: string; message: string } | null;
};
export type LastError = { at: string; code: string; message: string };

/** One provider card. The server-event keys are optional so a phase-1 server's cards still type-check. */
export type Integration = {
  provider: Provider;
  status: IntegrationStatus;
  browser: Record<string, string | null>;
  updatedAt: string | null;
  server?: ServerSettings;
  lastError?: LastError | null;
  lastSuccessAt?: string | null;
  deliveries?: Delivery[];
};

/** `secret` is write-only: omit it to keep the saved one. */
export type ServerChange = { enabled: boolean; secret?: string; testEventCode?: string | null };
export type TestVerdict = { ok: boolean; code: string | null; message: string };
export type DeliveriesResponse = { success: boolean; deliveries: Delivery[]; page: number; totalPage: number };
```

inside `endpoints`:

```ts
    saveServerSettings: builder.mutation<IntegrationResponse, { provider: Provider; server: ServerChange }>({
      query: ({ provider, server }) => ({ url: provider, method: "PUT", body: { server } }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          dispatch(replaceCard((await queryFulfilled).data.integration));
        } catch {
          // the section shows the error from the mutation result
        }
      },
    }),
    sendTestEvent: builder.mutation<{ success: boolean; test: TestVerdict }, Provider>({
      query: (provider) => ({ url: `${provider}/test`, method: "POST" }),
    }),
    deliveries: builder.query<DeliveriesResponse, { provider: Provider; page: number }>({
      query: ({ provider, page }) => `${provider}/deliveries?page=${page}`,
    }),
```

and export `useSaveServerSettingsMutation, useSendTestEventMutation, useLazyDeliveriesQuery` with the existing hooks.

- [ ] **Step 4:** Run: `npm test -- src/redux/api/auditAPI.test.ts src/pages/admin/apps/marketing.test.tsx` — Expected: PASS (phase-1 page tests untouched).
- [ ] **Step 5: Commit** — `git add src/redux/api && git commit -m "feat(marketing): API slice for server events and delivery log" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task B2: `serverEventsForm.ts` — the section's pure helpers

**Files:**
- Create: `src/pages/admin/apps/serverEventsForm.ts`, `src/pages/admin/apps/serverEventsForm.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from "vitest";
import { DELIVERY_LABEL, SERVER, serverDraftFrom, serverFieldError, toServerChange, validateServer } from "./serverEventsForm";

const SECRET = "fake-meta-token-aaaaaaaa";

describe("serverEventsForm", () => {
  it("starts from the saved settings and never pre-fills the secret", () => {
    expect(serverDraftFrom(undefined)).toEqual({ enabled: false, secret: "", testEventCode: "" });
    expect(serverDraftFrom({ provider: "meta", status: "ACTIVE", browser: {}, updatedAt: null,
      server: { enabled: true, hasSecret: true, testEventCode: "TEST123", available: true } }))
      .toEqual({ enabled: true, secret: "", testEventCode: "TEST123" });
  });

  it("mirrors the server's checks", () => {
    expect(validateServer({ enabled: false, secret: "short", testEventCode: "" }, SERVER.meta, false).secret).toMatch(/16–512/);
    expect(validateServer({ enabled: true, secret: "", testEventCode: "" }, SERVER.meta, false).secret).toMatch(/Paste the secret/);
    expect(validateServer({ enabled: true, secret: "", testEventCode: "" }, SERVER.meta, true)).toEqual({});
    expect(validateServer({ enabled: false, secret: "", testEventCode: "TE ST" }, SERVER.meta, true).testEventCode).toBeDefined();
    expect(validateServer({ enabled: false, secret: "", testEventCode: "TE ST" }, SERVER.google, true)).toEqual({});
  });

  it("sends the secret only when typed and the test code only for Meta", () => {
    expect(toServerChange({ enabled: true, secret: ` ${SECRET} `, testEventCode: " TEST123 " }, SERVER.meta))
      .toEqual({ enabled: true, secret: SECRET, testEventCode: "TEST123" });
    expect(toServerChange({ enabled: false, secret: "", testEventCode: "" }, SERVER.meta)).toEqual({ enabled: false, testEventCode: null });
    expect(toServerChange({ enabled: true, secret: "", testEventCode: "X" }, SERVER.google)).toEqual({ enabled: true });
  });

  it("maps the server's messages to a field or the top of the section", () => {
    expect(serverFieldError("server.secret: use 16-512 letters, digits, _ or -"))
      .toEqual({ key: "secret", text: "use 16-512 letters, digits, _ or -" });
    expect(serverFieldError("server.testEventCode: use 1-32 letters or digits").key).toBe("testEventCode");
    expect(serverFieldError("server: save the Meta Pixel ID first")).toEqual({ text: "Save the Meta Pixel ID first" });
    expect(serverFieldError("Try again later")).toEqual({ text: "Try again later" });
  });

  it("labels every delivery status", () => {
    expect(Object.keys(DELIVERY_LABEL).sort()).toEqual(["FAILED", "PENDING", "SENDING", "SENT", "SKIPPED"]);
  });
});
```

- [ ] **Step 2:** Run: `npm test -- src/pages/admin/apps/serverEventsForm.test.ts` — Expected: FAIL (module not found).
- [ ] **Step 3: Implement** `serverEventsForm.ts`:

```ts
import type { DeliveryStatus, Integration, Provider, ServerChange } from "../../../redux/api/marketingAPI";

/** The server's patterns (contracts.md → Marketing server events). */
export const SECRET_PATTERN = /^[A-Za-z0-9_-]{16,512}$/;
export const TEST_CODE_PATTERN = /^[A-Za-z0-9]{1,32}$/;

export type ServerDef = { idKey: string; idLabel: string; secretLabel: string; testCode: boolean; consent: string; intro: string };
export const SERVER: Record<Provider, ServerDef> = {
  google: {
    idKey: "ga4MeasurementId", idLabel: "GA4 measurement ID", secretLabel: "Measurement Protocol API secret",
    testCode: false, consent: "analytics",
    intro: "Also send each paid order to GA4 from your server, so sales count even when a browser blocks the tag. GA4 keeps one purchase per order.",
  },
  meta: {
    idKey: "pixelId", idLabel: "Meta Pixel ID", secretLabel: "Conversions API access token",
    testCode: true, consent: "marketing",
    intro: "Also send each paid order to Meta from your server, so sales count even when a browser blocks the Pixel. Meta counts a purchase once when both arrive.",
  },
};

export type ServerDraft = { enabled: boolean; secret: string; testEventCode: string };

export const serverDraftFrom = (integration?: Integration): ServerDraft => ({
  enabled: integration?.server?.enabled ?? false,
  secret: "",
  testEventCode: integration?.server?.testEventCode ?? "",
});

export const validateServer = (draft: ServerDraft, def: ServerDef, hasSecret: boolean): Record<string, string> => {
  const errors: Record<string, string> = {};
  const secret = draft.secret.trim();
  if (secret && !SECRET_PATTERN.test(secret)) errors.secret = "Paste the whole secret: 16–512 letters, digits, _ or -.";
  else if (draft.enabled && !secret && !hasSecret) errors.secret = "Paste the secret to turn on server events.";
  const code = draft.testEventCode.trim();
  if (def.testCode && code && !TEST_CODE_PATTERN.test(code)) {
    errors.testEventCode = "Use the code from Events Manager → Test events: letters and digits only.";
  }
  return errors;
};

export const toServerChange = (draft: ServerDraft, def: ServerDef): ServerChange => {
  const change: ServerChange = { enabled: draft.enabled };
  if (draft.secret.trim()) change.secret = draft.secret.trim();
  if (def.testCode) change.testEventCode = draft.testEventCode.trim() || null;
  return change;
};

/** "server.<field>: <reason>" goes under that field; "server: <reason>" and anything else at the top. */
export const serverFieldError = (message: string): { key?: "secret" | "testEventCode"; text: string } => {
  const m = /^server\.(secret|testEventCode): (.+)$/.exec(message);
  if (m) return { key: m[1] as "secret" | "testEventCode", text: m[2] };
  const text = message.replace(/^server: /, "");
  return { text: text.charAt(0).toUpperCase() + text.slice(1) };
};

export const DELIVERY_LABEL: Record<DeliveryStatus, string> = {
  PENDING: "Waiting to send", SENDING: "Sending", SENT: "Sent", FAILED: "Failed", SKIPPED: "Not sent",
};
```

- [ ] **Step 4:** Run the test again — Expected: PASS.
- [ ] **Step 5: Commit** — `git add src/pages/admin/apps/serverEventsForm* && git commit -m "feat(marketing): server-event form helpers" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task B3: `ServerEventsSection` — settings, test button, delivery log (and B4: mount it)

**Files:**
- Create: `src/pages/admin/apps/ServerEventsSection.tsx`, `src/pages/admin/apps/ServerEventsSection.test.tsx`, `src/styles/admin-styles/_server-events.scss`
- Modify: `src/styles/app.scss` (one `@import`), `src/pages/admin/apps/marketing.tsx` (one import + one line, Task B4 step)

The section renders **inside** the phase-1 card `<form>`, so it has no `<form>` of its own: its buttons are `type="button"` and Enter in its inputs is swallowed.

- [ ] **Step 1: Write the failing test** `ServerEventsSection.test.tsx` (same harness as `marketing.test.tsx`, kept local so the feed branch's edits to that file don't collide):

```tsx
// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { configureStore } from "@reduxjs/toolkit";
import { Provider } from "react-redux";
import { MemoryRouter } from "react-router-dom";
import { marketingAPI } from "../../../redux/api/marketingAPI";
import { button, cleanup, click, field, render, settle, type } from "../../../test/dom";
import Marketing from "./marketing";

vi.mock("../../../redux/config", () => ({ server: "http://shop.test" }));

const OFF = { enabled: false, hasSecret: false, testEventCode: null, available: true };
const delivery = (i: number) => ({ eventId: `o${i}`, orderNumber: `ORD-${i}`, eventName: "purchase", status: "SENT",
  attempts: 1, createdAt: "2026-10-31T10:00:00Z", updatedAt: "2026-10-31T10:00:30Z", nextAttemptAt: null, error: null });
const GOOGLE = { provider: "google", status: "NOT_CONNECTED", updatedAt: null, server: OFF, lastError: null,
  lastSuccessAt: null, deliveries: [],
  browser: { ga4MeasurementId: null, adsConversionId: null, adsPurchaseLabel: null, gtmContainerId: null } };
const META = { provider: "meta", status: "ACTIVE", updatedAt: "2026-10-31T10:00:00Z", browser: { pixelId: "123456789012345" },
  server: OFF, lastError: null, lastSuccessAt: null, deliveries: [] as unknown[] };
const SECRET = "fake-meta-token-aaaaaaaa";

type Reply = { status: number; body: unknown };
let handlers: Record<string, () => Reply>;
let calls: { key: string; search: string; body?: unknown }[];
let cards: unknown[];

beforeEach(() => {
  calls = [];
  cards = [GOOGLE, META];
  handlers = { "GET /api/v1/admin/marketing": () => ({ status: 200, body: { success: true, integrations: cards } }) };
  vi.stubGlobal("fetch", vi.fn(async (req: Request) => {
    const url = new URL(req.url);
    const key = `${req.method} ${url.pathname}`;
    calls.push({ key, search: url.search, body: req.method === "PUT" ? await req.clone().json() : undefined });
    const r = handlers[key]?.() ?? { status: 404, body: { success: false, message: "Not found" } };
    return new Response(JSON.stringify(r.body), { status: r.status, headers: { "Content-Type": "application/json" } });
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const show = async () => {
  const store = configureStore({
    reducer: { [marketingAPI.reducerPath]: marketingAPI.reducer },
    middleware: (d) => d().concat(marketingAPI.middleware),
  });
  await render(<Provider store={store}><MemoryRouter><Marketing /></MemoryRouter></Provider>);
  await settle();
};
const card = (title: string) => [...document.querySelectorAll<HTMLElement>("form.marketing-card")]
  .find((f) => f.querySelector("h2")?.textContent === title)!;
const meta = () => card("Meta (Facebook and Instagram)").querySelector<HTMLElement>("fieldset.server-events")!;
const google = () => card("Google").querySelector<HTMLElement>("fieldset.server-events")!;

describe("server events on Admin → Marketing", () => {
  it("still loads the page with one request and hides the section for phase-1 cards", async () => {
    cards = [{ ...GOOGLE, server: undefined }, { ...META, server: undefined }];
    await show();
    expect(calls.map((c) => c.key)).toEqual(["GET /api/v1/admin/marketing"]);
    expect(document.querySelector("fieldset.server-events")).toBeNull();
  });

  it("explains when the platform cannot store secrets, and asks for the ID first", async () => {
    cards = [GOOGLE, { ...META, server: { ...OFF, available: false } }];
    await show();
    expect(meta().textContent).toContain("isn't available");
    expect(meta().querySelector("input")).toBeNull();
    expect(google().textContent).toContain("Save your GA4 measurement ID first");
  });

  it("needs a secret to switch on and sends nothing until then", async () => {
    await show();
    await click(field("Also send purchases from the server", meta()));
    await click(button("Save server settings", meta()));
    expect(meta().textContent).toContain("Paste the secret");
    expect(calls).toHaveLength(1);
  });

  it("saves with one PUT of only the server settings, then forgets the typed secret", async () => {
    handlers["PUT /api/v1/admin/marketing/meta"] = () => ({ status: 200, body: { success: true,
      integration: { ...META, server: { enabled: true, hasSecret: true, testEventCode: "TEST123", available: true } } } });
    await show();
    await click(field("Also send purchases from the server", meta()));
    await type(field("Conversions API access token", meta()), SECRET);
    await type(field("Test event code", meta()), "TEST123");
    await click(button("Save server settings", meta()));
    await settle();
    expect(calls.slice(1)).toEqual([{ key: "PUT /api/v1/admin/marketing/meta", search: "",
      body: { server: { enabled: true, secret: SECRET, testEventCode: "TEST123" } } }]);
    const secret = field("Conversions API access token", meta());
    expect(secret.value).toBe("");
    expect(secret.type).toBe("password");
    expect(secret.placeholder).toContain("Saved");
  });

  it("shows the server's field error under the secret", async () => {
    handlers["PUT /api/v1/admin/marketing/meta"] = () => ({ status: 400,
      body: { success: false, message: "server.secret: use 16-512 letters, digits, _ or -" } });
    await show();
    await type(field("Conversions API access token", meta()), SECRET);
    await click(button("Save server settings", meta()));
    await settle();
    const secret = field("Conversions API access token", meta());
    expect(secret.getAttribute("aria-invalid")).toBe("true");
    expect(document.getElementById(secret.getAttribute("aria-describedby")!)?.textContent).toContain("16-512");
  });

  it("sends a test event with one POST and shows the verdict", async () => {
    cards = [GOOGLE, { ...META, server: { enabled: true, hasSecret: true, testEventCode: "TEST123", available: true } }];
    handlers["POST /api/v1/admin/marketing/meta/test"] = () => ({ status: 200, body: { success: true,
      test: { ok: false, code: "AUTH_FAILED", message: "Meta rejected the saved secret. Create a new one and save it here." } } });
    await show();
    await click(button("Send test event", meta()));
    await settle();
    expect(calls.map((c) => c.key)).toEqual(["GET /api/v1/admin/marketing", "POST /api/v1/admin/marketing/meta/test"]);
    expect(meta().querySelector('[role="status"]')?.textContent).toContain("Meta rejected the saved secret");
  });

  it("shows why a failing card is in error", async () => {
    cards = [GOOGLE, { ...META, status: "ERROR", server: { ...OFF, enabled: true, hasSecret: true },
      lastError: { at: "2026-10-31T10:05:00Z", code: "GAVE_UP", message: "Meta could not be reached after 6 attempts, so this purchase was not sent." } }];
    await show();
    expect(card("Meta (Facebook and Instagram)").querySelector(".badge")?.textContent).toBe("Error");
    expect(meta().querySelector('[role="alert"]')?.textContent).toContain("after 6 attempts");
  });

  it("lists recent deliveries and loads more with one request", async () => {
    cards = [GOOGLE, { ...META, deliveries: Array.from({ length: 10 }, (_, i) => delivery(i)) }];
    handlers["GET /api/v1/admin/marketing/meta/deliveries"] = () => ({ status: 200, body: { success: true,
      deliveries: Array.from({ length: 20 }, (_, i) => delivery(i)), page: 1, totalPage: 2 } });
    await show();
    expect(meta().querySelectorAll("tbody tr")).toHaveLength(10);
    expect(meta().textContent).toContain("Sent");
    await click(button("Show more", meta()));
    await settle();
    expect(calls.at(-1)).toMatchObject({ key: "GET /api/v1/admin/marketing/meta/deliveries", search: "?page=1" });
    expect(meta().querySelectorAll("tbody tr")).toHaveLength(20);
    expect(button("Show more", meta())).toBeTruthy(); // page 2 still to come
  });
});
```

- [ ] **Step 2:** Run: `npm test -- src/pages/admin/apps/ServerEventsSection.test.tsx` — Expected: FAIL (no `fieldset.server-events`; the phase-1-only test passes).
- [ ] **Step 3: Implement**

`ServerEventsSection.tsx`:

```tsx
import { InputHTMLAttributes, KeyboardEvent, useEffect, useMemo, useState } from "react";
import toast from "react-hot-toast";
import { LuAlertCircle } from "react-icons/lu";
import { errorMessage } from "../../../redux/api/baseQuery";
import {
  Delivery, Integration, Provider, TestVerdict, useLazyDeliveriesQuery, useSaveServerSettingsMutation, useSendTestEventMutation,
} from "../../../redux/api/marketingAPI";
import { formatDateTime } from "../../../utils/features";
import { DELIVERY_LABEL, SERVER, ServerDraft, serverDraftFrom, serverFieldError, toServerChange, validateServer } from "./serverEventsForm";

// The section sits inside the browser-tag <form>: Enter must not submit that form.
const noSubmit = (e: KeyboardEvent) => {
  if (e.key === "Enter") e.preventDefault();
};

/** Server-side conversions for one provider card (marketing phase 3). Renders nothing for a phase-1 server. */
const ServerEventsSection = ({ provider, integration }: { provider: Provider; integration?: Integration }) => {
  const def = SERVER[provider];
  const saved = useMemo(() => serverDraftFrom(integration), [integration]);
  const [draft, setDraft] = useState<ServerDraft>(saved);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [top, setTop] = useState<string | null>(null);
  const [verdict, setVerdict] = useState<TestVerdict | null>(null);
  const [more, setMore] = useState<{ rows: Delivery[]; page: number; totalPage: number } | null>(null);
  const [save, { isLoading: saving }] = useSaveServerSettingsMutation();
  const [sendTest, { isLoading: testing }] = useSendTestEventMutation();
  const [loadPage, { isFetching: loading }] = useLazyDeliveriesQuery();

  useEffect(() => setDraft(saved), [saved]);

  const server = integration?.server;
  if (!integration || !server) return null;

  const id = (key: string) => `${provider}-server-${key}`;
  const hasId = !!integration.browser[def.idKey];
  const dirty = draft.enabled !== saved.enabled || draft.secret !== "" || draft.testEventCode !== saved.testEventCode;
  const canTest = server.hasSecret && (!def.testCode || !!server.testEventCode);
  const rows = more?.rows ?? integration.deliveries ?? [];
  const canLoadMore = more ? more.page < more.totalPage : rows.length >= 10;

  const submit = async () => {
    const found = validateServer(draft, def, server.hasSecret);
    setErrors(found);
    setTop(null);
    if (Object.keys(found).length) return;
    const res = await save({ provider, server: toServerChange(draft, def) });
    if ("error" in res) {
      const e = serverFieldError(errorMessage(res.error));
      return e.key ? setErrors({ [e.key]: e.text }) : setTop(e.text);
    }
    toast.success("Server settings saved");
  };

  const test = async () => {
    setVerdict(null);
    setTop(null);
    const res = await sendTest(provider);
    if ("error" in res) return setTop(errorMessage(res.error));
    setVerdict(res.data.test);
  };

  const loadMore = async () => {
    const page = more ? more.page + 1 : 1;
    const res = await loadPage({ provider, page });
    if (res.data) setMore({ rows: page === 1 ? res.data.deliveries : [...rows, ...res.data.deliveries], page, totalPage: res.data.totalPage });
    else if (res.error) setTop(errorMessage(res.error));
  };

  const input = (key: "secret" | "testEventCode", label: string, extra: InputHTMLAttributes<HTMLInputElement>) => (
    <label htmlFor={id(key)}>
      {label}
      <input id={id(key)} value={draft[key]} autoComplete="off" spellCheck={false} onKeyDown={noSubmit}
        aria-invalid={!!errors[key]} aria-describedby={errors[key] ? id(`${key}-error`) : undefined}
        onChange={(e) => setDraft((d) => ({ ...d, [key]: e.target.value }))} {...extra} />
      {errors[key] && <span id={id(`${key}-error`)} className="field-error"><LuAlertCircle aria-hidden /> {errors[key]}</span>}
    </label>
  );

  return (
    <fieldset className="span-2 server-events" aria-labelledby={id("title")}>
      <h3 id={id("title")}>Server events</h3>
      {!server.available ? (
        <p className="store-settings__note">Sending purchases from the server isn't available on this platform yet.</p>
      ) : !hasId ? (
        <p className="store-settings__note">Save your {def.idLabel} first. Then you can also send purchases from your server.</p>
      ) : (
        <>
          <p className="store-settings__hint">{def.intro}</p>
          {integration.status === "ERROR" && integration.lastError && (
            <p className="field-error" role="alert">
              <LuAlertCircle aria-hidden /> {integration.lastError.message} ({formatDateTime(integration.lastError.at)})
            </p>
          )}
          {top && <p className="field-error" role="alert"><LuAlertCircle aria-hidden /> {top}</p>}
          <label className="checkbox" htmlFor={id("enabled")}>
            <input id={id("enabled")} type="checkbox" checked={draft.enabled}
              onChange={(e) => setDraft((d) => ({ ...d, enabled: e.target.checked }))} />
            Also send purchases from the server
          </label>
          {input("secret", def.secretLabel, { type: "password", maxLength: 512,
            placeholder: server.hasSecret ? "Saved. Paste a new one to replace it" : "" })}
          {def.testCode && input("testEventCode", "Test event code (optional)", { maxLength: 32, placeholder: "TEST12345" })}
          <div className="store-settings__actions">
            <button type="button" className="btn btn--primary" disabled={saving || !dirty} onClick={submit}>
              {saving ? "Saving…" : "Save server settings"}
            </button>
            <button type="button" className="btn" disabled={testing || !canTest} onClick={test}>
              {testing ? "Sending…" : "Send test event"}
            </button>
            {integration.lastSuccessAt && <span className="store-settings__note">Last sent {formatDateTime(integration.lastSuccessAt)}</span>}
          </div>
          {verdict && <p role="status" className={verdict.ok ? "store-settings__note" : "field-error"}>{verdict.message}</p>}
          <h4>Recent purchases sent</h4>
          {rows.length === 0 ? (
            <p className="store-settings__note">Nothing sent yet. Paid orders from shoppers who allowed {def.consent} cookies appear here.</p>
          ) : (
            <table className="server-events__log">
              <thead><tr><th scope="col">Order</th><th scope="col">Status</th><th scope="col">Tries</th><th scope="col">Queued</th><th scope="col">Note</th></tr></thead>
              <tbody>
                {rows.map((d) => (
                  <tr key={d.eventId}>
                    <td>{d.orderNumber ?? d.eventId}</td><td>{DELIVERY_LABEL[d.status]}</td><td>{d.attempts}</td>
                    <td>{formatDateTime(d.createdAt)}</td><td>{d.error?.message ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {canLoadMore && (
            <button type="button" className="btn" disabled={loading} onClick={loadMore}>{loading ? "Loading…" : "Show more"}</button>
          )}
        </>
      )}
    </fieldset>
  );
};

export default ServerEventsSection;
```

`_server-events.scss` (tokens and mixins only; `@import "admin-styles/server-events";` after `admin-ui` in `app.scss`):

```scss
// ---------- marketing server events (pages/admin/apps/ServerEventsSection.tsx) ----------
.badge--error { background: var(--danger-subtle); color: var(--danger); }

.server-events {
  display: grid;
  gap: $spacing-sm;
  border: none;
  border-top: 1px solid $color-border;
  margin: 0;
  padding: $spacing-md 0 0;

  h3 { @include text(h5); color: $color-text-primary; }
  h4 { @include text(caption); font-weight: 600; color: $color-text-secondary; }

  &__log {
    width: 100%;
    border-collapse: collapse;
    @include text(caption);

    th, td { padding: 0.375rem 0.5rem; text-align: left; border-bottom: 1px solid $color-border; }
    td:last-child { color: $color-text-secondary; }
  }
}
```

(If `$color-border` or `text(h5)` does not exist, use the nearest token already used in `_admin-ui.scss`; check with `grep -n "color-border\|text(h" src/styles/_tokens.scss src/styles/admin-styles/_admin-ui.scss`.)

- [ ] **Step 4 (Task B4): Mount** — in `marketing.tsx` add `import ServerEventsSection from "./ServerEventsSection";` and, as the last child of the card `<form>` (just before `</form>`):

```tsx
      <ServerEventsSection provider={def.provider} integration={integration} />
```

- [ ] **Step 5:** Run: `npm test -- src/pages/admin/apps && npm run lint` — Expected: PASS (both the new suite and the unchanged `marketing.test.tsx`).
- [ ] **Step 6: Commit** — `git add src/pages/admin/apps src/styles && git commit -m "feat(marketing): server events section with test button and delivery log" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task B5: `attribution.ts` — consent-gated first touch and the checkout payload

**Files:**
- Create: `src/utils/attribution.ts`, `src/utils/attribution.test.ts`
- Modify: `src/main.tsx` (call `captureAttribution()` once, after the `data-surface` line)

- [ ] **Step 1: Write the failing test**

```ts
// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

type Consent = typeof import("./consent");
type Attribution = typeof import("./attribution");
let consent: Consent;
let attribution: Attribution;

const LANDING = { pathname: "/product/lamp", search: `?utm_source=google&utm_campaign=${"d".repeat(200)}&gclid=Cj0-abc&fbclid=IwAR-xyz` };
const clearCookies = () => document.cookie.split(";").map((c) => c.split("=")[0].trim()).filter(Boolean)
  .forEach((n) => { document.cookie = `${n}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`; });

beforeEach(async () => {
  localStorage.clear();
  sessionStorage.clear();
  clearCookies();
  vi.restoreAllMocks();
  vi.resetModules(); // module state: the remembered landing URL and the consent subscription
  consent = await import("./consent");
  attribution = await import("./attribution");
});

const choose = (analytics: boolean, marketing: boolean) => consent.saveConsent({ functional: false, analytics, marketing });

describe("attribution", () => {
  it("keeps nothing and sends nothing before the shopper chooses", () => {
    attribution.captureAttribution(LANDING);
    expect(sessionStorage.getItem(attribution.ATTRIBUTION_KEY)).toBeNull();
    expect(attribution.attributionForCheckout()).toBeUndefined();
  });

  it("with marketing consent keeps campaign tags and click ids, capped, and reads the Meta cookies at checkout", () => {
    choose(false, true);
    attribution.captureAttribution(LANDING);
    document.cookie = "_fbp=fb.1.1730000000000.1098115397; path=/";
    document.cookie = "_ga=GA1.1.1234567890.1730000000; path=/";
    const a = attribution.attributionForCheckout(1730000000000)!;
    expect(a.utmSource).toBe("google");
    expect(a.utmCampaign).toHaveLength(150);
    expect(a.gclid).toBe("Cj0-abc");
    expect(a.fbp).toBe("fb.1.1730000000000.1098115397");
    expect(a.fbc).toBe("fb.1.1730000000000.IwAR-xyz"); // made from fbclid when the _fbc cookie is missing
    expect(a.gaClientId).toBeUndefined();
    expect(a.landingPath).toBe("/product/lamp");
    expect(a.consent).toEqual({ analytics: false, marketing: true, version: consent.CONSENT_VERSION });
  });

  it("with analytics only sends the landing path and the GA client id", () => {
    choose(true, false);
    attribution.captureAttribution(LANDING);
    document.cookie = "_ga=GA1.1.1234567890.1730000000; path=/";
    expect(attribution.attributionForCheckout()).toEqual({ landingPath: "/product/lamp", gaClientId: "1234567890.1730000000",
      consent: { analytics: true, marketing: false, version: consent.CONSENT_VERSION } });
  });

  it("captures the landing URL when consent comes later on the same page", () => {
    attribution.captureAttribution(LANDING);
    choose(true, true);
    expect(attribution.attributionForCheckout()?.utmSource).toBe("google");
  });

  it("keeps the first touch of the tab session", async () => {
    choose(true, true);
    attribution.captureAttribution(LANDING);
    vi.resetModules(); // a reload landing from another campaign
    attribution = await import("./attribution");
    attribution.captureAttribution({ pathname: "/", search: "?utm_source=meta" });
    expect(attribution.attributionForCheckout()?.utmSource).toBe("google");
  });

  it("forgets campaign data when marketing consent is withdrawn", () => {
    choose(true, true);
    attribution.captureAttribution(LANDING);
    choose(false, false);
    expect(sessionStorage.getItem(attribution.ATTRIBUTION_KEY)).toBeNull();
    expect(attribution.attributionForCheckout()).toEqual({ consent: { analytics: false, marketing: false, version: consent.CONSENT_VERSION } });
  });

  it("ignores admin landings and survives blocked storage", () => {
    choose(true, true);
    attribution.captureAttribution({ pathname: "/admin/app/marketing", search: "?utm_source=x" });
    expect(attribution.attributionForCheckout()?.utmSource).toBeUndefined();
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    expect(() => attribution.captureAttribution(LANDING)).not.toThrow();
  });
});
```

- [ ] **Step 2:** Run: `npm test -- src/utils/attribution.test.ts` — Expected: FAIL (module not found).
- [ ] **Step 3: Implement** `attribution.ts`:

```ts
import { CONSENT_VERSION, currentChoice, onConsentChange } from "./consent";
import { surfaceFor } from "./routes";

/**
 * Marketing first touch for server-side conversions (marketing spec §5.5, phase 3). The landing URL is remembered
 * on load; its campaign tags and click ids are kept in sessionStorage only once marketing cookies are allowed, the
 * landing path once analytics or marketing is. Cookies (_fbp, _fbc, _ga) are read at checkout under the consent in
 * force then. Values are capped here and checked again by the server.
 */
export type CheckoutAttribution = {
  utmSource?: string; utmMedium?: string; utmCampaign?: string; utmContent?: string; utmTerm?: string;
  gclid?: string; fbclid?: string; fbp?: string; fbc?: string; gaClientId?: string; landingPath?: string;
  consent: { analytics: boolean; marketing: boolean; version: string };
};
type Saved = Pick<CheckoutAttribution, "utmSource" | "utmMedium" | "utmCampaign" | "utmContent" | "utmTerm" | "gclid" | "fbclid" | "landingPath">;

export const ATTRIBUTION_KEY = "attribution";
const UTM = { utmSource: "utm_source", utmMedium: "utm_medium", utmCampaign: "utm_campaign", utmContent: "utm_content", utmTerm: "utm_term" } as const;
const MARKETING_FIELDS: (keyof Saved)[] = ["utmSource", "utmMedium", "utmCampaign", "utmContent", "utmTerm", "gclid", "fbclid"];

let landing: { path: string; search: string } | null = null;
let subscribed = false;

const cap = (value: string | null | undefined, max: number) => value?.trim().slice(0, max) || undefined;
const defined = <T extends object>(o: T): T =>
  Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as T;

const read = (): Saved => {
  try {
    const v: unknown = JSON.parse(sessionStorage.getItem(ATTRIBUTION_KEY) ?? "{}");
    return v && typeof v === "object" ? (v as Saved) : {};
  } catch {
    return {};
  }
};

const write = (v: Saved | null) => {
  try {
    if (v && Object.keys(v).length) sessionStorage.setItem(ATTRIBUTION_KEY, JSON.stringify(v));
    else sessionStorage.removeItem(ATTRIBUTION_KEY);
  } catch {
    // storage blocked: checkout then goes without campaign data
  }
};

const cookie = (name: string) => {
  const hit = document.cookie.split(";").map((c) => c.trim()).find((c) => c.startsWith(`${name}=`));
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : undefined;
};

/** Keeps what the current consent allows; values already kept in this tab session win (first touch). */
const store = () => {
  const choice = currentChoice();
  if (!choice) return;
  if (!choice.marketing && !choice.analytics) return write(null);
  const saved = read();
  if (!choice.marketing) MARKETING_FIELDS.forEach((k) => delete saved[k]);
  const next: Saved = {};
  if (landing) {
    const p = new URLSearchParams(landing.search);
    next.landingPath = cap(landing.path, 512);
    if (choice.marketing) {
      for (const [key, param] of Object.entries(UTM)) next[key as keyof typeof UTM] = cap(p.get(param), 150);
      next.gclid = cap(p.get("gclid"), 512);
      next.fbclid = cap(p.get("fbclid"), 512);
    }
  }
  write(defined({ ...next, ...saved }));
};

/** Call once when the app starts. Admin pages are never a landing. */
export const captureAttribution = (loc: { pathname: string; search: string } = window.location) => {
  if (!landing && surfaceFor(loc.pathname) !== "admin") landing = { path: loc.pathname, search: loc.search };
  store();
  if (!subscribed) {
    subscribed = true;
    onConsentChange(store);
  }
};

/** The checkout's `attribution`, or undefined before the shopper made a cookie choice. */
export const attributionForCheckout = (now = Date.now()): CheckoutAttribution | undefined => {
  const choice = currentChoice();
  if (!choice) return undefined;
  const saved = choice.marketing || choice.analytics ? read() : {};
  const out: CheckoutAttribution = { consent: { analytics: choice.analytics, marketing: choice.marketing, version: CONSENT_VERSION } };
  if (choice.marketing || choice.analytics) out.landingPath = saved.landingPath;
  if (choice.marketing) {
    MARKETING_FIELDS.forEach((k) => { out[k] = saved[k]; });
    out.fbp = cap(cookie("_fbp"), 512);
    out.fbc = cap(cookie("_fbc"), 512) ?? (saved.fbclid ? `fb.1.${now}.${saved.fbclid}` : undefined);
  }
  if (choice.analytics) out.gaClientId = /^GA\d\.\d\.(\d+\.\d+)$/.exec(cookie("_ga") ?? "")?.[1];
  return defined(out);
};
```

`main.tsx` — `import { captureAttribution } from './utils/attribution.ts'` and, right after the `data-surface` line: `captureAttribution()`.

- [ ] **Step 4:** Run: `npm test -- src/utils/attribution.test.ts src/utils/consent.test.ts` — Expected: PASS.
- [ ] **Step 5: Commit** — `git add src/utils/attribution* src/main.tsx && git commit -m "feat(marketing): consent-gated first-touch attribution for checkout" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task B6: Send `attribution` with checkout

**Files:**
- Modify: `src/types/api-types.ts`, `src/pages/shipping.tsx`

Covered by B5's payload tests and `tsc`; there is no shipping-page test harness (as in Phase 1).

- [ ] **Step 1:** `api-types.ts`: `import type { CheckoutAttribution } from "../utils/attribution";` and add `attribution?: CheckoutAttribution;` to `CheckoutRequest`.
- [ ] **Step 2:** `shipping.tsx`: `import { attributionForCheckout } from "../utils/attribution";` and in the `checkout({...})` call add `attribution: attributionForCheckout(),` after `coupon`.
- [ ] **Step 3:** Run: `npm run build && npm run lint` — Expected: PASS.
- [ ] **Step 4: Commit** — `git add src/types/api-types.ts src/pages/shipping.tsx && git commit -m "feat(checkout): send the attribution snapshot with checkout" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task B7: Help-centre article, privacy and cookie text, consent version (moved from Part A)

**Files:**
- Modify: `src/help/adminGuides.ts`, `src/legal/policies.ts`, `src/legal/policies.test.ts`, `src/utils/consent.ts`

- [ ] **Step 1: Write the failing test** — add to `policies.test.ts` → `describe("cookie and privacy notices")`:

```ts
  it("say that purchases are sent from the server, with Meta getting hashed contact details", () => {
    const privacy = JSON.stringify(POLICIES.privacy);
    expect(privacy).toContain("hashed");
    expect(privacy).not.toContain("We don't send them your name, email, phone or address");
    expect(JSON.stringify(POLICIES.cookies)).toContain("from its own server");
    expect(POLICIES.cookies.updated).toBe("2026-10-31"); // = backend app.marketing.pii-consent-version
  });
```

- [ ] **Step 2:** Run: `npm test -- src/legal/policies.test.ts` — Expected: FAIL (`expected … to contain 'hashed'`).
- [ ] **Step 3: Implement**
  - `policies.ts`, privacy `updated: "2026-10-31"`; replace the advertising-partners bullet's last sentence ("We don't send them your name, email, phone or address.") with: `"If you allow marketing cookies, the shop's server also sends each purchase to Meta with your email address and phone number in scrambled (hashed) form, so Meta can match it to your account. Google gets no email or phone. We never send your name or address."`
  - Cookie notice `updated: "2026-10-31"`; append to the Analytics bullet `" With your consent the shop also reports each purchase to Google Analytics from its own server, using the same _ga identifier."` and to the Marketing bullet `" With your consent the shop also tells Meta about each purchase from its own server, with your email and phone hashed."`
  - `consent.ts`: `export const CONSENT_VERSION = "2026-10-31";` (the banner asks everyone again; `policies.test` keeps it equal to the cookie notice date).
  - `adminGuides.ts`, `marketing` guide, after the "Connect and check" steps:

```ts
      { type: "steps", title: "Send purchases from your server too (optional)", items: [
        "Why: ad blockers drop the browser tag, so some sales never reach Meta or Google. Your store can also report each paid order itself; Meta and GA4 count it once even when both arrive.",
        "Meta: in Events Manager open your pixel → Settings → Conversions API → Generate access token. For testing, copy the code under Test events (for example TEST12345).",
        "Google: in Google Analytics open Admin → Data streams → your web stream → Measurement Protocol API secrets → Create, and copy the secret value.",
        "On the card, paste it into the secret field, tick Also send purchases from the server and click Save server settings. The secret is stored encrypted and never shown again; paste a new one to replace it.",
        "Click Send test event. Meta's event shows under Events Manager → Test events within a minute. Google only checks the event's format, so look for your next real order in GA4 → Realtime.",
      ] },
      { type: "p", text: "Purchases go to Meta only for shoppers who allowed marketing cookies, and to Google only for those who allowed analytics. Recent purchases sent shows each one: Sent, Waiting to send (we retry for about 15 hours when a provider is down), Failed, or Not sent with the reason. If Meta or Google rejects your secret the card turns to Error: create a new one and save it. Saving and testing are recorded in the Audit log." },
```

- [ ] **Step 4:** Run: `npm test -- src/legal src/help src/utils/consent.test.ts src/components/CookieBanner.test.tsx src/utils/attribution.test.ts` — Expected: PASS.
- [ ] **Step 5: Commit** — `git add src/help/adminGuides.ts src/legal src/utils/consent.ts && git commit -m "docs(marketing): help for server events; notices name server-side sharing" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task B8: Full verification (Part B)

- [ ] **Step 1:** Run: `npm test && npm run lint && npm run build` — Expected: all tests pass (baseline count + the new suites), lint prints nothing, build ends `✓ built in …`.
- [ ] **Step 2:** `git status --short` → clean; `git diff --stat feat/marketing--server-events -- backend docs infra k8s` → empty. Report the commit list; ask before merging into `feat/marketing--server-events`.
- [ ] **Step 3 (after both parts are merged, manual):** with the backend running and a store whose `marketing` switch is on, open Admin → Marketing in the browser: first load = 1 request in DevTools; save server settings = 1 `PUT`; test = 1 `POST`; "Show more" = 1 `GET`. Not automated (no e2e suite, see the Phase 1 frontend plan).
