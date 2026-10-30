# Marketing Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each phase gets its own detailed plan (full test and code per step) written when it starts; this file fixes the order, scope, files and tests of every phase.

**Goal:** A per-store Marketing page where an owner connects Google (GA4, Ads, Merchant Center) and Meta (Pixel, Conversions API, catalogue), with every ID, secret, feed and event bound to that store.

**Architecture:** New Modulith module `com.sixpack.marketing` (hexagonal). Config and encrypted secrets in tenant-scoped `marketing_integrations`; browser tags shipped through the existing `/tenant/config`; one product feed for both platforms; paid orders go through a tenant-scoped outbox to provider adapters behind one `MarketingDestination` port. OAuth and reporting come last.

**Tech Stack:** Java 21 / Spring Boot 3.5 / Spring Modulith / Spring Data MongoDB (`TenantMongo`), `RestClient`, JCA AES-GCM; React 18 + TypeScript + RTK Query, Vitest; JUnit 5 + MockMvc + Testcontainers + WireMock-style stubs via `MockRestServiceServer`; Playwright for call budgets.

**Spec:** `docs/superpowers/specs/2026-10-30-marketing-integrations-design.md`

## Global Constraints

- Branch tree: `feat/marketing` from `dev`; each phase is a sub-branch `feat/marketing--<phase-slug>` merged back into `feat/marketing` by PR. Never commit on `dev`/`main`.
- Backend from `backend/`: `mvn -q test -Dtest=<Class>`, `mvn -q verify`. Frontend from `ecommerce-frontend/`: `npm test -- <file>`, `npm run lint`, `npm run build`.
- Every Mongo access through `TenantMongo`; new documents extend `TenantDocument`. No `MongoTemplate` in main code (architecture test already enforces it).
- Off-request work (listener, dispatcher, jobs) sets `TenantContext` from the row's `tenantId`, never from ambient state.
- Adapters call fixed provider hosts from configuration constants only. No owner-supplied URL is ever fetched.
- Secrets are write-only: no response, log line, audit diff, export or exception message contains one. One test per endpoint asserts it.
- Every public ID is validated with the spec §5.1 patterns before it is stored.
- No tag, pixel, click-ID capture or server event without the matching consent category.
- Money and currency from `StoreConfig.currency()`, never a hard-coded INR.
- `Feature.MARKETING` off → admin endpoints 404, `/tenant/config` has no `marketing` key, dispatcher skips the store.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Cross-tenant leakage** — store A's admin, feed token, OAuth state or outbox row never reads, writes or sends for store B. Pinned by `*CrossTenantIT` cases in Tasks 1.2, 2.1, 3.3, 4.2.
2. **No consent, no tracking** — with the banner declined, the storefront loads no third-party script and the server queues nothing. Pinned in Tasks 0.2, 1.4, 3.2.
3. **Double counting** — a purchase seen by both pixel and Conversions API counts once (same `event_id`); two replicas never send one outbox row twice. Pinned in Tasks 1.4, 3.3.
4. **Secret exposure** — pinned in Tasks 1.2, 3.1, 4.2.
5. **Provider outage** — 5xx/429 retries with backoff, 4xx fails once and flags the card; checkout is never slowed or failed by marketing. Pinned in Task 3.3.

---

## Phase 0 — Prerequisites (consent banner, module skeleton)

### Task 0.1: Feature switch and module skeleton

**Files:** `shared/tenant/Feature.java` (add `MARKETING("marketing")`), `application.yml` (`app.features.defaults.marketing: false`), new `marketing/package-info.java` (Modulith module, allowed deps: `shared`, `order`, `product`, `tenant`, `audit`, `consent`), `docs/foundations/architecture.md` (module table row).

- [ ] Test first: `ModularityTests` still passes with the new module; `TenantFeatureServiceTest` lists `marketing` defaulting to off.
- [ ] Add the enum value, default, empty module; run `mvn -q verify`.
- [ ] Commit `feat(marketing): module skeleton and per-store switch`.

### Task 0.2: Cookie banner and real `hasConsent`

**Files:** `ecommerce-frontend/src/utils/consent.ts`, new `components/CookieBanner.tsx` + test, footer "Cookie settings" link, `docs/foundations/compliance-consent.md` (§5 status, policy version bump), privacy text (advertising partners).

- [ ] Tests first (`consent.test.ts`, `CookieBanner.test.tsx`): no choice → every optional category `false`; accept analytics only → `hasConsent("analytics")` true, `"marketing"` false; choice older than 6 months or version change → banner shows again; withdraw → listeners notified and known cookies (`_ga*`, `_fbp`, `_fbc`, `_gcl_*`) deleted.
- [ ] Implement per compliance-consent §5: bottom bar, no overlay, Customise toggles, `localStorage` for everyone in this phase (syncing a signed-in user's choice to the server is deferred; see compliance-consent.md). Export `onConsentChange(cb)`.
- [ ] Commit `feat(consent): cookie banner and per-category consent`.

**Phase 0 done when:** banner works on the storefront, `hasConsent` reflects the choice, nothing else changed.

---

## Phase 1 — Paste IDs: browser tags for GA4, Google Ads, Meta Pixel

### Task 1.1: Domain and validation

**Files:** `marketing/domain/Provider.java`, `Integration.java` (record: `provider, status, browser, server, oauth, lastSuccessAt, lastError`), `BrowserTags.java` (validating compact constructor), `IntegrationRepository.java` (port).

- [ ] Tests first (`BrowserTagsTest`): valid `G-AB12CD3`, `AW-123456789`, label, `GTM-ABC123`, pixel `123456789012345` accepted; `G-x"><script>`, empty-with-spaces, lowercase GA4, 25-digit pixel rejected with a field-specific message; blank → null.
- [ ] Implement records; no Spring in `domain`.

### Task 1.2: Storage and admin API

**Files:** `marketing/infra/mongo/MongoIntegrationRepository.java` (`marketing_integrations`, unique `{tenantId, provider}`), `marketing/application/IntegrationService.java`, `marketing/application/MarketingAudit.java`, `marketing/web/MarketingAdminController.java`, `audit/AuditAction.java` (new actions), `SecurityConfig` rule for `/api/v1/admin/marketing/**` (ADMIN).

- [ ] Tests first (`MarketingAdminIT`):
  - `GET /admin/marketing` with feature off → 404; on → one card per provider, `status: NOT_CONNECTED`.
  - `PUT /admin/marketing/google` with GA4 + Ads IDs → 200, card returned; invalid ID → 400 naming the field.
  - Non-admin → 403; admin who has not accepted the admin agreement → existing agreement response.
  - `DELETE` → card back to `NOT_CONNECTED`.
  - Audit: `INTEGRATION_UPDATED` with actor, entity `marketing_integration:<provider>`, before/after IDs.
- [ ] Tests first (`MarketingCrossTenantIT`): admin token of store A on store A's host cannot see store B's IDs; store A's token on store B's host → 401 (existing `tid` check); repository query without a tenant fails closed.
- [ ] Implement; `GET` returns all cards in one response (call budget 1).
- [ ] Commit `feat(marketing): store owners save Google and Meta tag IDs`.

### Task 1.3: Public tags in `/tenant/config`

**Files:** `tenant/web/TenantConfigController.java` (add `marketing.tags` through a small `MarketingTags` port that `marketing` implements, so `tenant` does not depend on `marketing`), `shared/tenant/MarketingTags.java`.

- [ ] Tests first (`TenantConfigIT`): feature on + IDs saved → `marketing.tags = {ga4, adsId, adsPurchaseLabel, gtm, metaPixel}`; feature off or nothing saved → no `marketing` key; never any secret field; store B's config never contains store A's IDs.
- [ ] Implement; cached with the rest of the tenant config.

### Task 1.4: Storefront tag loader and shop events

**Files:** new `ecommerce-frontend/src/utils/tracking.ts` + `tracking.test.ts`, hooks in product page, cart, checkout and order-success page; both CSP lines in `ecommerce-frontend/nginx.conf.template` (one SPA serves admin and storefront; only storefront routes call the loader).

- [ ] Tests first (`tracking.test.ts`): no consent → no `<script>` added and `track()` is a no-op; analytics consent → only gtag for GA4; marketing consent → fbq + Ads; Consent Mode v2 `default denied` set before gtag loads and `update` on change; `purchase` sends `transaction_id`/`eventID` = order id, value and store currency; script `src` built from constants plus validated ID only; withdrawing consent stops further events.
- [ ] Implement events `view_item`, `add_to_cart`, `begin_checkout`, `purchase` (GA4 names; mapped to Meta `ViewContent`, `AddToCart`, `InitiateCheckout`, `Purchase`).
- [ ] Playwright: storefront first load still ≤ 3 API calls; no request to Google/Meta hosts before consent.
- [ ] Commit `feat(marketing): consent-gated GA4, Google Ads and Meta Pixel on the storefront`.

### Task 1.5: Admin Marketing page

**Files:** `ecommerce-frontend/src/redux/api/marketingAPI.ts`, `pages/admin/apps/marketing.tsx` + test (beside `apps/coupon.tsx`), admin nav entry under Apps (hidden when feature off), `adminGuides.ts` help article.

- [ ] Tests first: renders one card per provider from a single query; inline validation mirrors backend patterns; save shows the server's field error; "Disconnect" asks for confirmation.
- [ ] Implement card layout: status chip, fields, "Where do I find this?" link to the help article. Follow the admin design tokens (ADR-013).
- [ ] Playwright: Marketing page first load = 1 API call.
- [ ] Commit `feat(marketing): admin Marketing page`.

**Phase 1 done when:** an owner pastes IDs, accepts cookies on the storefront and sees the purchase in GA4 DebugView and Meta Events Manager.

---

## Phase 2 — One product feed for Google Merchant Center and Meta catalogue

### Task 2.1: Feed token and endpoint

**Files:** `marketing/domain/MarketingSettings.java`, `infra/mongo/MongoMarketingSettingsRepository.java` (`marketing_settings`, unique `tenantId`), `application/CatalogFeedService.java`, `web/FeedController.java`, `product/CatalogReads.java` (read port: active products with variants, paged), its Mongo adapter in `product`.

- [ ] Tests first (`CatalogFeedServiceTest`): product with 3 variants → 3 `<item>`s with one `g:item_group_id`; out-of-stock → `out of stock`; price with store currency; HTML stripped from description; XML special characters escaped; image URL absolute on the store's own domain; product without image skipped and counted.
- [ ] Tests first (`FeedIT`): right host + token → 200 `application/rss+xml` with `ETag`; same `If-None-Match` → 304; wrong token → 404; store A's token on store B's host → 404; feed disabled → 404; 61st request in a minute from one IP → 429.
- [ ] Tests first: `POST /admin/marketing/feed/rotate` → new token, old one 404, audit `FEED_TOKEN_ROTATED`.
- [ ] Implement; Caffeine cache 15 min keyed by tenant; evict on `ProductChangedEvent`. Stream the XML (no whole-catalogue string for large stores); cap at `TenantLimits` product count.
- [ ] Admin card shows the feed URL with a copy button and setup steps for both platforms.
- [ ] Commit `feat(marketing): per-store product feed for Google and Meta`.

**Phase 2 done when:** Merchant Center and Commerce Manager both accept the feed URL of a test store with no item errors.

---

## Phase 3 — Server-side conversions (Meta Conversions API, GA4 Measurement Protocol)

### Task 3.1: Secret encryption

**Files:** `marketing/infra/crypto/SecretBox.java`, `marketing/application/MarketingProperties.java` (`MARKETING_KEYS`), `.env.example` (fake key), `docs/foundations/architecture.md` new ADR, next free number (ADR-028 is the module decision) (secrets encrypted in Mongo for owner-set and rotating tokens; deviation from T+).

- [ ] Tests first (`SecretBoxTest`): round trip; same plaintext twice → different ciphertexts; tampered ciphertext, IV or tag → fails; **other tenant's AAD → fails**; decrypts with an older key id after a new key is put first; no key configured → `isAvailable() == false`.
- [ ] Tests first (`MarketingAdminIT`): `PUT` with `server.secret` stores ciphertext only (assert raw Mongo document), response has `hasSecret: true` and no secret anywhere; audit diff shows `secret: "[changed]"`.
- [ ] Implement with JCA `AES/GCM/NoPadding`, 12-byte random IV, 128-bit tag.

### Task 3.2: Attribution capture at checkout

**Files:** `order/domain/OrderAttribution.java` (additive nested record), checkout command and `CheckoutService`, `ecommerce-frontend/src/utils/attribution.ts` + test, checkout request in `orderAPI.ts`.

- [ ] Tests first (frontend): landing URL with `utm_*`, `gclid`, `fbclid` stored in `sessionStorage` only when marketing consent is given; `_fbp`/`_fbc` read only with consent; values length-capped.
- [ ] Tests first (`CheckoutIT`): attribution saved on the order; oversized or non-ASCII junk trimmed/rejected; missing attribution → `null`, checkout unchanged; legacy orders load.
- [ ] `UserDeletedEvent` clears attribution on that user's orders (extend the existing erasure listener test).

### Task 3.3: Outbox, recorder, dispatcher, adapters

**Files:** `marketing/domain/ConversionEvent.java`, `MarketingDestination.java`, `marketing/infra/mongo/MongoOutbox.java` (`marketing_outbox`, unique `{tenantId, provider, eventId}`, TTL 30 d), `application/ConversionRecorder.java` (`@EventListener OrderChangedEvent`), `application/ConversionDispatcher.java` (`@Scheduled` 30 s), `infra/meta/MetaConversionsApi.java`, `infra/google/Ga4MeasurementProtocol.java`, `application/PiiHasher.java`.

- [ ] Tests first (`PiiHasherTest`): email trimmed + lower-cased, phone to E.164 digits, SHA-256 hex; matches the providers' documented sample values.
- [ ] Tests first (`ConversionRecorderTest`): paid order with marketing consent → one row per provider with server sending on; no consent → none; same order event twice → still one row per provider; unpaid → none; feature off → none.
- [ ] Tests first (`ConversionDispatcherIT`, with `MockRestServiceServer`): sends with that tenant's decrypted token and `event_id = orderId`; 500/429/timeout → retry at the backoff steps then `FAILED`; 400/401 → `FAILED` at once and card `ERROR` with an owner-readable message; two dispatchers racing claim each row once; store A's row is sent with store A's credentials even when store B's row is next; checkout latency unaffected (recorder never calls the network).
- [ ] Tests first (`MarketingAdminIT`): `POST /{provider}/test` sends a test event (Meta `test_event_code`, GA4 debug endpoint) and returns the provider's verdict; rate-limited to 10/min per store; delivery log paged.
- [ ] Metrics `marketing.events{provider,outcome}` and `marketing.outbox.pending`; alert rule next to the audit-write alert.
- [ ] Commit per task.

**Phase 3 done when:** a paid test order shows once (deduplicated) in Meta Events Manager and in GA4, with the browser blocked and unblocked.

---

## Phase 4 — One-click "Connect" with OAuth

Start Google OAuth verification, the Google Ads developer token and Meta App Review **when Phase 3 starts**; this phase is blocked on them.

### Task 4.1: Platform OAuth apps and state

**Files:** `marketing/application/OAuthState.java` (HMAC-signed `{tenantId, userId, provider, nonce, exp}` + PKCE verifier kept server-side keyed by nonce, single use), `MarketingProperties` (client IDs/secrets, callback base URL), `TenantResolutionFilter` (add `/api/v1/oauth` to its tenant-less list beside `/api/v1/platform`; the callback sets `TenantContext` from the verified state only).

- [ ] Tests first (`OAuthStateTest`): round trip; tampered, expired, reused, or provider-mismatched state rejected.

### Task 4.2: Connect, pick account, disconnect

**Files:** `web/OAuthController.java` (`/admin/marketing/oauth/{provider}/start`, platform `/api/v1/oauth/{provider}/callback`), `infra/google/GoogleOAuthClient.java`, `infra/meta/MetaOAuthClient.java`, account pickers in `apps/marketing.tsx`.

- [ ] Tests first (`OAuthFlowIT`): start returns provider URL with `state`, `code_challenge`, the platform callback; callback with valid state stores encrypted refresh token **on the tenant in the state** and redirects to that tenant's admin domain; cross-tenant or replayed state → 400 and nothing stored; provider error → card error, no token; disconnect revokes at the provider then deletes.
- [ ] Tests first: `TenantPurge` revokes then deletes; `TenantExport` contains providers and public IDs, no tokens.
- [ ] After connect, list GA4 properties / Ads accounts / pixels / catalogues for the owner to choose; fill the phase-1 IDs from the choice.
- [ ] Optional in this phase: Google Merchant API push of changed products, Google Ads enhanced conversions for paid orders (new adapter on the same outbox).

**Phase 4 done when:** an owner connects Google and Meta without pasting any ID and the existing tags and server events keep working.

---

## Phase 5 — Reporting: spend and return on ad spend

### Task 5.1: Daily pull

**Files:** `marketing/infra/mongo/MongoMarketingDaily.java` (`marketing_daily`, unique `{tenantId, provider, day}`), `application/ReportingJob.java` (nightly per connected tenant, store time zone, re-pulls the last 3 days), adapters' `fetchDaily`.

- [ ] Tests first: upsert is idempotent; days follow the store's time zone; a tenant with an expired token is skipped and its card flagged; currency mismatch between ad account and store is shown, not converted.

### Task 5.2: Marketing tab in Analytics

**Files:** `dashboard` read port for `marketing_daily`, new Analytics "Marketing" tab (spend, clicks, conversions, revenue from attributed orders, ROAS per provider and per `utm_campaign`), CSV export like the other sections.

- [ ] Tests first: zero spend → ROAS shown as "—", never `Infinity`; attributed revenue counts paid orders only.

**Phase 5 done when:** the owner sees last month's spend and ROAS per channel next to sales.

---

## Later (not planned in detail)

TikTok and Pinterest adapters (card + tag + events API on the same port), Microsoft Ads, abandoned-checkout events, email marketing integration. Each is one adapter plus one card; no new infrastructure.

## Rollout

1. Each phase behind `Feature.MARKETING` per store; enable for the default tenant first, then one pilot store.
2. `make scan` and `make dast` before each phase PR; security review of phases 3 and 4 by the `security-reviewer` agent.
3. Update `docs/foundations/contracts.md` (endpoints, call budgets), `requirements.md` (audit events), `compliance-consent.md`, the help centre and release notes with each phase.
