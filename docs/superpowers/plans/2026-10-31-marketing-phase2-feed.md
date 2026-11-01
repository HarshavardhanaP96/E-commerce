# Marketing Phase 2 — Product Feed for Google Merchant Center and the Meta Catalogue

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A store owner turns on one product feed on Admin → Marketing, copies its URL into Google Merchant Center and Meta Commerce Manager, and both pull that store's active products (one item per variant) from `https://<store domain>/api/v1/feeds/<secret token>/products.xml`. The token is bound to the host-resolved store, rotatable, and treated as a credential.

**Architecture:** Backend: the `marketing` module gains a per-store `marketing_settings` row (`_id` = tenant id), a `FeedSettingsService` (enable/disable/rotate, audit), a `CatalogFeedService` that renders Google RSS 2.0 with a StAX writer fed from a Mongo cursor through a new `product.CatalogReads` port, and caches the bytes per store for 15 minutes (evicted on `ProductChangedEvent`). Two new controllers (`FeedAdminController`, `FeedController`); the existing `GET /admin/marketing` only gains a `feed` key. The public feed reuses `AuthRateLimitFilter` (new bucket function) and the token is masked out of traces. Frontend: a new `FeedCard.tsx` on the existing Marketing page (one mount line), mutations injected into the existing `marketingAPI` slice so they patch its cache.

**Tech Stack:** Java 21 / Spring Boot 3.5 / Spring Modulith / Spring Data MongoDB (`TenantMongo`), JDK StAX (`javax.xml.stream`), Caffeine; JUnit 5 + AssertJ + MockMvc + Testcontainers (Docker must be running). React 18 + TypeScript + RTK Query 2, Vitest 3 + jsdom (no Testing Library; `src/test/dom.tsx`).

**Spec:** `docs/superpowers/specs/2026-10-30-marketing-integrations-design.md` (§4.3 feed row, §5.2, §6 Feed, §7) · **Phase plan:** `docs/superpowers/plans/2026-10-30-marketing-integrations.md` Phase 2, Task 2.1 · **Decision:** ADR-028 in `docs/foundations/architecture.md`.

The plan has two independent parts. **Part A (backend)** and **Part B (frontend)** run in parallel in separate worktrees; Part B depends only on the Fixed API contract below and mocks every call.

## Global Constraints

- **Branches and worktrees.** This plan lives on `feat/marketing--feed` (cut from `feat/marketing`). Each part gets a sub-branch of it, merged back into `feat/marketing--feed` by PR or local merge (ask before merging or pushing):
  - Part A: `git -C /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing-feed worktree add ../6Pack-marketing-feed-be -b feat/marketing--feed--backend feat/marketing--feed`
  - Part B: `git -C /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing-feed worktree add ../6Pack-marketing-feed-fe -b feat/marketing--feed--frontend feat/marketing--feed`
  - Never commit on `dev`, `main`, `feat/marketing` or `feat/marketing--feed` directly. Commit after each task.
- **Sandbox.** If your Bash tool runs in a sandbox that blocks writes in the worktree, run every write/git/mvn/npm command there with the sandbox disabled.
- **Backend commands** run from `backend/`: one class `mvn -q test -Dtest=<Class>`, several `mvn -q test -Dtest='<A>,<B>'`, everything `mvn -q verify`. Test classes end in `Test`/`Tests` (no failsafe plugin: a class named `*IT` would silently not run); integration tests are `*IntegrationTest` and need Docker.
- **Frontend commands** run from `ecommerce-frontend/`: `npm ci` first if `node_modules` is missing (it is missing in a fresh worktree); one file `npm test -- <path>`; all `npm test`; `npm run lint` (`--max-warnings 0`); `npm run build`.
- Every Mongo access through `TenantMongo`; the new document extends `TenantDocument`. No `MongoTemplate` in main code (`TenantIsolationArchitectureTest`).
- No Spring, Jackson or Mongo types in `com.sixpack.marketing.domain`. No module may depend on `marketing` (`ModularityTests.nothingOutsideMarketingDependsOnIt`).
- Errors through `ApiException` → `{success:false, message}`; JSON responses through `ApiResponse.of`.
- **The feed token is a credential.** It never appears in an audit entry, a log line, a trace attribute, an error message, an exception message, a `toString()` or the offboarding export. It only appears in the admin responses' `url` and in the stored row.
- Money and currency from `StoreConfig.currency()`; never a hard-coded INR.
- **No new dependencies** (backend or frontend).
- **Parallel work (Phase 3 is being built on another branch).** Phase 3 also edits `marketing.tsx`, `marketingAPI.ts`, `MarketingAdminController`, `AuditAction`, `adminGuides.ts`, `contracts.md`, `requirements.md`, `TenantExport` and the `IntegrationTest` base. Keep edits to those files to the few lines this plan shows; put everything else in new files. Do **not** edit `backend/src/test/java/com/sixpack/it/IntegrationTest.java` (the new `FeedIntegrationTest` base cleans its own collection).
- Part A edits only `backend/` and `docs/foundations/`; Part B edits only `ecommerce-frontend/`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Fixed API contract (both parts rely on it — implement exactly)

Paths below are under `/api/v1`.

**Storage.** Collection `marketing_settings` (extends `TenantDocument`; one row per store): `{_id = tenantId, tenantId, feedEnabled: boolean, feedToken: string|null, updatedAt}`. Token = 32 bytes from `SecureRandom`, URL-safe base64 without padding (43 characters `[A-Za-z0-9_-]`), created on the first enable, kept while the feed is off, replaced only by rotate.

**`feed` object:** `{enabled: boolean, url: string|null}`. `url` = `https://<store primary domain>/api/v1/feeds/<token>/products.xml` while enabled; `null` while disabled (and for a store without a domain).

| Call | Success | Errors |
|---|---|---|
| `GET /admin/marketing` | `200 {success:true, integrations:[…unchanged…], feed:{enabled, url}}` — keys in that order; never-enabled store → `feed:{enabled:false, url:null}` | feature off → 404 |
| `PUT /admin/marketing/feed` body `{enabled:boolean}` | `200 {success:true, feed:{enabled, url}}` | `{}` or `enabled:null` → `400 {success:false, message:"enabled: is required"}`; feature off → 404 |
| `POST /admin/marketing/feed/rotate` | `200 {success:true, feed:{enabled:true, url:<new>}}`; the old URL answers 404 at once | feed off → `400 {success:false, message:"feed: enable it first"}`; feature off → 404 |
| `GET /feeds/{token}/products.xml` | `200`, `Content-Type: application/rss+xml;charset=UTF-8`, `ETag: "<32 hex>"`, `Cache-Control: max-age=900, public`, Google RSS 2.0 body (`xmlns:g="http://base.google.com/ns/1.0"`); with a matching `If-None-Match` → `304` (no body) | **empty-body 404** unless the feature is on, the feed is on and `{token}` equals the host-resolved store's token (constant-time compare); `429` after 60 requests per minute from one client address on one store (any token) |

- Admin routes: tenant `ADMIN` only (403 otherwise, 401 anonymous, existing admin-agreement 403 applies); a token from store A on store B's host → 401 (existing `tid` check).
- **Audit.** Entity type `marketing_settings`, entity id `feed`. `FEED_UPDATED` on a PUT that changes `enabled` (a PUT that changes nothing writes no entry): changes = `enabled` with before/after values, plus `feedToken` as a redacted "changed" entry (no value) when the token was created. `FEED_TOKEN_ROTATED` on rotate: changes = `feedToken` redacted only.
- **Items** (one `<item>` per active variant, `g:item_group_id` = product id; one per product without variants), children in this order: `g:id` (product id, or `<productId>-<variantId>`; when that is longer than 50 characters `<productId>-<first 16 hex of SHA-256(variantId)>`), `g:item_group_id` (variants only), `title` (name, or `<name> - <variant label>`; cut to 150 characters ending `…`), `description` (HTML tags stripped, basic entities decoded, whitespace collapsed; the name when empty; cut to 5000), `link` (`https://<domain>/product/<productId>`), `g:image_link` (`https://<domain>/<photo path>`; the variant's colour image, else the product photo), `g:availability` (`in stock` when stock > 0, else `out of stock`), `g:price` (`<amount, 2 decimals, HALF_UP> <StoreConfig.currency()>`, e.g. `499.00 INR`), `g:brand` (store name), `g:condition` (`new`). Channel: `title` = store name, `link` = `https://<domain>`, `description` = `Products from <store name>`.
- Only `ACTIVE` products (and legacy ones without a status), oldest first (`_id` ascending), at most `TenantQuotas.maxProducts()` products; disabled variants and items without an image are left out; characters XML 1.0 cannot carry are dropped; all text escaped by the XML writer.
- Rendered from a Mongo cursor with a StAX writer (no list of products, no whole-catalogue `String`); the rendered bytes are cached per store for 15 minutes; a `ProductChangedEvent` or a feed setting change (enable, disable, rotate) drops that store's copy.
- `TenantPurge` deletes `marketing_settings` (registered `BackfillTarget`); `TenantExport` writes `marketing_settings.json` **without** `feedToken` and lists `marketing_settings.feedToken` under `omitted`.
- **Call budget:** Marketing page first load stays 1 call (`feed` rides on `GET /admin/marketing`); toggle = 1 `PUT`; New URL = 1 `POST`; Copy = 0; mutations patch the cached `GET` response, never refetch.

## Review Focus

1. **Cross-store leakage** — store A's token never opens anything on store B's host; a feed never lists another store's products, also when served from cache; one store's admin cannot change another's feed. Pinned by `FeedCrossTenantIntegrationTest` (Task A5), `CatalogReadsIntegrationTest.readsOnlyTheCurrentStore` (A1), `MarketingSettingsIntegrationTest.eachStoreHasItsOwnRow…` (A3).
2. **Token as a credential** — constant-time compare; every miss is the same empty 404; token absent from audit, logs, traces, `toString()` and export. Pinned by `FeedAdminIntegrationTest.changesAreAuditedWithoutTheToken` (A4), `…theTokenNeverReachesTheLogs` (A6), `TenantObservationConventionTest` (A6), `MarketingSettingsTest` (A3), `TenantOffboardingIntegrationTest.theFeedTokenIsNeitherExportedNorKeptAfterPurge` (A3).
3. **A feed both platforms accept** — escaping, dropped control characters, Google's 50/150/5000 limits, absolute links on the store domain, price format with the store's currency. Pinned by `FeedWriterTest` (A2) and `FeedEndpointIntegrationTest.servesOneItemPerActiveVariantAndPerSimpleProduct` (A5).
4. **Freshness and load** — cached until a product or feed setting changes; `If-None-Match` → 304; 60/min per client per store regardless of the token in the path (no fresh budget by guessing tokens). Pinned by `FeedEndpointIntegrationTest` (A5, A6) and `AuthRateLimitFilterTest.aBucketCountsEveryMatchingRequestTogether` (A6).
5. **Call budget and cache updates** — page still 1 request; toggle and New URL 1 request each, no refetch. Pinned by `FeedCard.test.tsx` (B2), which counts every `fetch`.

## Changes from the phase plan / contract (decided while planning — read before starting)

No change to the request/response shapes above was forced by the code. Decisions and clarifications:

- **Unique per store via `_id = tenantId`, not a unique index on `tenantId`.** `TenantDocument` already carries a non-unique `@Indexed tenantId`; MongoDB refuses a second index on the same key that differs only in `unique`. Keying the row by the store id gives the same guarantee and lets enable be one atomic upsert (`$setOnInsert` for the first token, so two admins enabling at once still end with one token).
- **"Streamed" and "cached" together.** Options considered:
  - **A (chosen):** StAX writer fed one product at a time from a Mongo cursor (`TenantMongo.stream`, new) into a byte buffer; the bytes plus a SHA-256 ETag are cached. *Why:* the 15-minute server cache and cheap 304s need the bytes anyway, a render error becomes a clean 500 instead of a half-written XML file, and no list of products or `String` of the catalogue is ever built.
  - B: cache only an ETag fingerprint and stream every miss straight to the socket — O(1) memory, but needs a cheap catalogue fingerprint (products have no reliable change counter), reads Mongo on every fetch and can send truncated XML on error.
- **Own Caffeine cache in `CatalogFeedService`, not a `CacheConfig` entry.** The size cap must weigh the feed bytes (a marketing type `shared` cannot see), so the cache lives in the module: `expireAfterWrite 15 min`, `maximumWeight 64 MB`, keyed by `TenantContext.require().id()` (fails closed like `TenantCacheManager`). `Cache.get(key, loader)` also gives single-flight rendering per store, and an `invalidate` during a render removes the result, so a product change mid-render cannot leave a stale copy. Bonus: `CacheConfig` (shared) stays untouched.
- **Eviction without a tenant.** `ProductChangedEvent` handlers evict the current store's copy; with no store bound (a background job) they drop every store's copy instead of throwing — a throwing listener would fail the product write.
- **Rate limit reuses `AuthRateLimitFilter`** with a new constructor taking a bucket function (`request → bucket name or null`). The feed bucket is the constant `"feed"`, so the key is `store | client IP | feed`: guessing tokens gives no fresh budget, and Google's shared fetcher IPs are limited per store, not platform-wide. The second instance gets its own bean name so `OncePerRequestFilter` never confuses it with the login limiter. Limit 60/min as a constant in `SecurityConfig`.
- **Empty-body 404 from `FeedController`** (`ResponseEntity.notFound()`), not `ApiException`: a fetcher sending `Accept: application/xml` would otherwise get a content-negotiation error instead of a 404, and nothing is echoed.
- **Token kept out of traces.** Spring's default server observation puts the raw request URI (with the token) into every span's `http.url`. `TenantObservationConvention` overrides `httpUrl` to write `/api/v1/feeds/{token}/…`. The route template already keeps it out of metrics.
- **`TenantSettings` gains `name()`** (for `g:brand` and the channel title) — one method on the shared port and its single implementation.
- **Audit diff** carries `feedToken` as a redacted "changed" entry (field name only, no value) when a token is created or replaced; the contract's "diff of `enabled`" is otherwise as stated.
- **A store without a domain** gets `url: null` and a 404 feed (no absolute links possible). The default tenant's domains are `localhost,127.0.0.1`, so in local dev the URL is `https://localhost/…` (Google cannot fetch it; expected).
- **Description fallback** to the product name and the 150/5000 cuts are not in the phase plan; Google rejects items without a description or over the limits.
- **Help-centre note moves to Part B (Task B4).** The admin help centre is `ecommerce-frontend/src/help/adminGuides.ts`; editing it in both parts would conflict. Part A's docs task updates `contracts.md` and `requirements.md` only.
- **No Playwright request-count test** (no e2e suite exists; adding one is a new dependency): the call budget is pinned in Vitest by counting `fetch` calls through a real RTK Query store, as in Phase 1.
- **Deferred (named, not built):** metric `marketing.feed.requests` (spec §9) — one INFO log line per render instead; `HEAD` requests (GET only); `g:gtin`/`g:mpn`/`g:color`/`g:size`; evicting the feed when the store's name, currency or domain changes (it refreshes within 15 minutes, or at once when the owner turns the feed off and on).

## File map

**Part A — backend** (`backend/src/main/java/com/sixpack/…` unless noted)

| File | Responsibility | Task |
|---|---|---|
| `shared/tenant/TenantMongo.java` | `stream(Query, Class)` — scoped cursor | A1 |
| `product/CatalogReads.java` (new) | public port: `forEachListing(maxProducts, sink)` + `Listing` record | A1 |
| `product/domain/ProductRepository.java`, `product/infra/mongo/MongoProductRepository.java` | `forEachPublic(limit, action)` through the cursor | A1 |
| `product/application/ProductCatalogService.java` | implements `CatalogReads` (reuses `variantPhoto`) | A1 |
| `marketing/application/FeedWriter.java` (new) | Google RSS 2.0 item writer (StAX), limits, cleaning | A2 |
| `marketing/domain/MarketingSettings.java`, `MarketingSettingsRepository.java` (new) | settings record (constant-time `opensFeed`, masked `toString`), port | A3 |
| `marketing/infra/mongo/MarketingSettingsDocument.java`, `MongoMarketingSettingsRepository.java`, `FeedBackfill.java` (new) | `marketing_settings` storage, offboarding registration | A3 |
| `tenant/application/TenantExport.java` | leave `feedToken` out of the export | A3 |
| `audit/AuditAction.java` | `FEED_UPDATED`, `FEED_TOKEN_ROTATED` | A4 |
| `marketing/application/FeedSettingsService.java` (new) | view / enable / disable / rotate / `opens`, audit | A4, A5 |
| `marketing/web/FeedAdminController.java` (new) | `PUT /admin/marketing/feed`, `POST …/rotate` | A4 |
| `marketing/web/MarketingAdminController.java` | `feed` key on `GET` (3 lines) | A4 |
| `shared/tenant/TenantSettings.java`, `tenant/application/TenantSettingsService.java` | `name()` | A5 |
| `marketing/application/CatalogFeedService.java` (new) | render, per-store cache, eviction | A5 |
| `marketing/web/FeedController.java` (new) | public `GET /feeds/{token}/products.xml` | A5 |
| `shared/security/SecurityConfig.java` | `permitAll` for the feed (A5), feed rate limiter (A6) | A5, A6 |
| `shared/security/AuthRateLimitFilter.java` | bucket-function constructor | A6 |
| `tenant/web/TenantObservationConvention.java` | mask the token in `http.url` | A6 |
| `docs/foundations/contracts.md`, `docs/foundations/requirements.md` | endpoints, data row, call budget, audit events, FR | A7 |

Tests (`backend/src/test/java/com/sixpack/…`): `marketing/FeedXml.java` (helper), `it/FeedIntegrationTest.java` (abstract helpers), `it/CatalogReadsIntegrationTest.java`, `it/TenantMongoIntegrationTest.java` (A1); `marketing/application/FeedWriterTest.java` (A2); `marketing/domain/MarketingSettingsTest.java`, `it/MarketingSettingsIntegrationTest.java`, `it/TenantOffboardingIntegrationTest.java` (A3); `it/FeedAdminIntegrationTest.java` (A4, A6); `it/FeedEndpointIntegrationTest.java`, `it/FeedCrossTenantIntegrationTest.java` (A5, A6); `shared/security/AuthRateLimitFilterTest.java`, `tenant/web/TenantObservationConventionTest.java` (A6).

**Part B — frontend** (`ecommerce-frontend/src/…`)

| File | Responsibility | Task |
|---|---|---|
| `utils/clipboard.ts` (+ `clipboard.test.ts`) | `copyText` with the Clipboard API and a fallback | B1 |
| `redux/api/marketingAPI.ts` | `Feed` type, `feed` on `MarketingResponse` (2 lines) | B2 |
| `redux/api/feedAPI.ts` (new) | `setFeed`, `rotateFeed` injected into `marketingAPI`, cache patch | B2 |
| `pages/admin/apps/FeedCard.tsx` (+ `FeedCard.test.tsx`) | the Product feed card | B2 |
| `pages/admin/apps/marketing.tsx` | import + one mount line | B2 |
| `redux/api/auditAPI.ts` (+ test) | the two audit actions in the filter list | B3 |
| `help/adminGuides.ts` (+ test) | `product-feed` guide | B4 |

---

# Part A: Backend

### Task A0: Baseline

**Files:** none.

- [ ] **Step 1: Worktree and branch**

```bash
git -C /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing-feed worktree add ../6Pack-marketing-feed-be -b feat/marketing--feed--backend feat/marketing--feed
cd /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing-feed-be
git status --short   # expect clean, branch feat/marketing--feed--backend
```

- [ ] **Step 2: Record the baseline**

Run: `cd backend && mvn -q verify`
Expected: exit code 0 (Docker running). If anything fails here, stop and report it — it is not caused by this plan.

---

### Task A1 (phase plan 2.1, read port): The catalogue, one product at a time

**Files:**
- Modify: `backend/src/main/java/com/sixpack/shared/tenant/TenantMongo.java`
- Create: `backend/src/main/java/com/sixpack/product/CatalogReads.java`
- Modify: `backend/src/main/java/com/sixpack/product/domain/ProductRepository.java`
- Modify: `backend/src/main/java/com/sixpack/product/infra/mongo/MongoProductRepository.java`
- Modify: `backend/src/main/java/com/sixpack/product/application/ProductCatalogService.java`
- Create (test helpers): `backend/src/test/java/com/sixpack/marketing/FeedXml.java`, `backend/src/test/java/com/sixpack/it/FeedIntegrationTest.java`
- Test: `backend/src/test/java/com/sixpack/it/CatalogReadsIntegrationTest.java`, `backend/src/test/java/com/sixpack/it/TenantMongoIntegrationTest.java`

**Interfaces:**
- Produces: `TenantMongo.stream(Query, Class<T>) → Stream<T>` (scoped, fails closed, caller closes); `ProductRepository.forEachPublic(int limit, Consumer<Product>)`; public `com.sixpack.product.CatalogReads` with `forEachListing(int maxProducts, Consumer<Listing>)` and `record Listing(String productId, String variantId, String name, String variantLabel, String description, String photo, double price, int stock)`.

- [ ] **Step 1: Write the test helpers (used by every later backend task)**

`backend/src/test/java/com/sixpack/marketing/FeedXml.java`:

```java
package com.sixpack.marketing;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Reads a product feed back for assertions. */
public final class FeedXml {

    private FeedXml() {
    }

    /** Each {@code <item>} as tag name (with prefix, e.g. {@code g:id}) → text, in document order. */
    public static List<Map<String, String>> items(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml))
                .getElementsByTagName("item");
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Map<String, String> item = new LinkedHashMap<>();
            NodeList fields = nodes.item(i).getChildNodes();
            for (int j = 0; j < fields.getLength(); j++) {
                if (fields.item(j) instanceof Element field) {
                    item.put(field.getTagName(), field.getTextContent());
                }
            }
            items.add(item);
        }
        return items;
    }
}
```

`backend/src/test/java/com/sixpack/it/FeedIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.shared.tenant.TenantLimits;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Helpers for the product feed tests: stores, raw products, the two admin calls and the public fetch. */
abstract class FeedIntegrationTest extends MarketingIntegrationTest {

    protected static final String FEED = BASE + "/feed";
    /** A feed address: the store's domain (group 1), then a 43-character URL-safe token (group 2). */
    protected static final Pattern FEED_URL =
            Pattern.compile("^https://([^/]+)/api/v1/feeds/([A-Za-z0-9_-]{43})/products\\.xml$");

    // Cleaned here, not in the shared IntegrationTest base, which other workstreams edit in parallel.
    @BeforeEach
    void cleanFeedSettings() {
        mongo.getCollection("marketing_settings").deleteMany(new Document());
    }

    /** A store on {@code shop.<slug>.test} with the marketing switch on, its own currency and optional limits. */
    protected Tenant feedStore(String slug, String name, String currency, TenantLimits limits) {
        Tenant t = tenantRepository.save(new Tenant(null, slug, name, TenantStatus.ACTIVE,
                List.of("shop." + slug + ".test"), Map.of(), "en-IN", currency, null, null, null, null, null,
                Map.of("marketing", true), null, limits));
        tenantRegistry.refresh();
        return t;
    }

    protected ResultActions setFeed(Tenant t, String token, boolean enabled) throws Exception {
        return mvc.perform(put(FEED).with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enabled", enabled))));
    }

    protected ResultActions rotate(Tenant t, String token) throws Exception {
        return mvc.perform(post(FEED + "/rotate").with(host(hostOf(t))).header("Authorization", bearer(token)));
    }

    /** Turns the feed on through the admin API and returns its secret token. */
    protected String enableFeed(Tenant t, String adminToken) throws Exception {
        return tokenOf(body(setFeed(t, adminToken, true).andExpect(status().isOk()).andReturn())
                .get("feed").get("url").asText());
    }

    protected static String tokenOf(String url) {
        Matcher m = FEED_URL.matcher(url);
        assertThat(m.matches()).as(url).isTrue();
        return m.group(2);
    }

    protected static String feedPath(String feedToken) {
        return "/api/v1/feeds/" + feedToken + "/products.xml";
    }

    /** An anonymous fetch, as Google or Meta make it, on the store's host. */
    protected ResultActions fetch(Tenant t, String feedToken) throws Exception {
        return mvc.perform(get(feedPath(feedToken)).with(host(hostOf(t))));
    }

    protected byte[] feedBody(Tenant t, String feedToken) throws Exception {
        return fetch(t, feedToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /** Makes the request come from {@code ip}. */
    protected static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** Inserts a product straight into the store's collection: no product event, so cached feeds stay. */
    protected String insertProduct(Tenant t, Document product) {
        ObjectId id = new ObjectId();
        mongo.getCollection("products").insertOne(product.append("_id", id).append("tenantId", t.id()));
        return id.toHexString();
    }

    /** An active product without variants, photo {@code uploads/p/<name>.png}. */
    protected static Document simple(String name, double price, int stock) {
        return new Document("name", name).append("photo", "uploads/p/" + name.replace(' ', '-') + ".png")
                .append("price", price).append("stock", stock).append("status", "ACTIVE");
    }

    /** "Tee" with axes Size (S, M) and Colour (Black, which has an image) and the given variants. */
    protected static Document tee(Document... variants) {
        return simple("Tee", 499, 0).append("variants", new Document("axes", List.of(
                        new Document("name", "Size").append("type", "TEXT")
                                .append("values", List.of(new Document("label", "S"), new Document("label", "M"))),
                        new Document("name", "Colour").append("type", "COLOUR").append("values", List.of(
                                new Document("label", "Black").append("images", List.of("uploads/p/tee-black.png"))))))
                .append("items", List.of(variants)));
    }

    /** A Tee variant in Black; {@code status} is ACTIVE or DISABLED. */
    protected static Document variant(String id, String size, double price, int stock, String status) {
        return new Document("variantId", id).append("sku", id.toUpperCase())
                .append("options", List.of(new Document("name", "Size").append("value", size),
                        new Document("name", "Colour").append("value", "Black")))
                .append("price", price).append("stock", stock).append("status", status);
    }
}
```

- [ ] **Step 2: Write the failing tests**

Add to `TenantMongoIntegrationTest` (its imports already cover `assertThat`, `assertThatThrownBy`, `TenantContext`, `Query`):

```java
    @Test
    void streamReadsOnlyTheCurrentTenantAndFailsClosed() {
        saveAs(A, "one", "x");
        saveAs(B, "two", "x");

        TenantContext.runAs(A, () -> {
            try (var probes = tenantMongo.stream(new Query(), Probe.class)) {
                assertThat(probes.map(p -> p.name)).containsExactly("one");
            }
        });
        try (var none = TenantContext.open(null)) {
            assertThatThrownBy(() -> tenantMongo.stream(new Query(), Probe.class))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
```

`backend/src/test/java/com/sixpack/it/CatalogReadsIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.product.CatalogReads;
import com.sixpack.product.CatalogReads.Listing;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The product module's read port for the feed: sellable lines of public products, one product at a time. */
class CatalogReadsIntegrationTest extends FeedIntegrationTest {

    @Autowired
    CatalogReads catalog;

    Tenant acme;

    @BeforeEach
    void store() {
        acme = feedStore("acme", "Acme Shop", "INR", null);
    }

    private List<Listing> listings(Tenant t, int maxProducts) {
        List<Listing> out = new ArrayList<>();
        TenantContext.runAs(ref(t), () -> catalog.forEachListing(maxProducts, out::add));
        return out;
    }

    @Test
    void oneLinePerSimpleProductAndPerActiveVariantOldestFirst() {
        String mug = insertProduct(acme, simple("Mug", 250, 4).append("description", "Big"));
        insertProduct(acme, simple("Draft", 1, 1).append("status", "DRAFT"));
        insertProduct(acme, simple("Archived", 1, 1).append("status", "ARCHIVED"));
        String legacy = insertProduct(acme, simple("Legacy", 5, 1).append("status", null));
        String tee = insertProduct(acme, tee(variant("tee-s", "S", 499, 5, "ACTIVE"),
                variant("tee-m", "M", 549, 0, "DISABLED")));

        assertThat(listings(acme, 100)).containsExactly(
                new Listing(mug, null, "Mug", null, "Big", "uploads/p/Mug.png", 250, 4),
                new Listing(legacy, null, "Legacy", null, null, "uploads/p/Legacy.png", 5, 1),
                new Listing(tee, "tee-s", "Tee", "S / Black", null, "uploads/p/tee-black.png", 499, 5));
    }

    @Test
    void readsAtMostTheGivenNumberOfProducts() {
        String one = insertProduct(acme, simple("One", 1, 1));
        String two = insertProduct(acme, simple("Two", 1, 1));
        insertProduct(acme, simple("Three", 1, 1));

        assertThat(listings(acme, 2)).extracting(Listing::productId).containsExactly(one, two);
    }

    @Test
    void readsOnlyTheCurrentStore() {
        Tenant globex = feedStore("globex", "Globex", "INR", null);
        String mine = insertProduct(acme, simple("Anvil", 1, 1));
        insertProduct(globex, simple("Gizmo", 1, 1));

        assertThat(listings(acme, 100)).extracting(Listing::productId).containsExactly(mine);
        try (var none = TenantContext.open(null)) {
            assertThatThrownBy(() -> catalog.forEachListing(100, l -> { }))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='CatalogReadsIntegrationTest,TenantMongoIntegrationTest'`
Expected: FAIL — `COMPILATION ERROR … cannot find symbol … class CatalogReads` and `cannot find symbol … method stream(Query, Class<Probe>)`.

- [ ] **Step 4: Implement**

`TenantMongo.java` — add the import `java.util.stream.Stream` and, after `find(Query, Class<T>)`:

```java
    /** Like {@link #find(Query, Class)} but through a cursor, one document at a time. The caller closes it. */
    public <T> Stream<T> stream(Query query, Class<T> type) {
        return mongo.stream(scoped(query), type);
    }
```

`backend/src/main/java/com/sixpack/product/CatalogReads.java`:

```java
package com.sixpack.product;

import java.util.function.Consumer;

/** Read-only view of the catalogue for modules that list it elsewhere (the marketing product feed). */
public interface CatalogReads {

    /**
     * Calls {@code sink} once per sellable line of the current store's public products, oldest product first,
     * reading one product at a time. A product without variants is one line; a product with variants is one line
     * per active variant (disabled ones are left out). At most {@code maxProducts} products are read.
     */
    void forEachListing(int maxProducts, Consumer<Listing> sink);

    /**
     * @param variantId    null for a product without variants
     * @param variantLabel e.g. "M / Black"; null for a product without variants
     * @param description  as the admin typed it; may be null
     * @param photo        storage path such as {@code uploads/…}: the variant's colour image, else the product
     *                     photo; may be null
     */
    record Listing(String productId, String variantId, String name, String variantLabel, String description,
                   String photo, double price, int stock) {
    }
}
```

`ProductRepository.java` — add the import `java.util.function.Consumer` and, after `findLatest`:

```java
    /** Public (ACTIVE) products, oldest first, at most {@code limit}, read through a cursor one at a time. */
    void forEachPublic(int limit, Consumer<Product> action);
```

`MongoProductRepository.java` — add the imports `java.util.function.Consumer` and `java.util.stream.Stream` and, after `findLatest`:

```java
    @Override
    public void forEachPublic(int limit, Consumer<Product> action) {
        try (Stream<ProductDocument> docs = mongo.stream(
                new Query(publicOnly()).with(Sort.by("id")).limit(limit), ProductDocument.class)) {
            docs.map(MongoProductRepository::toDomain).forEach(action);
        }
    }
```

`ProductCatalogService.java` — add the imports `com.sixpack.product.CatalogReads` and `java.util.function.Consumer`, change the declaration to `class ProductCatalogService implements ProductCatalog, CatalogReads {`, and add after `lowStock()`:

```java
    @Override
    public void forEachListing(int maxProducts, Consumer<Listing> sink) {
        products.forEachPublic(maxProducts, p -> {
            String description = p.details().description();
            if (!p.hasVariants()) {
                sink.accept(new Listing(p.id(), null, p.name(), null, description, p.photo(), p.price(), p.stock()));
                return;
            }
            for (Variant v : p.details().variants().items()) {
                if (v.status().isSellable()) {
                    sink.accept(new Listing(p.id(), v.id(), p.name(), v.label(), description, variantPhoto(p, v),
                            v.price(), v.stock()));
                }
            }
        });
    }
```

- [ ] **Step 5: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='CatalogReadsIntegrationTest,TenantMongoIntegrationTest,ModularityTests'`
Expected: PASS (all).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/sixpack/shared/tenant/TenantMongo.java \
        backend/src/main/java/com/sixpack/product/CatalogReads.java \
        backend/src/main/java/com/sixpack/product/domain/ProductRepository.java \
        backend/src/main/java/com/sixpack/product/infra/mongo/MongoProductRepository.java \
        backend/src/main/java/com/sixpack/product/application/ProductCatalogService.java \
        backend/src/test/java/com/sixpack/marketing/FeedXml.java \
        backend/src/test/java/com/sixpack/it/FeedIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/CatalogReadsIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/TenantMongoIntegrationTest.java
git commit -m "feat(product): read the store's sellable lines one product at a time

CatalogReads lets the marketing feed list a store's catalogue from a cursor
instead of loading every product into memory.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A2 (phase plan 2.1, CatalogFeedServiceTest): The feed writer

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/application/FeedWriter.java`
- Test: `backend/src/test/java/com/sixpack/marketing/application/FeedWriterTest.java`

**Interfaces:**
- Consumes: `CatalogReads.Listing` (A1).
- Produces (package-private): `new FeedWriter(OutputStream out, String storeName, String base, String currency)`, `add(Listing)`, `finish()`, `items()`, `skipped()`, `static String sha256(byte[])` (hex).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/sixpack/marketing/application/FeedWriterTest.java`:

```java
package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.sixpack.marketing.FeedXml;
import com.sixpack.product.CatalogReads.Listing;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class FeedWriterTest {

    private static final String BASE = "https://shop.acme.test";
    private static final String PID = "65f000000000000000000001";

    private record Written(byte[] xml, FeedWriter writer) {
    }

    private static Written write(Listing... listings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FeedWriter writer = new FeedWriter(out, "Acme Shop", BASE, "USD");
        for (Listing l : listings) {
            writer.add(l);
        }
        writer.finish();
        return new Written(out.toByteArray(), writer);
    }

    private static List<Map<String, String>> items(Listing... listings) throws Exception {
        return FeedXml.items(write(listings).xml());
    }

    private static Listing product(String name, String description, String photo, double price, int stock) {
        return new Listing(PID, null, name, null, description, photo, price, stock);
    }

    @Test
    void writesAGoogleShoppingRssChannel() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element rss = factory.newDocumentBuilder().parse(new ByteArrayInputStream(write().xml()))
                .getDocumentElement();

        assertThat(rss.getTagName()).isEqualTo("rss");
        assertThat(rss.getAttribute("version")).isEqualTo("2.0");
        assertThat(rss.lookupNamespaceURI("g")).isEqualTo("http://base.google.com/ns/1.0");
        Element channel = (Element) rss.getElementsByTagName("channel").item(0);
        assertThat(channel.getElementsByTagName("title").item(0).getTextContent()).isEqualTo("Acme Shop");
        assertThat(channel.getElementsByTagName("link").item(0).getTextContent()).isEqualTo(BASE);
        assertThat(channel.getElementsByTagName("description").item(0).getTextContent())
                .isEqualTo("Products from Acme Shop");
    }

    @Test
    void aProductWithoutVariantsIsOneItem() throws Exception {
        assertThat(items(product("Mug", "A mug.", "uploads/p/mug.png", 250, 4)).get(0)).containsExactly(
                entry("g:id", PID), entry("title", "Mug"), entry("description", "A mug."),
                entry("link", BASE + "/product/" + PID), entry("g:image_link", BASE + "/uploads/p/mug.png"),
                entry("g:availability", "in stock"), entry("g:price", "250.00 USD"),
                entry("g:brand", "Acme Shop"), entry("g:condition", "new"));
    }

    @Test
    void variantsAreItemsGroupedUnderTheirProduct() throws Exception {
        List<Map<String, String>> items = items(
                new Listing(PID, "tee-s", "Tee", "S / Black", null, "uploads/p/s.png", 499, 5),
                new Listing(PID, "tee-m", "Tee", "M / Black", null, "uploads/p/m.png", 549, 0));

        assertThat(items).extracting(i -> i.get("g:id")).containsExactly(PID + "-tee-s", PID + "-tee-m");
        assertThat(items).extracting(i -> i.get("g:item_group_id")).containsOnly(PID);
        assertThat(items).extracting(i -> i.get("title")).containsExactly("Tee - S / Black", "Tee - M / Black");
        assertThat(items).extracting(i -> i.get("g:availability")).containsExactly("in stock", "out of stock");
        assertThat(items).extracting(i -> i.get("description")).containsOnly("Tee");
        assertThat(items(product("Mug", null, "uploads/p/mug.png", 1, 1)).get(0))
                .doesNotContainKey("g:item_group_id");
    }

    @Test
    void pricesHaveTwoDecimalsAndTheStoresCurrency() throws Exception {
        assertThat(items(product("A", null, "uploads/a.png", 0.1 + 0.2, 1),
                product("B", null, "uploads/b.png", 1234.5, 1), product("C", null, "uploads/c.png", 7, 1)))
                .extracting(i -> i.get("g:price")).containsExactly("0.30 USD", "1234.50 USD", "7.00 USD");
    }

    @Test
    void htmlIsStrippedFromDescriptions() throws Exception {
        assertThat(items(product("Mug", "<p>Holds <b>tea</b> &amp; coffee.</p>\n<br/>Dishwasher&nbsp;safe",
                "uploads/p/mug.png", 1, 1)).get(0).get("description"))
                .isEqualTo("Holds tea & coffee. Dishwasher safe");
    }

    @Test
    void markupInTextIsEscapedAndCharactersXmlCannotCarryAreDropped() throws Exception {
        byte[] xml = write(product("Tom & Jerry <\"Mugs\">\u0001￾", "a\u0000b", "uploads/p/tj.png", 1, 1)).xml();

        assertThat(new String(xml, StandardCharsets.UTF_8)).contains("Tom &amp; Jerry &lt;")
                .doesNotContain("\u0001").doesNotContain("\u0000");
        Map<String, String> item = FeedXml.items(xml).get(0);
        assertThat(item.get("title")).isEqualTo("Tom & Jerry <\"Mugs\">");
        assertThat(item.get("description")).isEqualTo("ab");
    }

    @Test
    void imageAddressesAreAbsoluteOnTheStoresDomain() throws Exception {
        assertThat(items(product("A", null, "uploads/a.png", 1, 1), product("B", null, "/uploads/b.png", 1, 1)))
                .extracting(i -> i.get("g:image_link"))
                .containsExactly(BASE + "/uploads/a.png", BASE + "/uploads/b.png");
    }

    @Test
    void listingsWithoutAnImageAreLeftOutAndCounted() throws Exception {
        Written w = write(product("A", null, null, 1, 1), product("B", null, " ", 1, 1),
                product("C", null, "uploads/c.png", 1, 1));

        assertThat(FeedXml.items(w.xml())).extracting(i -> i.get("title")).containsExactly("C");
        assertThat(w.writer().items()).isEqualTo(1);
        assertThat(w.writer().skipped()).isEqualTo(2);
    }

    @Test
    void valuesStayWithinGooglesLimits() throws Exception {
        Listing longOne = new Listing(PID, "v".repeat(40), "T".repeat(200), null, "d".repeat(6000), "uploads/p.png",
                1, 1);
        Listing other = new Listing(PID, "w".repeat(40), "T", null, null, "uploads/p.png", 1, 1);

        Map<String, String> item = items(longOne).get(0);
        assertThat(item.get("g:id")).startsWith(PID + "-").hasSizeLessThanOrEqualTo(50)
                .isEqualTo(items(longOne).get(0).get("g:id"))
                .isNotEqualTo(items(other).get(0).get("g:id"));
        assertThat(item.get("title")).hasSize(150).endsWith("…");
        assertThat(item.get("description")).hasSize(5000).endsWith("…");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=FeedWriterTest`
Expected: FAIL — `COMPILATION ERROR … cannot find symbol … class FeedWriter`.

- [ ] **Step 3: Write the feed writer**

`backend/src/main/java/com/sixpack/marketing/application/FeedWriter.java`:

```java
package com.sixpack.marketing.application;

import com.sixpack.product.CatalogReads.Listing;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Writes a Google product feed (RSS 2.0 with the {@code g:} namespace), which Google Merchant Center and the Meta
 * catalogue both read. Items are written as they arrive, so the catalogue is never held as a whole. The XML writer
 * escapes all text; characters XML 1.0 cannot carry are dropped first.
 */
final class FeedWriter {

    static final String G = "http://base.google.com/ns/1.0";
    // Google's limits: longer values get the item rejected.
    private static final int MAX_ID = 50;
    private static final int MAX_TITLE = 150;
    private static final int MAX_DESCRIPTION = 5000;
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern NOT_XML =
            Pattern.compile("[^\\x09\\x0A\\x0D\\x20-\\x{D7FF}\\x{E000}-\\x{FFFD}\\x{10000}-\\x{10FFFF}]");

    private final XMLStreamWriter xml;
    private final String base;
    private final String brand;
    private final String currency;
    private int items;
    private int skipped;

    /** @param base the store's address without a trailing slash, e.g. {@code https://shop.acme.test} */
    FeedWriter(OutputStream out, String storeName, String base, String currency) {
        this.base = base;
        this.brand = storeName;
        this.currency = currency;
        try {
            xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("rss");
            xml.writeAttribute("version", "2.0");
            xml.writeNamespace("g", G);
            xml.writeStartElement("channel");
            element("title", storeName);
            element("link", base);
            element("description", "Products from " + storeName);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not start the product feed", e);
        }
    }

    /** Adds the listing as one item, or counts it as skipped when it has no image (both platforms require one). */
    void add(Listing l) {
        if (l.photo() == null || l.photo().isBlank()) {
            skipped++;
            return;
        }
        String description = plain(l.description());
        try {
            xml.writeStartElement("item");
            g("id", id(l));
            if (l.variantId() != null) {
                g("item_group_id", l.productId());
            }
            element("title", cut(l.variantLabel() == null ? l.name() : l.name() + " - " + l.variantLabel(),
                    MAX_TITLE));
            element("description", cut(description.isEmpty() ? l.name() : description, MAX_DESCRIPTION));
            element("link", base + "/product/" + l.productId());
            g("image_link", base + "/" + l.photo().replaceFirst("^/+", ""));
            g("availability", l.stock() > 0 ? "in stock" : "out of stock");
            g("price", BigDecimal.valueOf(l.price()).setScale(2, RoundingMode.HALF_UP).toPlainString() + " "
                    + currency);
            g("brand", brand);
            g("condition", "new");
            xml.writeEndElement();
            items++;
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not write product " + l.productId() + " to the feed", e);
        }
    }

    void finish() {
        try {
            xml.writeEndElement(); // channel
            xml.writeEndElement(); // rss
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not finish the product feed", e);
        }
    }

    int items() {
        return items;
    }

    int skipped() {
        return skipped;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Product id, or product id + variant id; the variant part is hashed when the whole would pass 50. */
    private static String id(Listing l) {
        if (l.variantId() == null) {
            return l.productId();
        }
        String id = l.productId() + "-" + l.variantId();
        return id.length() <= MAX_ID ? id
                : l.productId() + "-" + sha256(l.variantId().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static String plain(String html) {
        if (html == null) {
            return "";
        }
        String text = TAG.matcher(html).replaceAll(" ").replace("&nbsp;", " ").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return SPACE.matcher(text).replaceAll(" ").trim();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private void element(String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(clean(text));
        xml.writeEndElement();
    }

    private void g(String name, String text) throws XMLStreamException {
        xml.writeStartElement("g", name, G);
        xml.writeCharacters(clean(text));
        xml.writeEndElement();
    }

    /** Also removes a half surrogate pair left by {@link #cut}. */
    private static String clean(String text) {
        return NOT_XML.matcher(text).replaceAll("");
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=FeedWriterTest`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/application/FeedWriter.java \
        backend/src/test/java/com/sixpack/marketing/application/FeedWriterTest.java
git commit -m "feat(marketing): write a Google product feed both Google and Meta accept

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A3 (phase plan 2.1, storage): `marketing_settings` and offboarding

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/domain/MarketingSettings.java`
- Create: `backend/src/main/java/com/sixpack/marketing/domain/MarketingSettingsRepository.java`
- Create: `backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingSettingsDocument.java`
- Create: `backend/src/main/java/com/sixpack/marketing/infra/mongo/MongoMarketingSettingsRepository.java`
- Create: `backend/src/main/java/com/sixpack/marketing/infra/mongo/FeedBackfill.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/application/TenantExport.java`
- Test: `backend/src/test/java/com/sixpack/marketing/domain/MarketingSettingsTest.java`, `backend/src/test/java/com/sixpack/it/MarketingSettingsIntegrationTest.java`, `backend/src/test/java/com/sixpack/it/TenantOffboardingIntegrationTest.java`

**Interfaces:**
- Produces: `record MarketingSettings(boolean feedEnabled, String feedToken)` with `NONE`, `opensFeed(String)` (constant time), masked `toString()`; `interface MarketingSettingsRepository { MarketingSettings find(); MarketingSettings enableFeed(String newToken); void disableFeed(); boolean rotateFeedToken(String newToken); }`; `BackfillTarget` `marketing_settings`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/sixpack/marketing/domain/MarketingSettingsTest.java`:

```java
package com.sixpack.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketingSettingsTest {

    @Test
    void onlyTheExactTokenOfAnEnabledFeedOpensIt() {
        MarketingSettings on = new MarketingSettings(true, "abc_DEF-123");

        assertThat(on.opensFeed("abc_DEF-123")).isTrue();
        assertThat(on.opensFeed("abc_def-123")).isFalse();
        assertThat(on.opensFeed("abc_DEF-12")).isFalse();
        assertThat(on.opensFeed("abc_DEF-1234")).isFalse();
        assertThat(on.opensFeed("")).isFalse();
        assertThat(on.opensFeed(null)).isFalse();
        assertThat(new MarketingSettings(false, "abc_DEF-123").opensFeed("abc_DEF-123")).isFalse();
        assertThat(MarketingSettings.NONE.opensFeed(null)).isFalse();
    }

    @Test
    void theTokenNeverShowsInItsTextForm() {
        assertThat(new MarketingSettings(true, "secret-token").toString())
                .doesNotContain("secret-token").contains("feedEnabled=true");
    }
}
```

`backend/src/test/java/com/sixpack/it/MarketingSettingsIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.marketing.domain.MarketingSettings;
import com.sixpack.marketing.domain.MarketingSettingsRepository;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One marketing_settings row per store, reached only through that store. */
class MarketingSettingsIntegrationTest extends FeedIntegrationTest {

    @Autowired
    MarketingSettingsRepository settings;

    Tenant acme;
    Tenant globex;

    @BeforeEach
    void twoStores() {
        acme = feedStore("acme", "Acme", "INR", null);
        globex = feedStore("globex", "Globex", "INR", null);
    }

    private <T> T in(Tenant t, Supplier<T> action) {
        return TenantContext.callAs(ref(t), action);
    }

    @Test
    void aStoreWithoutARowHasTheFeedOff() {
        assertThat(in(acme, settings::find)).isEqualTo(MarketingSettings.NONE);
    }

    @Test
    void theFirstTokenIsKeptThroughOffAndOn() {
        assertThat(in(acme, () -> settings.enableFeed("first"))).isEqualTo(new MarketingSettings(true, "first"));
        TenantContext.runAs(ref(acme), settings::disableFeed);
        assertThat(in(acme, settings::find)).isEqualTo(new MarketingSettings(false, "first"));
        assertThat(in(acme, () -> settings.enableFeed("second"))).isEqualTo(new MarketingSettings(true, "first"));

        List<Document> rows = mongo.getCollection("marketing_settings").find().into(new ArrayList<>());
        assertThat(rows).hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("_id"))).isEqualTo(acme.id());
        assertThat(rows.get(0).getString("tenantId")).isEqualTo(acme.id());
    }

    @Test
    void onlyAnEnabledFeedGetsANewToken() {
        assertThat(in(acme, () -> settings.rotateFeedToken("x"))).isFalse();
        assertThat(in(acme, settings::find)).isEqualTo(MarketingSettings.NONE);

        in(acme, () -> settings.enableFeed("first"));
        assertThat(in(acme, () -> settings.rotateFeedToken("second"))).isTrue();
        TenantContext.runAs(ref(acme), settings::disableFeed);
        assertThat(in(acme, () -> settings.rotateFeedToken("third"))).isFalse();
        assertThat(in(acme, settings::find)).isEqualTo(new MarketingSettings(false, "second"));
    }

    @Test
    void eachStoreHasItsOwnRowAndNoStoreMeansNoAccess() {
        in(acme, () -> settings.enableFeed("acme-token"));
        assertThat(in(globex, settings::find)).isEqualTo(MarketingSettings.NONE);

        in(globex, () -> settings.enableFeed("globex-token"));
        TenantContext.runAs(ref(globex), settings::disableFeed);
        assertThat(in(globex, () -> settings.rotateFeedToken("other"))).isFalse();
        assertThat(in(acme, settings::find)).isEqualTo(new MarketingSettings(true, "acme-token"));

        try (var none = TenantContext.open(null)) {
            assertThatThrownBy(settings::find).isInstanceOf(IllegalStateException.class);
        }
    }
}
```

Add to `TenantOffboardingIntegrationTest` (add the import `java.nio.charset.StandardCharsets`; `populatedStore`, `platform`, `platformToken`, `unzip`, `ownedBy` and `purge` already exist):

```java
    @Test
    void theFeedTokenIsNeitherExportedNorKeptAfterPurge() throws Exception {
        Tenant acme = populatedStore();
        String token = "feed-token-that-must-not-leak";
        mongo.getCollection("marketing_settings").insertOne(new Document("_id", acme.id())
                .append("tenantId", acme.id()).append("feedEnabled", true).append("feedToken", token));

        byte[] zip = mvc.perform(get(API + acme.id() + "/export").header("Authorization", bearer(platformToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> files = unzip(zip);

        assertThat(files).containsKey("marketing_settings.json");
        JsonNode settings = json.readTree(files.get("marketing_settings.json"));
        assertThat(settings).hasSize(1);
        assertThat(settings.get(0).get("feedEnabled").asBoolean()).isTrue();
        assertThat(settings.get(0).has("feedToken")).isFalse();
        assertThat(files.values()).noneMatch(b -> new String(b, StandardCharsets.ISO_8859_1).contains(token));
        assertThat(json.readTree(files.get("manifest.json")).get("omitted").toString())
                .contains("marketing_settings.feedToken");

        platform(acme.id() + "/offboard").andExpect(status().isOk());
        purge.purgeDue(Instant.now().plus(Duration.ofDays(31)));
        assertThat(ownedBy(acme, "marketing_settings")).isZero();
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='MarketingSettingsTest,MarketingSettingsIntegrationTest,TenantOffboardingIntegrationTest'`
Expected: FAIL — `COMPILATION ERROR … cannot find symbol … class MarketingSettings` (and `MarketingSettingsRepository`).

- [ ] **Step 3: Write the domain, storage and offboarding change**

`backend/src/main/java/com/sixpack/marketing/domain/MarketingSettings.java`:

```java
package com.sixpack.marketing.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The store's marketing settings that belong to no single provider: the product feed. {@code feedToken} is the
 * secret part of the feed's address; treat it as a credential (never log, audit or export it).
 */
public record MarketingSettings(boolean feedEnabled, String feedToken) {

    public static final MarketingSettings NONE = new MarketingSettings(false, null);

    /** Whether {@code token} opens this store's feed. Compares in constant time. */
    public boolean opensFeed(String token) {
        return feedEnabled && feedToken != null && token != null && MessageDigest.isEqual(
                feedToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "MarketingSettings[feedEnabled=" + feedEnabled + ", feedToken=" + (feedToken == null ? null : "***")
                + "]";
    }
}
```

`backend/src/main/java/com/sixpack/marketing/domain/MarketingSettingsRepository.java`:

```java
package com.sixpack.marketing.domain;

/** The current store's settings row. Implementations scope every call to the tenant in context and fail without one. */
public interface MarketingSettingsRepository {

    /** The store's settings, or {@link MarketingSettings#NONE} when it has none yet. */
    MarketingSettings find();

    /** Turns the feed on; {@code newToken} is stored only when the store has no token yet. @return what is stored */
    MarketingSettings enableFeed(String newToken);

    /** Turns the feed off and keeps the token, so turning it on again restores the same address. */
    void disableFeed();

    /** Replaces the token of an enabled feed. @return false when the feed is off (nothing changed) */
    boolean rotateFeedToken(String newToken);
}
```

`backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingSettingsDocument.java`:

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** The store's feed settings (spec §5.2). */
@Document("marketing_settings")
class MarketingSettingsDocument extends TenantDocument {

    /** The store's id, so a store can never have two rows (a unique index on tenantId would clash with @Indexed). */
    @Id
    String id;
    boolean feedEnabled;
    /** A credential: never logged or exported. */
    String feedToken;
    Instant updatedAt;
}
```

`backend/src/main/java/com/sixpack/marketing/infra/mongo/MongoMarketingSettingsRepository.java`:

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.marketing.domain.MarketingSettings;
import com.sixpack.marketing.domain.MarketingSettingsRepository;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Instant;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoMarketingSettingsRepository implements MarketingSettingsRepository {

    private final TenantMongo mongo;

    MongoMarketingSettingsRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public MarketingSettings find() {
        return mongo.findOne(new Query(), MarketingSettingsDocument.class)
                .map(d -> new MarketingSettings(d.feedEnabled, d.feedToken)).orElse(MarketingSettings.NONE);
    }

    /** One atomic upsert: two admins enabling at once still leave one token. */
    @Override
    public MarketingSettings enableFeed(String newToken) {
        mongo.upsert(mine(), new Update().set("feedEnabled", true).setOnInsert("feedToken", newToken)
                .set("updatedAt", Instant.now()), MarketingSettingsDocument.class);
        return find();
    }

    @Override
    public void disableFeed() {
        mongo.updateFirst(mine(), new Update().set("feedEnabled", false).set("updatedAt", Instant.now()),
                MarketingSettingsDocument.class);
    }

    @Override
    public boolean rotateFeedToken(String newToken) {
        return mongo.updateFirst(mine().addCriteria(Criteria.where("feedEnabled").is(true)),
                new Update().set("feedToken", newToken).set("updatedAt", Instant.now()),
                MarketingSettingsDocument.class).getMatchedCount() > 0;
    }

    /** The store's row; TenantMongo adds the tenant and copies both onto a row an upsert creates. */
    private static Query mine() {
        return Query.query(Criteria.where("id").is(TenantContext.require().id()));
    }
}
```

`backend/src/main/java/com/sixpack/marketing/infra/mongo/FeedBackfill.java`:

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers marketing_settings so offboarding exports (without the token) and purges it with the rest of the store. */
@Configuration
class FeedBackfill {

    @Bean
    BackfillTarget marketingSettingsBackfill() {
        return () -> "marketing_settings";
    }
}
```

`TenantExport.java` — replace the `SKIPPED_FIELDS` line:

```java
    private static final Map<String, Set<String>> SKIPPED_FIELDS = Map.of("users", Set.of("password"),
            "marketing_settings", Set.of("feedToken"));
```

and the `omitted` line in `write`:

```java
            manifest.put("omitted", List.of("users.password", "marketing_settings.feedToken", "password_reset_tokens",
                    "payment_intents"));
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='MarketingSettingsTest,MarketingSettingsIntegrationTest,TenantOffboardingIntegrationTest,TenantIsolationArchitectureTest'`
Expected: PASS (all, including `everyTenantOwnedCollectionIsRegistered`).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/domain/MarketingSettings.java \
        backend/src/main/java/com/sixpack/marketing/domain/MarketingSettingsRepository.java \
        backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingSettingsDocument.java \
        backend/src/main/java/com/sixpack/marketing/infra/mongo/MongoMarketingSettingsRepository.java \
        backend/src/main/java/com/sixpack/marketing/infra/mongo/FeedBackfill.java \
        backend/src/main/java/com/sixpack/tenant/application/TenantExport.java \
        backend/src/test/java/com/sixpack/marketing/domain/MarketingSettingsTest.java \
        backend/src/test/java/com/sixpack/it/MarketingSettingsIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/TenantOffboardingIntegrationTest.java
git commit -m "feat(marketing): per-store feed settings, purged with the store and never exported

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A4 (phase plan 2.1, admin API): Turn the feed on and off, rotate it, audit it

**Files:**
- Modify: `backend/src/main/java/com/sixpack/audit/AuditAction.java`
- Create: `backend/src/main/java/com/sixpack/marketing/application/FeedSettingsService.java`
- Create: `backend/src/main/java/com/sixpack/marketing/web/FeedAdminController.java`
- Modify: `backend/src/main/java/com/sixpack/marketing/web/MarketingAdminController.java`
- Test: `backend/src/test/java/com/sixpack/it/FeedAdminIntegrationTest.java`

**Interfaces:**
- Consumes: `MarketingSettingsRepository` (A3), `TenantSettings.primaryDomain()`, `AuditTrail`.
- Produces: `FeedSettingsService` (public) with `record Feed(boolean enabled, String url)`, `view()`, `setEnabled(boolean)`, `rotate()`, `opens(String token)`; `AuditAction.FEED_UPDATED`, `FEED_TOKEN_ROTATED`; the two admin routes; `feed` key on `GET /admin/marketing`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/sixpack/it/FeedAdminIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Admin → Marketing product feed: on/off, the URL, a new URL, roles, the feature switch and audit. */
class FeedAdminIntegrationTest extends FeedIntegrationTest {

    Tenant acme;
    String admin;

    @BeforeEach
    void store() throws Exception {
        acme = feedStore("acme", "Acme Shop", "INR", null);
        admin = adminTokenFor(acme);
    }

    @Test
    void withTheFeatureOffTheFeedEndpointsAreNotFound() throws Exception {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        String token = adminToken();

        setFeed(def, token, true).andExpect(status().isNotFound());
        rotate(def, token).andExpect(status().isNotFound());
        assertThat(mongo.getCollection("marketing_settings").countDocuments()).isZero();
    }

    @Test
    void theMarketingPageGetsTheFeedInItsOneCall() throws Exception {
        JsonNode body = body(list(acme, admin).andExpect(status().isOk()).andReturn());

        assertThat(fieldNames(body)).containsExactly("success", "integrations", "feed");
        assertThat(fieldNames(body.get("feed"))).containsExactly("enabled", "url");
        assertThat(body.get("feed").get("enabled").asBoolean()).isFalse();
        assertThat(body.get("feed").get("url").isNull()).isTrue();
    }

    @Test
    void turningTheFeedOnGivesAUrlOnTheStoresDomain() throws Exception {
        JsonNode feed = body(setFeed(acme, admin, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andReturn()).get("feed");

        assertThat(feed.get("enabled").asBoolean()).isTrue();
        Matcher url = FEED_URL.matcher(feed.get("url").asText());
        assertThat(url.matches()).isTrue();
        assertThat(url.group(1)).isEqualTo("shop.acme.test");
        list(acme, admin).andExpect(jsonPath("$.feed.enabled").value(true))
                .andExpect(jsonPath("$.feed.url").value(feed.get("url").asText()));

        Document row = mongo.getCollection("marketing_settings").find(new Document("tenantId", acme.id())).first();
        assertThat(String.valueOf(row.get("_id"))).isEqualTo(acme.id());
        assertThat(row.getBoolean("feedEnabled")).isTrue();
        assertThat(row.getString("feedToken")).isEqualTo(url.group(2));
    }

    @Test
    void turningItOffHidesTheUrlAndTurningItOnBringsTheSameUrlBack() throws Exception {
        String token = enableFeed(acme, admin);

        setFeed(acme, admin, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.enabled").value(false))
                .andExpect(jsonPath("$.feed.url").value(nullValue()));
        list(acme, admin).andExpect(jsonPath("$.feed.url").value(nullValue()));

        assertThat(enableFeed(acme, admin)).isEqualTo(token);
    }

    @Test
    void aNewUrlReplacesTheOldOne() throws Exception {
        String old = enableFeed(acme, admin);

        String fresh = tokenOf(body(rotate(acme, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.enabled").value(true)).andReturn()).get("feed").get("url").asText());

        assertThat(fresh).isNotEqualTo(old);
        list(acme, admin).andExpect(jsonPath("$.feed.url").value(containsString(fresh)));
        assertThat(mongo.getCollection("marketing_settings").find().first().getString("feedToken")).isEqualTo(fresh);
    }

    @Test
    void aNewUrlNeedsTheFeedOn() throws Exception {
        rotate(acme, admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("feed: enable it first"));

        enableFeed(acme, admin);
        setFeed(acme, admin, false).andExpect(status().isOk());
        rotate(acme, admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("feed: enable it first"));
        assertThat(auditEntries("FEED_TOKEN_ROTATED")).isEmpty();
    }

    @Test
    void theBodyMustSayOnOrOff() throws Exception {
        mvc.perform(put(FEED).with(host(hostOf(acme))).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("enabled: is required"));
    }

    @Test
    void onlyTheStoresAdminMayChangeTheFeed() throws Exception {
        String customer = registerOn(acme, "buyer@acme.test");

        setFeed(acme, customer, true).andExpect(status().isForbidden());
        rotate(acme, customer).andExpect(status().isForbidden());
        mvc.perform(put(FEED).with(host(hostOf(acme))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isUnauthorized());
        assertThat(mongo.getCollection("marketing_settings").countDocuments()).isZero();
    }

    @Test
    void changesAreAuditedWithoutTheToken() throws Exception {
        String first = enableFeed(acme, admin);
        setFeed(acme, admin, true).andExpect(status().isOk()); // no change, no entry
        String second = tokenOf(body(rotate(acme, admin).andExpect(status().isOk()).andReturn())
                .get("feed").get("url").asText());
        setFeed(acme, admin, false).andExpect(status().isOk());

        List<Document> updated = auditEntries("FEED_UPDATED");
        assertThat(updated).hasSize(2);
        Document on = updated.get(1);
        assertThat(on.getString("tenantId")).isEqualTo(acme.id());
        assertThat(on.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(on.getString("entityType")).isEqualTo("marketing_settings");
        assertThat(on.getString("entityId")).isEqualTo("feed");
        assertThat(changeOf(on, "enabled").get("before")).isEqualTo(false);
        assertThat(changeOf(on, "enabled").get("after")).isEqualTo(true);
        assertThat(changeOf(on, "feedToken").getBoolean("redacted")).isTrue();
        Document off = updated.get(0);
        assertThat(off.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("enabled");

        List<Document> rotated = auditEntries("FEED_TOKEN_ROTATED");
        assertThat(rotated).hasSize(1);
        assertThat(rotated.get(0).getString("entityId")).isEqualTo("feed");
        assertThat(rotated.get(0).getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("feedToken");
        assertThat(changeOf(rotated.get(0), "feedToken").getBoolean("redacted")).isTrue();

        String everything = mongo.getCollection("audit_log").find(new Document("tenantId", acme.id()))
                .map(Document::toJson).into(new ArrayList<>()).toString();
        assertThat(everything).doesNotContain(first).doesNotContain(second);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=FeedAdminIntegrationTest`
Expected: FAIL — e.g. `theMarketingPageGetsTheFeedInItsOneCall` (no `feed` key) and `turningTheFeedOnGivesAUrlOnTheStoresDomain` with `Status expected:<200> but was:<404>` (PUT `/feed` lands on `MarketingAdminController.save` as an unknown provider). `withTheFeatureOffTheFeedEndpointsAreNotFound` and `onlyTheStoresAdminMayChangeTheFeed` may already pass.

- [ ] **Step 3: Implement**

`AuditAction.java` — add one line after `INTEGRATION_UPDATED, INTEGRATION_DISCONNECTED,` (its own javadoc, so a parallel edit of the integration line does not touch it):

```java
    /** The store's product feed turned on or off, and its address replaced (Admin → Marketing). */
    FEED_UPDATED, FEED_TOKEN_ROTATED,
```

`backend/src/main/java/com/sixpack/marketing/application/FeedSettingsService.java`:

```java
package com.sixpack.marketing.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.audit.FieldChange;
import com.sixpack.marketing.domain.MarketingSettings;
import com.sixpack.marketing.domain.MarketingSettingsRepository;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.TenantSettings;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The store's product feed switch and secret address. Callers check {@code Feature.MARKETING} first. */
@Service
public class FeedSettingsService {

    static final String ENTITY = "marketing_settings";
    static final String ENTITY_ID = "feed";
    /** {@code enabled} is logged with its values; the token only as "changed". */
    static final FieldPolicy POLICY = Changes.policy(List.of("enabled"), List.of("feedToken"));
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the admin sees: whether the feed is on and, while it is, its address. */
    public record Feed(boolean enabled, String url) {
    }

    private final MarketingSettingsRepository settings;
    private final TenantSettings tenant;
    private final AuditTrail audit;

    FeedSettingsService(MarketingSettingsRepository settings, TenantSettings tenant, AuditTrail audit) {
        this.settings = settings;
        this.tenant = tenant;
        this.audit = audit;
    }

    public Feed view() {
        return view(settings.find());
    }

    /** Turns the feed on (creating its token the first time) or off (keeping the token); audited when it changes. */
    public Feed setEnabled(boolean enabled) {
        MarketingSettings before = settings.find();
        MarketingSettings after;
        if (enabled) {
            after = settings.enableFeed(newToken());
        } else {
            settings.disableFeed();
            after = new MarketingSettings(false, before.feedToken());
        }
        if (before.feedEnabled() != after.feedEnabled()) {
            audit.record(AuditEvent.of(AuditAction.FEED_UPDATED).on(ENTITY, ENTITY_ID)
                    .changing(Changes.diff(audited(before), audited(after), POLICY)));
        }
        return view(after);
    }

    /** Gives the feed a new address; the old one stops working at once. */
    public Feed rotate() {
        String token = newToken();
        if (!settings.rotateFeedToken(token)) {
            throw ApiException.badRequest("feed: enable it first");
        }
        audit.record(AuditEvent.of(AuditAction.FEED_TOKEN_ROTATED).on(ENTITY, ENTITY_ID)
                .changing(List.of(FieldChange.redacted("feedToken"))));
        return view(new MarketingSettings(true, token));
    }

    /** Whether {@code token} opens the current store's feed: on, with a domain to link to, and the right token. */
    public boolean opens(String token) {
        return tenant.primaryDomain() != null && settings.find().opensFeed(token);
    }

    private Feed view(MarketingSettings s) {
        String domain = tenant.primaryDomain();
        return new Feed(s.feedEnabled(), s.feedEnabled() && domain != null
                ? "https://" + domain + "/api/v1/feeds/" + s.feedToken() + "/products.xml" : null);
    }

    private static Map<String, Object> audited(MarketingSettings s) {
        Map<String, Object> m = new HashMap<>(); // not Map.of: the token may be null
        m.put("enabled", s.feedEnabled());
        m.put("feedToken", s.feedToken());
        return m;
    }

    /** 32 random bytes, URL-safe base64 without padding: 43 characters. */
    private static String newToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
```

`backend/src/main/java/com/sixpack/marketing/web/FeedAdminController.java`:

```java
package com.sixpack.marketing.web;

import com.sixpack.marketing.application.FeedSettingsService;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.web.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Marketing product feed. ADMIN-only via the existing {@code /api/v1/admin/**} rule. The literal
 * {@code /feed} path wins over {@code MarketingAdminController}'s {@code /{provider}}.
 */
@RestController
@RequestMapping("/api/v1/admin/marketing/feed")
class FeedAdminController {

    record FeedRequest(Boolean enabled) {
    }

    private final FeedSettingsService feeds;
    private final TenantFeatures features;

    FeedAdminController(FeedSettingsService feeds, TenantFeatures features) {
        this.feeds = feeds;
        this.features = features;
    }

    @PutMapping
    Map<String, Object> update(@RequestBody FeedRequest request) {
        requireEnabled();
        if (request.enabled() == null) {
            throw ApiException.badRequest("enabled: is required");
        }
        return ApiResponse.of("feed", feeds.setEnabled(request.enabled()));
    }

    @PostMapping("/rotate")
    Map<String, Object> rotate() {
        requireEnabled();
        return ApiResponse.of("feed", feeds.rotate());
    }

    private void requireEnabled() {
        if (!features.enabled(Feature.MARKETING)) {
            throw ApiException.notFound("Not found");
        }
    }
}
```

`MarketingAdminController.java` — add the import `com.sixpack.marketing.application.FeedSettingsService`, then replace the fields/constructor and `all()`:

```java
    private final IntegrationService service;
    private final FeedSettingsService feeds;
    private final TenantFeatures features;

    MarketingAdminController(IntegrationService service, FeedSettingsService feeds, TenantFeatures features) {
        this.service = service;
        this.feeds = feeds;
        this.features = features;
    }

    @GetMapping
    Map<String, Object> all() {
        requireEnabled();
        return ApiResponse.of("integrations", service.all().stream().map(IntegrationView::from).toList(),
                "feed", feeds.view());
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest='FeedAdminIntegrationTest,MarketingAdminIntegrationTest,MarketingCrossTenantIntegrationTest'`
Expected: PASS (all; Phase 1 tests unchanged).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/audit/AuditAction.java \
        backend/src/main/java/com/sixpack/marketing/application/FeedSettingsService.java \
        backend/src/main/java/com/sixpack/marketing/web/FeedAdminController.java \
        backend/src/main/java/com/sixpack/marketing/web/MarketingAdminController.java \
        backend/src/test/java/com/sixpack/it/FeedAdminIntegrationTest.java
git commit -m "feat(marketing): store owners turn the product feed on and make a new feed URL

Audited as FEED_UPDATED and FEED_TOKEN_ROTATED; the token is never in the log.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A5 (phase plan 2.1, FeedIT): The public feed, cached per store

**Files:**
- Modify: `backend/src/main/java/com/sixpack/shared/tenant/TenantSettings.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/application/TenantSettingsService.java`
- Create: `backend/src/main/java/com/sixpack/marketing/application/CatalogFeedService.java`
- Modify: `backend/src/main/java/com/sixpack/marketing/application/FeedSettingsService.java`
- Create: `backend/src/main/java/com/sixpack/marketing/web/FeedController.java`
- Modify: `backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/sixpack/it/FeedEndpointIntegrationTest.java`, `backend/src/test/java/com/sixpack/it/FeedCrossTenantIntegrationTest.java`

**Interfaces:**
- Consumes: `CatalogReads` (A1), `FeedWriter` (A2), `FeedSettingsService.opens` (A4), `StoreConfig.currency()`, `TenantQuotas.maxProducts()`.
- Produces: `TenantSettings.name()`; `CatalogFeedService` (public) with `record RenderedFeed(byte[] body, String etag)`, `current()`, `evict()`, listener on `ProductChangedEvent`; `GET /api/v1/feeds/{token}/products.xml` (public).

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/sixpack/it/FeedEndpointIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.marketing.FeedXml;
import com.sixpack.shared.tenant.TenantLimits;
import com.sixpack.storage.TestImages;
import com.sixpack.tenant.domain.Tenant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/** The public product feed Google Merchant Center and the Meta catalogue fetch. */
class FeedEndpointIntegrationTest extends FeedIntegrationTest {

    Tenant acme;
    String admin;
    String token;

    @BeforeEach
    void storeWithAFeed() throws Exception {
        acme = feedStore("acme", "Acme Shop", "USD", null);
        admin = adminTokenFor(acme);
        token = enableFeed(acme, admin);
    }

    private List<Map<String, String>> items(Tenant t, String feedToken) throws Exception {
        return FeedXml.items(feedBody(t, feedToken));
    }

    @Test
    void servesOneItemPerActiveVariantAndPerSimpleProduct() throws Exception {
        String mug = insertProduct(acme, simple("Mug", 250, 4)
                .append("description", "<p>Holds <b>tea</b> &amp; coffee.</p>"));
        String tee = insertProduct(acme, tee(variant("tee-s", "S", 499, 5, "ACTIVE"),
                variant("tee-m", "M", 549, 0, "ACTIVE")));

        MvcResult res = fetch(acme, token).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/rss+xml"))
                .andExpect(content().encoding("UTF-8"))
                .andExpect(header().string("Cache-Control", "max-age=900, public"))
                .andExpect(header().string("ETag", matchesPattern("\"[0-9a-f]{32}\"")))
                .andReturn();
        List<Map<String, String>> items = FeedXml.items(res.getResponse().getContentAsByteArray());

        assertThat(items).hasSize(3);
        assertThat(items.get(0)).containsExactly(
                entry("g:id", mug), entry("title", "Mug"), entry("description", "Holds tea & coffee."),
                entry("link", "https://shop.acme.test/product/" + mug),
                entry("g:image_link", "https://shop.acme.test/uploads/p/Mug.png"),
                entry("g:availability", "in stock"), entry("g:price", "250.00 USD"),
                entry("g:brand", "Acme Shop"), entry("g:condition", "new"));
        assertThat(items.get(1)).contains(entry("g:id", tee + "-tee-s"), entry("g:item_group_id", tee),
                entry("title", "Tee - S / Black"), entry("description", "Tee"),
                entry("link", "https://shop.acme.test/product/" + tee),
                entry("g:image_link", "https://shop.acme.test/uploads/p/tee-black.png"),
                entry("g:availability", "in stock"), entry("g:price", "499.00 USD"));
        assertThat(items.get(2)).contains(entry("g:id", tee + "-tee-m"), entry("g:item_group_id", tee),
                entry("g:availability", "out of stock"), entry("g:price", "549.00 USD"));
    }

    @Test
    void aClientWithTheCurrentETagGetsNotModified() throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        String etag = fetch(acme, token).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");

        mvc.perform(get(feedPath(token)).with(host(hostOf(acme))).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void leavesOutHiddenProductsDisabledVariantsAndProductsWithoutAnImage() throws Exception {
        insertProduct(acme, simple("Draft", 10, 1).append("status", "DRAFT"));
        insertProduct(acme, simple("Archived", 10, 1).append("status", "ARCHIVED"));
        insertProduct(acme, simple("No photo", 10, 1).append("photo", null));
        String legacy = insertProduct(acme, simple("Legacy", 10, 1).append("status", null));
        String tee = insertProduct(acme, tee(variant("tee-s", "S", 499, 5, "ACTIVE"),
                variant("tee-m", "M", 549, 3, "DISABLED")));

        assertThat(items(acme, token)).extracting(i -> i.get("g:id")).containsExactly(legacy, tee + "-tee-s");
    }

    @Test
    void escapesMarkupAndDropsCharactersXmlCannotCarry() throws Exception {
        insertProduct(acme, simple("x", 10, 1).append("name", "Tom & Jerry <\"Mugs\">\u0001"));

        byte[] body = feedBody(acme, token);
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("Tom &amp; Jerry &lt;").doesNotContain("\u0001");
        assertThat(FeedXml.items(body).get(0).get("title")).isEqualTo("Tom & Jerry <\"Mugs\">");
    }

    @Test
    void listsNoMoreProductsThanTheStoreMayHold() throws Exception {
        Tenant small = feedStore("small", "Small", "INR", new TenantLimits(2, null, null));
        String smallToken = enableFeed(small, adminTokenFor(small));
        String one = insertProduct(small, simple("One", 1, 1));
        String two = insertProduct(small, simple("Two", 1, 1));
        insertProduct(small, simple("Three", 1, 1));

        assertThat(items(small, smallToken)).extracting(i -> i.get("g:id")).containsExactly(one, two);
    }

    @Test
    void theFeedIsCachedUntilAProductChanges() throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        assertThat(items(acme, token)).hasSize(1);
        insertProduct(acme, simple("Bowl", 120, 2)); // straight into the database: no product event
        assertThat(items(acme, token)).hasSize(1);

        mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .with(host(hostOf(acme))).param("name", "Plate").param("price", "90").param("stock", "5")
                        .param("category", "home").header("Authorization", bearer(admin)))
                .andExpect(status().isCreated());

        assertThat(items(acme, token)).extracting(i -> i.get("title")).containsExactly("Mug", "Bowl", "Plate");
    }

    @Test
    void turningTheFeedOffAndOnRebuildsIt() throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        assertThat(items(acme, token)).hasSize(1);
        insertProduct(acme, simple("Bowl", 120, 2));

        setFeed(acme, admin, false);
        setFeed(acme, admin, true);

        assertThat(items(acme, token)).hasSize(2);
    }

    @Test
    void anythingButTheRightTokenOnAnEnabledFeedIsAnEmptyNotFound() throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        String wrong = token.substring(0, 42) + (token.endsWith("A") ? "B" : "A");

        fetch(acme, wrong).andExpect(status().isNotFound()).andExpect(content().string(""));
        fetch(acme, "x").andExpect(status().isNotFound()).andExpect(content().string(""));

        setFeed(acme, admin, false);
        fetch(acme, token).andExpect(status().isNotFound());
        setFeed(acme, admin, true);
        fetch(acme, token).andExpect(status().isOk()); // the same address works again

        marketing(acme, false);
        fetch(acme, token).andExpect(status().isNotFound()).andExpect(content().string(""));
    }
}
```

`backend/src/test/java/com/sixpack/it/FeedCrossTenantIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.marketing.FeedXml;
import com.sixpack.tenant.domain.Tenant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One store's feed token, products and cache never reach another store. */
class FeedCrossTenantIntegrationTest extends FeedIntegrationTest {

    Tenant acme;
    Tenant globex;
    String acmeAdmin;
    String acmeFeed;
    String globexFeed;

    @BeforeEach
    void twoStoresWithFeeds() throws Exception {
        acme = feedStore("acme", "Acme Shop", "INR", null);
        globex = feedStore("globex", "Globex", "EUR", null);
        acmeAdmin = adminTokenFor(acme);
        acmeFeed = enableFeed(acme, acmeAdmin);
        globexFeed = enableFeed(globex, adminTokenFor(globex));
        insertProduct(acme, simple("Acme Anvil", 100, 3));
        insertProduct(globex, simple("Globex Gizmo", 50, 1));
    }

    private List<Map<String, String>> items(Tenant t, String feedToken) throws Exception {
        return FeedXml.items(feedBody(t, feedToken));
    }

    @Test
    void aStoresTokenOpensNothingOnAnotherStoresHost() throws Exception {
        fetch(globex, acmeFeed).andExpect(status().isNotFound());
        fetch(acme, globexFeed).andExpect(status().isNotFound());
    }

    @Test
    void eachFeedListsOnlyItsOwnStoresProductsAlsoFromCache() throws Exception {
        assertThat(items(acme, acmeFeed)).extracting(i -> i.get("title")).containsExactly("Acme Anvil");
        assertThat(items(globex, globexFeed)).extracting(i -> i.get("title")).containsExactly("Globex Gizmo");

        List<Map<String, String>> cached = items(acme, acmeFeed);
        assertThat(cached).extracting(i -> i.get("title")).containsExactly("Acme Anvil");
        assertThat(cached.get(0).get("link")).startsWith("https://shop.acme.test/");
        assertThat(cached.get(0).get("g:brand")).isEqualTo("Acme Shop");
        assertThat(cached.get(0).get("g:price")).isEqualTo("100.00 INR");
        assertThat(items(globex, globexFeed).get(0).get("g:price")).isEqualTo("50.00 EUR");
    }

    @Test
    void aStoreThatNeverTurnedItsFeedOnServesNothing() throws Exception {
        Tenant initech = feedStore("initech", "Initech", "INR", null);
        insertProduct(initech, simple("Stapler", 5, 1));

        fetch(initech, acmeFeed).andExpect(status().isNotFound());
    }

    @Test
    void oneStoresAdminCannotChangeAnotherStoresFeed() throws Exception {
        setFeed(globex, acmeAdmin, false).andExpect(status().isUnauthorized());
        rotate(globex, acmeAdmin).andExpect(status().isUnauthorized());

        fetch(globex, globexFeed).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='FeedEndpointIntegrationTest,FeedCrossTenantIntegrationTest'`
Expected: FAIL — fetches get `Status expected:<200> but was:<401>` (no public route yet); the 404 tests fail on `401`. `oneStoresAdminCannotChangeAnotherStoresFeed` stops at its `fetch` the same way.

- [ ] **Step 3: Implement**

`TenantSettings.java` — add after `slug()`:

```java
    /** The store's display name, as shoppers see it. */
    String name();
```

`TenantSettingsService.java` — add after `slug()`:

```java
    @Override
    public String name() {
        return tenant().name();
    }
```

`backend/src/main/java/com/sixpack/marketing/application/CatalogFeedService.java`:

```java
package com.sixpack.marketing.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sixpack.product.CatalogReads;
import com.sixpack.product.ProductChangedEvent;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantQuotas;
import com.sixpack.shared.tenant.TenantSettings;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Renders the current store's product feed and keeps it for 15 minutes or until its catalogue changes. */
@Service
public class CatalogFeedService {

    private static final Logger log = LoggerFactory.getLogger(CatalogFeedService.class);

    /** One store's rendered feed; {@code etag} is a hash of the body. */
    public record RenderedFeed(byte[] body, String etag) {
    }

    private final CatalogReads catalog;
    private final TenantSettings tenant;
    private final StoreConfig store;
    private final TenantQuotas quotas;
    // Not a CacheConfig cache: the size cap weighs this module's feed bytes. Keyed by the bound store, fail-closed;
    // get() renders once per store even when Google and Meta fetch at the same moment.
    private final Cache<String, RenderedFeed> feeds = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumWeight(64L * 1024 * 1024)
            .weigher((String tenantId, RenderedFeed feed) -> feed.body().length)
            .build();

    CatalogFeedService(CatalogReads catalog, TenantSettings tenant, StoreConfig store, TenantQuotas quotas) {
        this.catalog = catalog;
        this.tenant = tenant;
        this.store = store;
        this.quotas = quotas;
    }

    public RenderedFeed current() {
        return feeds.get(TenantContext.require().id(), id -> render());
    }

    @EventListener
    public void onProductChanged(ProductChangedEvent event) {
        evict();
    }

    /** Drops the current store's feed; with no store bound, every store's (each is rebuilt on its next fetch). */
    public void evict() {
        TenantContext.current().ifPresentOrElse(t -> feeds.invalidate(t.id()), feeds::invalidateAll);
    }

    private RenderedFeed render() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        FeedWriter feed = new FeedWriter(out, tenant.name(), "https://" + tenant.primaryDomain(), store.currency());
        catalog.forEachListing(quotas.maxProducts(), feed::add);
        feed.finish();
        log.info("Product feed rendered: {} item(s), {} left out without an image", feed.items(), feed.skipped());
        byte[] body = out.toByteArray();
        return new RenderedFeed(body, FeedWriter.sha256(body).substring(0, 32));
    }
}
```

`FeedSettingsService.java` — drop the store's cached feed whenever its settings change (so turning the feed off and on also forces a rebuild). Replace the fields/constructor:

```java
    private final MarketingSettingsRepository settings;
    private final TenantSettings tenant;
    private final AuditTrail audit;
    private final CatalogFeedService catalogFeed;

    FeedSettingsService(MarketingSettingsRepository settings, TenantSettings tenant, AuditTrail audit,
                        CatalogFeedService catalogFeed) {
        this.settings = settings;
        this.tenant = tenant;
        this.audit = audit;
        this.catalogFeed = catalogFeed;
    }
```

in `setEnabled`, inside the `if (before.feedEnabled() != after.feedEnabled())` block, after `audit.record(…);` add:

```java
            catalogFeed.evict();
```

and in `rotate`, after `audit.record(…);` add:

```java
        catalogFeed.evict();
```

`backend/src/main/java/com/sixpack/marketing/web/FeedController.java`:

```java
package com.sixpack.marketing.web;

import com.sixpack.marketing.application.CatalogFeedService;
import com.sixpack.marketing.application.CatalogFeedService.RenderedFeed;
import com.sixpack.marketing.application.FeedSettingsService;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product feed Google Merchant Center and the Meta catalogue fetch. Public: the secret token in the path and
 * the store the host resolves to must both match. Every miss is the same empty 404, so a wrong guess, a feed that
 * is off and a store without the feature look alike, and nothing is echoed back.
 */
@RestController
class FeedController {

    private static final MediaType RSS = MediaType.parseMediaType("application/rss+xml;charset=UTF-8");
    private static final CacheControl FIFTEEN_MINUTES = CacheControl.maxAge(Duration.ofMinutes(15)).cachePublic();

    private final FeedSettingsService feeds;
    private final CatalogFeedService catalog;
    private final TenantFeatures features;

    FeedController(FeedSettingsService feeds, CatalogFeedService catalog, TenantFeatures features) {
        this.feeds = feeds;
        this.catalog = catalog;
        this.features = features;
    }

    @GetMapping("/api/v1/feeds/{token}/products.xml")
    ResponseEntity<byte[]> products(@PathVariable String token) {
        if (!features.enabled(Feature.MARKETING) || !feeds.opens(token)) {
            return ResponseEntity.notFound().build();
        }
        RenderedFeed feed = catalog.current();
        // Spring answers 304 itself when If-None-Match carries this ETag.
        return ResponseEntity.ok().contentType(RSS).eTag(feed.etag()).cacheControl(FIFTEEN_MINUTES)
                .body(feed.body());
    }
}
```

`SecurityConfig.java` — after `.requestMatchers(GET, "/api/v1/tenant/config").permitAll()` add:

```java
                        // product feed for Google and Meta: the secret token in the path is checked by the controller
                        .requestMatchers(GET, "/api/v1/feeds/*/products.xml").permitAll()
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='FeedEndpointIntegrationTest,FeedCrossTenantIntegrationTest,FeedAdminIntegrationTest,ModularityTests,AuthorizationIntegrationTest'`
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/shared/tenant/TenantSettings.java \
        backend/src/main/java/com/sixpack/tenant/application/TenantSettingsService.java \
        backend/src/main/java/com/sixpack/marketing/application/CatalogFeedService.java \
        backend/src/main/java/com/sixpack/marketing/application/FeedSettingsService.java \
        backend/src/main/java/com/sixpack/marketing/web/FeedController.java \
        backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java \
        backend/src/test/java/com/sixpack/it/FeedEndpointIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/FeedCrossTenantIntegrationTest.java
git commit -m "feat(marketing): per-store product feed for Google Merchant Center and Meta

Served at /api/v1/feeds/<token>/products.xml on the store's own host, cached
for 15 minutes per store with an ETag, rebuilt when a product changes.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A6: Rate limit, and the token out of traces and logs

**Files:**
- Modify: `backend/src/main/java/com/sixpack/shared/security/AuthRateLimitFilter.java`
- Modify: `backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/web/TenantObservationConvention.java`
- Test: `backend/src/test/java/com/sixpack/shared/security/AuthRateLimitFilterTest.java`, `backend/src/test/java/com/sixpack/tenant/web/TenantObservationConventionTest.java` (new), `backend/src/test/java/com/sixpack/it/FeedEndpointIntegrationTest.java`, `backend/src/test/java/com/sixpack/it/FeedAdminIntegrationTest.java`

**Interfaces:**
- Produces: `AuthRateLimitFilter(int limitPerMinute, JsonSecurityHandlers, Function<HttpServletRequest, String> bucket)` (bucket name or null = not limited; the old two-argument constructor keeps the login behaviour); spans' `http.url` reads `/api/v1/feeds/{token}/…`.

- [ ] **Step 1: Write the failing tests**

`AuthRateLimitFilterTest.java` — add a test and a static helper (the existing imports cover everything used):

```java
    @Test
    void aBucketCountsEveryMatchingRequestTogether() throws Exception {
        AuthRateLimitFilter feed = new AuthRateLimitFilter(2, new JsonSecurityHandlers(new ObjectMapper()),
                r -> r.getRequestURI().startsWith("/api/v1/feeds/") ? "feed" : null);

        assertThat(call(feed, "GET", "3.3.3.3", "/api/v1/feeds/a/products.xml").getStatus()).isEqualTo(200);
        assertThat(call(feed, "GET", "3.3.3.3", "/api/v1/feeds/b/products.xml").getStatus()).isEqualTo(200);
        // Another token in the path is still the same client: guessing tokens buys no fresh budget.
        assertThat(call(feed, "GET", "3.3.3.3", "/api/v1/feeds/c/products.xml").getStatus()).isEqualTo(429);
        assertThat(call(feed, "GET", "4.4.4.4", "/api/v1/feeds/c/products.xml").getStatus()).isEqualTo(200);
        assertThat(call(feed, "POST", "3.3.3.3", "/api/v1/user/login").getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse call(AuthRateLimitFilter f, String method, String ip, String uri)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }
```

`backend/src/test/java/com/sixpack/tenant/web/TenantObservationConventionTest.java`:

```java
package com.sixpack.tenant.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantObservationConventionTest {

    private final TenantObservationConvention convention = new TenantObservationConvention();

    private String httpUrl(String uri) {
        var context = new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri),
                new MockHttpServletResponse());
        for (KeyValue kv : convention.getHighCardinalityKeyValues(context)) {
            if (kv.getKey().equals("http.url")) {
                return kv.getValue();
            }
        }
        throw new AssertionError("no http.url");
    }

    @Test
    void aFeedTokenNeverReachesATrace() {
        assertThat(httpUrl("/api/v1/feeds/Zm9vYmFy_-1234567890/products.xml"))
                .isEqualTo("/api/v1/feeds/{token}/products.xml");
    }

    @Test
    void otherAddressesAreKept() {
        assertThat(httpUrl("/api/v1/admin/marketing/feed")).isEqualTo("/api/v1/admin/marketing/feed");
    }
}
```

Add to `FeedEndpointIntegrationTest`:

```java
    @Test
    void sixtyFetchesAMinutePerClientThenTooManyRequests() throws Exception {
        // The limiter counts per clock minute: start well inside one so all 62 calls share a window.
        while (System.currentTimeMillis() % 60_000 > 50_000) {
            Thread.sleep(250);
        }
        for (int i = 0; i < 60; i++) {
            mvc.perform(get(feedPath(token)).with(host(hostOf(acme))).with(from("10.6.6.6")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(feedPath(token)).with(host(hostOf(acme))).with(from("10.6.6.6")))
                .andExpect(status().isTooManyRequests());
        // Guessing another token buys no fresh budget.
        mvc.perform(get(feedPath("guess")).with(host(hostOf(acme))).with(from("10.6.6.6")))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get(feedPath(token)).with(host(hostOf(acme))).with(from("10.7.7.7")))
                .andExpect(status().isOk());
    }
```

Add to `FeedAdminIntegrationTest` — annotate the class with `@ExtendWith(OutputCaptureExtension.class)` (imports `org.junit.jupiter.api.extension.ExtendWith`, `org.springframework.boot.test.system.CapturedOutput`, `org.springframework.boot.test.system.OutputCaptureExtension`) and add (it passes at once; it guards against a future log line):

```java
    @Test
    void theTokenNeverReachesTheLogs(CapturedOutput output) throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        String first = enableFeed(acme, admin);
        fetch(acme, first).andExpect(status().isOk());
        String second = tokenOf(body(rotate(acme, admin).andExpect(status().isOk()).andReturn())
                .get("feed").get("url").asText());
        fetch(acme, first).andExpect(status().isNotFound());
        fetch(acme, second).andExpect(status().isOk());

        assertThat(output.getAll()).contains("Product feed rendered").doesNotContain(first).doesNotContain(second);
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='AuthRateLimitFilterTest,TenantObservationConventionTest,FeedEndpointIntegrationTest,FeedAdminIntegrationTest'`
Expected: FAIL — `COMPILATION ERROR … no suitable constructor found for AuthRateLimitFilter(int,JsonSecurityHandlers,(r)->…)`. (After Step 3's filter change alone, `aFeedTokenNeverReachesATrace` would still fail with the raw URI, and `sixtyFetchesAMinutePerClientThenTooManyRequests` with `Status expected:<429> but was:<200>`.)

- [ ] **Step 3: Implement**

`AuthRateLimitFilter.java` — add the import `java.util.function.Function`; replace the fields, constructor, `shouldNotFilter` and the key line:

```java
    private final int limitPerMinute;
    private final JsonSecurityHandlers handlers;
    private final Function<HttpServletRequest, String> bucket;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Limits each credential endpoint on its own. */
    AuthRateLimitFilter(int limitPerMinute, JsonSecurityHandlers handlers) {
        this(limitPerMinute, handlers, r -> "POST".equals(r.getMethod()) && LIMITED_PATHS.contains(r.getRequestURI())
                ? r.getRequestURI() : null);
    }

    /** @param bucket names the counter a request belongs to, or null for a request this filter does not limit */
    AuthRateLimitFilter(int limitPerMinute, JsonSecurityHandlers handlers,
                        Function<HttpServletRequest, String> bucket) {
        this.limitPerMinute = limitPerMinute;
        this.handlers = handlers;
        this.bucket = bucket;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return bucket.apply(request) == null;
    }
```

and in `doFilterInternal`:

```java
        String key = tenant + "|" + request.getRemoteAddr() + "|" + bucket.apply(request);
```

`SecurityConfig.java` — add the import `java.util.regex.Pattern`, these constants at the top of the class:

```java
    /** Product feed fetches (Google, Meta) per minute, per client address and store, whatever the token. */
    private static final int FEED_REQUESTS_PER_MINUTE = 60;
    private static final Pattern FEED_PATH = Pattern.compile("^/api/v1/feeds/[^/]+/products\\.xml$");
```

after the existing `.addFilterBefore(new AuthRateLimitFilter(props.authRequestsPerMinute(), handlers), BearerTokenAuthenticationFilter.class)`:

```java
                .addFilterBefore(feedRateLimit(handlers), BearerTokenAuthenticationFilter.class)
```

and the factory method next to `passwordEncoder()`:

```java
    private static AuthRateLimitFilter feedRateLimit(JsonSecurityHandlers handlers) {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(FEED_REQUESTS_PER_MINUTE, handlers,
                r -> "GET".equals(r.getMethod()) && FEED_PATH.matcher(r.getRequestURI()).matches() ? "feed" : null);
        // Its own OncePerRequestFilter marker, so it is never mistaken for the login limiter.
        filter.setBeanName("feedRateLimitFilter");
        return filter;
    }
```

`TenantObservationConvention.java` — add the imports `java.util.regex.Pattern` (`KeyValue` is already imported) and:

```java
    private static final Pattern FEED_TOKEN = Pattern.compile("^/api/v1/feeds/[^/]+/");

    /** The product feed token is a credential: keep it out of every span's {@code http.url}. */
    @Override
    protected KeyValue httpUrl(ServerRequestObservationContext context) {
        KeyValue url = super.httpUrl(context);
        return KeyValue.of(url.getKey(), FEED_TOKEN.matcher(url.getValue()).replaceFirst("/api/v1/feeds/{token}/"));
    }
```

(If `httpUrl` is not overridable in this Spring version, override `getHighCardinalityKeyValues` and replace the `http.url` key there instead; the test stays the same.)

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='AuthRateLimitFilterTest,TenantObservationConventionTest,FeedEndpointIntegrationTest,FeedAdminIntegrationTest,AuthIntegrationTest,TenantObservabilityIntegrationTest'`
Expected: PASS (all; the login limiter tests are unchanged).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/shared/security/AuthRateLimitFilter.java \
        backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java \
        backend/src/main/java/com/sixpack/tenant/web/TenantObservationConvention.java \
        backend/src/test/java/com/sixpack/shared/security/AuthRateLimitFilterTest.java \
        backend/src/test/java/com/sixpack/tenant/web/TenantObservationConventionTest.java \
        backend/src/test/java/com/sixpack/it/FeedEndpointIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/FeedAdminIntegrationTest.java
git commit -m "feat(marketing): rate-limit the product feed and keep its token out of traces

60 fetches a minute per client and store, whatever token the URL carries.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A7: Docs — contract, call budget, audit events

**Files:**
- Modify: `docs/foundations/contracts.md`
- Modify: `docs/foundations/requirements.md`

(The admin help-centre guide is `ecommerce-frontend/src/help/adminGuides.ts` and is written in Part B Task B4.)

- [ ] **Step 1: `contracts.md` — data model row**

After the table row that starts ``| `marketing_integrations` (new, marketing phase 1, ADR-028) |``, add:

```markdown
| `marketing_settings` (new, marketing phase 2) | `_id` (= `tenantId`, so one row per store), `tenantId`, `feedEnabled`, `feedToken` (32 random bytes, URL-safe base64 without padding; created on the first enable, kept while the feed is off, replaced by "New URL"), `updatedAt`. The token is a credential: it never appears in audit entries, logs, traces, error messages or the offboarding export (`TenantExport` leaves the field out). Purged with the store |
```

- [ ] **Step 2: `contracts.md` — the Marketing GET row and the "later phases" line**

Replace the row ``| `GET /admin/marketing` | 🛡 | — | `{success:true, integrations:[google, meta]}`: always both, `google` first |`` with:

```markdown
| `GET /admin/marketing` | 🛡 | — | `{success:true, integrations:[google, meta], feed:{enabled, url}}`: always both integrations, `google` first; `feed` as in "Marketing product feed" below |
```

Replace `Later phases (feed, test event, delivery log, OAuth) add endpoints listed in the design spec §7.` with:

```markdown
Later phases (test event, delivery log, OAuth) add endpoints listed in the design spec §7.

### Marketing product feed (phase 2)
One feed per store that Google Merchant Center and the Meta catalogue both read. The admin routes follow the
Marketing rules above (🛡 tenant `ADMIN`, 404 while the store's `marketing` switch is off). `feed` is
`{enabled, url}`; `url` is `https://<store's first domain>/api/v1/feeds/<token>/products.xml` while the feed is on,
else `null` (also `null` for a store without a domain).

| Method & path | Auth | Request | Response |
|---|---|---|---|
| `PUT /admin/marketing/feed` | 🛡 | `{enabled:boolean}` | `{success:true, feed}`. The first enable creates the token; turning the feed off keeps it, so turning it on again restores the same URL. 400 `enabled: is required`. Audited as `FEED_UPDATED` when `enabled` changes |
| `POST /admin/marketing/feed/rotate` | 🛡 | — | `{success:true, feed}` with a new token; the old URL answers 404 at once. 400 `feed: enable it first` while the feed is off. Audited as `FEED_TOKEN_ROTATED` |
| `GET /feeds/{token}/products.xml` | 🔓 | optional `If-None-Match` | 200 `application/rss+xml;charset=UTF-8`, Google RSS 2.0 with the `g:` namespace, `ETag`, `Cache-Control: max-age=900, public`; 304 when `If-None-Match` matches. An empty 404 unless `marketing` is on, the feed is on and `{token}` equals the token of the store the host resolves to (constant-time compare). 429 after 60 requests a minute from one client address on one store, whatever the token |

Items: one per active variant (`g:item_group_id` = product id) or per product without variants, with `g:id` (product
id, or `<product id>-<variant id>`, the variant part hashed when the whole would pass 50 characters), `title` (name,
plus ` - <variant label>`; at most 150), `description` (HTML stripped; the name when empty; at most 5000), `link`
(`https://<domain>/product/<id>`), `g:image_link` (absolute on the store's domain: the variant's colour image, else
the product photo), `g:availability` (`in stock`/`out of stock`), `g:price` (`<amount with 2 decimals> <store
currency>`), `g:brand` (store name) and `g:condition` `new`. Only `ACTIVE` products, oldest first, at most the store's
product limit; products without an image and disabled variants are left out. Rendered one product at a time from a
cursor with a streaming XML writer, cached per store for 15 minutes; a product change or a feed setting change drops
the store's copy.

Audit: entity type `marketing_settings`, entity id `feed`. `FEED_UPDATED` holds `enabled` before/after and `feedToken`
only as "changed" (no value) when a token was created; `FEED_TOKEN_ROTATED` holds `feedToken` as "changed" only.

**Call budgets:**

| Screen / action | Calls | Budget |
|---|---|---|
| Admin → Marketing, first load | `feed` inside the existing `GET /admin/marketing` | +0 |
| Turn the feed on or off | `PUT /admin/marketing/feed` (returns `feed`) | 1 |
| New feed URL | `POST /admin/marketing/feed/rotate` (returns `feed`) | 1 |
| Copy the feed URL | — (clipboard) | 0 |
| Google / Meta fetch | `GET /feeds/{token}/products.xml`, 304 while unchanged | — |
```

- [ ] **Step 3: `requirements.md` — audit events and the feature**

In FR13 replace ``marketing integrations: `INTEGRATION_UPDATED`, `INTEGRATION_DISCONNECTED`)`` with:

```markdown
marketing integrations: `INTEGRATION_UPDATED`, `INTEGRATION_DISCONNECTED`; product feed: `FEED_UPDATED`, `FEED_TOKEN_ROTATED`)
```

After the line that starts `- [ ] FR-marketing (phases 0–1, ADR-028):`, add:

```markdown
- [ ] FR-marketing-feed (phase 2, ADR-028): A store owner turns on one product feed on the Marketing page and pastes its URL into Google Merchant Center and Meta Commerce Manager. The feed lists the store's active products, one entry per active size or colour, with the price in the store's currency, stock, picture and product link on the store's own domain; products without a picture are left out. The URL carries a random secret; "New URL" replaces it and the old URL stops working at once. Only the store the request's domain belongs to, with the right secret, gets its feed; anything else is "not found". Turning the feed on or off and making a new URL are audited (`FEED_UPDATED`, `FEED_TOKEN_ROTATED`) without the secret, which is also kept out of logs, traces and the store's export. The Marketing page still loads in one call; each change is one call. A client may fetch a store's feed 60 times a minute.
```

- [ ] **Step 4: Check the docs render and commit**

Run: `git diff --stat docs/foundations` — expect only `contracts.md` and `requirements.md`.

```bash
git add docs/foundations/contracts.md docs/foundations/requirements.md
git commit -m "docs(marketing): product feed contract, call budget and audit events

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task A8: Full backend verification

**Files:** none.

- [ ] **Step 1: Run everything**

Run: `cd backend && mvn -q verify`
Expected: exit code 0. All new classes run: `FeedWriterTest`, `MarketingSettingsTest`, `AuthRateLimitFilterTest`, `TenantObservationConventionTest`, `CatalogReadsIntegrationTest`, `MarketingSettingsIntegrationTest`, `FeedAdminIntegrationTest`, `FeedEndpointIntegrationTest`, `FeedCrossTenantIntegrationTest`, plus the extended `TenantMongoIntegrationTest`, `TenantOffboardingIntegrationTest`; `ModularityTests` and `TenantIsolationArchitectureTest` stay green.

- [ ] **Step 2: Check the feed by hand (optional, local)**

With the stack running and the `marketing` feature on for the default store, `PUT /api/v1/admin/marketing/feed {"enabled":true}` as the admin, then `curl -s http://localhost:8080/api/v1/feeds/<token>/products.xml | head` shows the RSS header and items. Paste the XML into Google's feed validator only from a public test store.

- [ ] **Step 3: Report**

Report the commit list (`git log --oneline feat/marketing--feed..HEAD`) and ask before pushing or merging into `feat/marketing--feed`.

---

# Part B: Frontend

Part B relies only on the Fixed API contract; every request is answered by a mocked `fetch`.

### Task B0: Baseline

**Files:** none.

- [ ] **Step 1: Worktree, branch, install**

```bash
git -C /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing-feed worktree add ../6Pack-marketing-feed-fe -b feat/marketing--feed--frontend feat/marketing--feed
cd /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing-feed-fe/ecommerce-frontend
[ -d node_modules ] || npm ci
```

- [ ] **Step 2: Record the baseline**

Run: `npm test && npm run lint && npm run build`
Expected: every test file passes (note the counts), lint prints nothing, build ends `✓ built in …` (Sass `@import` deprecation warnings are pre-existing). If anything fails here, stop and report it.

---

### Task B1: Copy to the clipboard, with a fallback

**Files:**
- Create: `ecommerce-frontend/src/utils/clipboard.ts`
- Test: `ecommerce-frontend/src/utils/clipboard.test.ts`

**Interfaces:**
- Produces: `copyText(text: string): Promise<boolean>`.

- [ ] **Step 1: Write the failing test**

`ecommerce-frontend/src/utils/clipboard.test.ts`:

```ts
// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { copyText } from "./clipboard";

const URL_TEXT = "https://shop.test/api/v1/feeds/abc/products.xml";
const setClipboard = (value: unknown) => Object.defineProperty(navigator, "clipboard", { value, configurable: true });
const setExecCommand = (fn: (command: string) => boolean) =>
  Object.defineProperty(document, "execCommand", { value: fn, configurable: true, writable: true });

afterEach(() => {
  Reflect.deleteProperty(navigator, "clipboard");
  Reflect.deleteProperty(document, "execCommand");
});

describe("copyText", () => {
  it("uses the Clipboard API when the browser offers it", async () => {
    const writeText = vi.fn(async () => undefined);
    setClipboard({ writeText });
    expect(await copyText(URL_TEXT)).toBe(true);
    expect(writeText).toHaveBeenCalledWith(URL_TEXT);
  });

  it("falls back to a hidden text box when there is no Clipboard API", async () => {
    setClipboard(undefined);
    let copied: string | undefined;
    setExecCommand((command) => {
      copied = command === "copy" ? document.querySelector("textarea")?.value : undefined;
      return true;
    });
    expect(await copyText(URL_TEXT)).toBe(true);
    expect(copied).toBe(URL_TEXT);
    expect(document.querySelector("textarea")).toBeNull();
  });

  it("falls back when the browser refuses the Clipboard API", async () => {
    setClipboard({ writeText: vi.fn(async () => { throw new Error("NotAllowedError"); }) });
    const exec = vi.fn(() => true);
    setExecCommand(exec);
    expect(await copyText(URL_TEXT)).toBe(true);
    expect(exec).toHaveBeenCalledWith("copy");
  });

  it("reports failure when nothing can copy", async () => {
    setClipboard(undefined);
    setExecCommand(() => false);
    expect(await copyText(URL_TEXT)).toBe(false);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/utils/clipboard.test.ts`
Expected: FAIL — `Failed to resolve import "./clipboard"`.

- [ ] **Step 3: Implement**

`ecommerce-frontend/src/utils/clipboard.ts`:

```ts
/**
 * Copies `text` to the clipboard. Falls back to a hidden text box and the old copy command where the Clipboard API
 * is missing (plain http, older browsers) or refused. Returns whether it worked.
 */
export const copyText = async (text: string): Promise<boolean> => {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const box = document.createElement("textarea");
    box.value = text;
    box.setAttribute("readonly", "");
    box.style.position = "fixed";
    box.style.opacity = "0";
    document.body.append(box);
    box.select();
    try {
      return document.execCommand("copy");
    } catch {
      return false;
    } finally {
      box.remove();
    }
  }
};
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/utils/clipboard.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend/src/utils/clipboard.ts ecommerce-frontend/src/utils/clipboard.test.ts
git commit -m "feat(admin): copy text to the clipboard, with a fallback for older browsers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B2: The Product feed card on the Marketing page

**Files:**
- Modify: `ecommerce-frontend/src/redux/api/marketingAPI.ts`
- Create: `ecommerce-frontend/src/redux/api/feedAPI.ts`
- Create: `ecommerce-frontend/src/pages/admin/apps/FeedCard.tsx`
- Modify: `ecommerce-frontend/src/pages/admin/apps/marketing.tsx`
- Test: `ecommerce-frontend/src/pages/admin/apps/FeedCard.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/admin/marketing` → `feed`, `PUT …/feed`, `POST …/feed/rotate` (contract); `copyText` (B1).
- Produces: `type Feed = { enabled: boolean; url: string | null }`, `MarketingResponse.feed`; `useSetFeedMutation(enabled: boolean)`, `useRotateFeedMutation()`; `<FeedCard feed={…} />`.

- [ ] **Step 1: Write the failing test**

`ecommerce-frontend/src/pages/admin/apps/FeedCard.test.tsx`:

```tsx
// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { configureStore } from "@reduxjs/toolkit";
import { Provider } from "react-redux";
import { MemoryRouter } from "react-router-dom";
import { marketingAPI } from "../../../redux/api/marketingAPI";
import { button, cleanup, click, field, queryButton, render, settle } from "../../../test/dom";
import Marketing from "./marketing";

// RTK Query builds a Request from the base URL, and Node's Request needs an absolute one.
vi.mock("../../../redux/config", () => ({ server: "http://shop.test" }));

const OLD_URL = `https://shop.test/api/v1/feeds/${"a".repeat(43)}/products.xml`;
const NEW_URL = `https://shop.test/api/v1/feeds/${"b".repeat(43)}/products.xml`;
const CARDS = [
  { provider: "google", status: "NOT_CONNECTED", updatedAt: null,
    browser: { ga4MeasurementId: null, adsConversionId: null, adsPurchaseLabel: null, gtmContainerId: null } },
  { provider: "meta", status: "NOT_CONNECTED", updatedAt: null, browser: { pixelId: null } },
];

type Reply = { status: number; body: unknown };
let feed: { enabled: boolean; url: string | null } | undefined;
let handlers: Record<string, () => Reply>;
let calls: { key: string; body?: unknown }[];

const json = ({ status, body }: Reply) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  calls = [];
  feed = { enabled: false, url: null };
  handlers = {
    "GET /api/v1/admin/marketing": () => ({
      status: 200, body: { success: true, integrations: CARDS, ...(feed ? { feed } : {}) },
    }),
  };
  vi.stubGlobal("fetch", vi.fn(async (req: Request) => {
    const key = `${req.method} ${new URL(req.url).pathname}`;
    const text = req.method === "GET" ? "" : await req.clone().text();
    calls.push({ key, body: text ? JSON.parse(text) : undefined });
    const handler = handlers[key];
    return json(handler ? handler() : { status: 404, body: { success: false, message: "Not found" } });
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  Reflect.deleteProperty(navigator, "clipboard");
});

const show = async () => {
  const store = configureStore({
    reducer: { [marketingAPI.reducerPath]: marketingAPI.reducer },
    middleware: (getDefault) => getDefault().concat(marketingAPI.middleware),
  });
  await render(
    <Provider store={store}>
      <MemoryRouter initialEntries={["/admin/app/marketing"]}>
        <Marketing />
      </MemoryRouter>
    </Provider>,
  );
  await settle();
};

const card = () => document.querySelector<HTMLElement>('section[aria-labelledby="marketing-feed"]');
const feedCard = () => {
  const found = card();
  if (!found) throw new Error("No feed card");
  return found;
};
const toggle = () => field("Publish my products in a feed", feedCard());
const badge = () => feedCard().querySelector(".badge")?.textContent;

describe("Product feed card", () => {
  it("shows the feed off, from the Marketing page's one request", async () => {
    await show();
    expect(calls.map((c) => c.key)).toEqual(["GET /api/v1/admin/marketing"]);
    expect(toggle().checked).toBe(false);
    expect(badge()).toBe("Inactive");
    expect(queryButton("Copy", feedCard())).toBeNull();
    expect(feedCard().querySelector('a[href="/admin/help/product-feed"]')).not.toBeNull();
  });

  it("turns the feed on with one request and shows the address it returns", async () => {
    handlers["PUT /api/v1/admin/marketing/feed"] = () => ({
      status: 200, body: { success: true, feed: { enabled: true, url: OLD_URL } },
    });
    await show();
    await click(toggle());
    await settle();
    expect(calls).toEqual([
      { key: "GET /api/v1/admin/marketing", body: undefined },
      { key: "PUT /api/v1/admin/marketing/feed", body: { enabled: true } },
    ]);
    expect(toggle().checked).toBe(true);
    expect(badge()).toBe("Active");
    expect(field("Feed address", feedCard()).value).toBe(OLD_URL);
    expect(feedCard().textContent).toContain("Merchant Center");
    expect(feedCard().textContent).toContain("Commerce Manager");
  });

  it("turns the feed off with one request", async () => {
    feed = { enabled: true, url: OLD_URL };
    handlers["PUT /api/v1/admin/marketing/feed"] = () => ({
      status: 200, body: { success: true, feed: { enabled: false, url: null } },
    });
    await show();
    await click(toggle());
    await settle();
    expect(calls[1]).toEqual({ key: "PUT /api/v1/admin/marketing/feed", body: { enabled: false } });
    expect(calls).toHaveLength(2);
    expect(toggle().checked).toBe(false);
    expect(() => field("Feed address", feedCard())).toThrow();
  });

  it("copies the address without calling the server", async () => {
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    feed = { enabled: true, url: OLD_URL };
    await show();
    await click(button("Copy", feedCard()));
    await settle();
    expect(writeText).toHaveBeenCalledWith(OLD_URL);
    expect(calls).toHaveLength(1);
  });

  it("asks before making a new address, then shows it without reloading the page", async () => {
    feed = { enabled: true, url: OLD_URL };
    handlers["POST /api/v1/admin/marketing/feed/rotate"] = () => ({
      status: 200, body: { success: true, feed: { enabled: true, url: NEW_URL } },
    });
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    await show();

    await click(button("New URL", feedCard()));
    expect(confirm.mock.calls[0][0]).toContain("stops working");
    expect(calls).toHaveLength(1);

    await click(button("New URL", feedCard()));
    await settle();
    expect(calls.map((c) => c.key)).toEqual([
      "GET /api/v1/admin/marketing", "POST /api/v1/admin/marketing/feed/rotate",
    ]);
    expect(field("Feed address", feedCard()).value).toBe(NEW_URL);
  });

  it("shows the server's message when a change fails and keeps the address", async () => {
    feed = { enabled: true, url: OLD_URL };
    handlers["POST /api/v1/admin/marketing/feed/rotate"] = () => ({
      status: 400, body: { success: false, message: "feed: enable it first" },
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    await show();
    await click(button("New URL", feedCard()));
    await settle();
    expect(feedCard().querySelector('[role="alert"]')?.textContent).toContain("feed: enable it first");
    expect(field("Feed address", feedCard()).value).toBe(OLD_URL);
  });

  it("is left out when the server sends no feed", async () => {
    feed = undefined;
    await show();
    expect(card()).toBeNull();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/pages/admin/apps/FeedCard.test.tsx`
Expected: FAIL — six tests with `Error: No feed card`; "is left out when the server sends no feed" passes.

- [ ] **Step 3: Implement**

`marketingAPI.ts` — replace the `MarketingResponse` line with:

```ts
/** The store's product feed (phase 2); `url` is null while it is off. */
export type Feed = { enabled: boolean; url: string | null };

export type MarketingResponse = { success: boolean; integrations: Integration[]; feed: Feed };
```

`ecommerce-frontend/src/redux/api/feedAPI.ts`:

```ts
import { Feed, marketingAPI } from "./marketingAPI";

export type FeedResponse = { success: boolean; feed: Feed };

/**
 * Admin → Marketing product feed, injected into the Marketing slice so it shares its cache: turning the feed on or
 * off and making a new address are one call each, and the returned feed replaces the cached one (no refetch).
 */
export const feedAPI = marketingAPI.injectEndpoints({
  endpoints: (builder) => ({
    setFeed: builder.mutation<FeedResponse, boolean>({
      query: (enabled) => ({ url: "feed", method: "PUT", body: { enabled } }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled;
          dispatch(putFeed(data.feed));
        } catch {
          // the card shows the error from the mutation result
        }
      },
    }),
    rotateFeed: builder.mutation<FeedResponse, void>({
      query: () => ({ url: "feed/rotate", method: "POST" }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled;
          dispatch(putFeed(data.feed));
        } catch {
          // the card shows the error from the mutation result
        }
      },
    }),
  }),
});

const putFeed = (feed: Feed) =>
  marketingAPI.util.updateQueryData("marketing", undefined, (draft) => {
    draft.feed = feed;
  });

export const { useSetFeedMutation, useRotateFeedMutation } = feedAPI;
```

`ecommerce-frontend/src/pages/admin/apps/FeedCard.tsx`:

```tsx
import { useState } from "react";
import toast from "react-hot-toast";
import { LuAlertCircle } from "react-icons/lu";
import { Link } from "react-router-dom";
import StatusBadge from "../../../components/admin/StatusBadge";
import { errorMessage } from "../../../redux/api/baseQuery";
import { useRotateFeedMutation, useSetFeedMutation } from "../../../redux/api/feedAPI";
import { Feed } from "../../../redux/api/marketingAPI";
import { copyText } from "../../../utils/clipboard";

const HELP = "/admin/help/product-feed";
const ROTATE_CONFIRM =
  "Make a new feed address? The current one stops working straight away, so paste the new one into Google Merchant Center and Meta Commerce Manager.";

/** Admin → Marketing: one product feed address for Google Merchant Center and the Meta catalogue. */
const FeedCard = ({ feed }: { feed: Feed }) => {
  const [setFeed, { isLoading: switching }] = useSetFeedMutation();
  const [rotateFeed, { isLoading: rotating }] = useRotateFeedMutation();
  const [error, setError] = useState<string | null>(null);
  const busy = switching || rotating;

  const toggle = async (enabled: boolean) => {
    setError(null);
    const res = await setFeed(enabled);
    if ("error" in res) return setError(errorMessage(res.error));
    toast.success(enabled ? "Product feed is on" : "Product feed is off");
  };

  const rotate = async () => {
    if (!confirm(ROTATE_CONFIRM)) return;
    setError(null);
    const res = await rotateFeed();
    if ("error" in res) return setError(errorMessage(res.error));
    toast.success("New feed address ready");
  };

  const copy = async () => {
    if (feed.url && (await copyText(feed.url))) toast.success("Feed address copied");
    else toast.error("Couldn't copy. Select the address and copy it yourself.");
  };

  return (
    <section className="card form-grid marketing-card" aria-labelledby="marketing-feed">
      <div className="span-2 marketing-card__head">
        <h2 id="marketing-feed">Product feed</h2>
        <StatusBadge status={feed.enabled ? "ACTIVE" : "INACTIVE"} />
      </div>
      <p className="span-2 store-settings__hint">
        One address that Google Merchant Center and the Meta catalogue read your active products from, with their
        price, stock, picture and page. <Link to={HELP}>How do I set it up?</Link>
      </p>
      {error && (
        <p className="span-2 field-error" role="alert">
          <LuAlertCircle aria-hidden /> {error}
        </p>
      )}
      <label className="span-2 check">
        <input type="checkbox" checked={feed.enabled} disabled={busy} onChange={(e) => toggle(e.target.checked)} />
        Publish my products in a feed
      </label>
      {feed.enabled && feed.url && (
        <>
          <label className="span-2" htmlFor="feed-url">
            Feed address
            <input id="feed-url" value={feed.url} readOnly spellCheck={false} onFocus={(e) => e.target.select()} />
          </label>
          <div className="span-2 store-settings__actions">
            <button type="button" className="btn btn--primary" onClick={copy}>
              Copy
            </button>
            <button type="button" className="btn" disabled={busy} onClick={rotate}>
              {rotating ? "Making a new URL…" : "New URL"}
            </button>
          </div>
          <div className="span-2 store-settings__hint">
            <p>
              Google Merchant Center: open Products, add a product source from a file, choose Enter a link to your
              file, paste the address and set it to fetch daily.
            </p>
            <p>
              Meta Commerce Manager: open your catalogue, then Data sources, add items with a data feed, choose a
              scheduled feed and paste the address.
            </p>
          </div>
        </>
      )}
    </section>
  );
};

export default FeedCard;
```

`marketing.tsx` — add the import after the `marketingForm` import:

```tsx
import FeedCard from "./FeedCard";
```

and mount the card right after the `PROVIDERS.map(…)` block, still inside `<div className="store-settings">`:

```tsx
        {data?.feed && <FeedCard feed={data.feed} />}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/pages/admin/apps/FeedCard.test.tsx src/pages/admin/apps/marketing.test.tsx`
Expected: PASS (7 new tests; the Phase 1 `marketing.test.tsx` is unchanged — its GET has no `feed`, so no card renders there).

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend/src/redux/api/marketingAPI.ts ecommerce-frontend/src/redux/api/feedAPI.ts \
        ecommerce-frontend/src/pages/admin/apps/FeedCard.tsx ecommerce-frontend/src/pages/admin/apps/FeedCard.test.tsx \
        ecommerce-frontend/src/pages/admin/apps/marketing.tsx
git commit -m "feat(marketing): product feed card with copy and new URL on the Marketing page

One request per change; the response updates the cached page, no refetch.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B3: Filter the audit log by feed changes

**Files:**
- Modify: `ecommerce-frontend/src/redux/api/auditAPI.ts`
- Test: `ecommerce-frontend/src/redux/api/auditAPI.test.ts`

- [ ] **Step 1: Write the failing test**

Add to the `describe("AUDIT_ACTIONS", …)` block in `auditAPI.test.ts`:

```ts
  it("lets the audit log be filtered by product feed changes", () => {
    expect(AUDIT_ACTIONS).toEqual(expect.arrayContaining(["FEED_UPDATED", "FEED_TOKEN_ROTATED"]));
  });
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/redux/api/auditAPI.test.ts`
Expected: FAIL in "lets the audit log be filtered by product feed changes" (`expected [...] to deeply equal ArrayContaining ["FEED_UPDATED", "FEED_TOKEN_ROTATED"]`).

- [ ] **Step 3: Implement**

In `auditAPI.ts`, add one line after `"INTEGRATION_UPDATED", "INTEGRATION_DISCONNECTED",`:

```ts
  "FEED_UPDATED", "FEED_TOKEN_ROTATED",
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/redux/api/auditAPI.test.ts`
Expected: PASS (3 tests, including "lists each action once").

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend/src/redux/api/auditAPI.ts ecommerce-frontend/src/redux/api/auditAPI.test.ts
git commit -m "feat(audit): filter the audit log by product feed changes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B4: Help guide — listing products on Google and Meta

**Files:**
- Modify: `ecommerce-frontend/src/help/adminGuides.ts`
- Test: `ecommerce-frontend/src/help/adminGuides.test.ts`

- [ ] **Step 1: Write the failing test**

Add to the `describe("admin guides", …)` block in `adminGuides.test.ts`:

```ts
  it("explains the product feed for both platforms", () => {
    const text = JSON.stringify(guideBySlug("product-feed"));
    expect(text).toContain("Merchant Center");
    expect(text).toContain("Commerce Manager");
    expect(text).toContain("New URL");
  });
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm test -- src/help/adminGuides.test.ts`
Expected: FAIL in "explains the product feed for both platforms" (`expected undefined to contain 'Merchant Center'`; `JSON.stringify(undefined)` is `undefined`).

- [ ] **Step 3: Implement**

In `adminGuides.ts`, add this guide right after the closing `},` of the `marketing` guide (before `slug: "audit-log"`), as its own object so a parallel edit of the `marketing` guide does not touch it:

```ts
  {
    slug: "product-feed",
    group: "Sales",
    title: "Listing your products on Google and Meta",
    summary: "Turn on the product feed and paste its address into Google Merchant Center and Meta Commerce Manager.",
    openLabel: { to: "/admin/app/marketing", label: "Open Marketing" },
    body: [
      { type: "p", text: "The product feed is one web address that lists your active products with their price, stock, picture and a link to their page. Google Merchant Center and Meta Commerce Manager read it on their own schedule, so your products can show in Google Shopping and in ads and shops on Facebook and Instagram. Each size or colour of a product is listed on its own and grouped under the product." },
      { type: "steps", title: "Turn it on", items: [
        "Go to Marketing and find the Product feed card.",
        "Tick Publish my products in a feed. The card shows the feed address.",
        "Click Copy to copy the address.",
      ] },
      { type: "steps", title: "Add it to Google Merchant Center", items: [
        "In Merchant Center, open Products and add a product source.",
        "Choose to add products from a file, then Enter a link to your file, and paste the feed address.",
        "Set it to fetch daily and save. Your products appear under Products after the first fetch.",
      ] },
      { type: "steps", title: "Add it to Meta Commerce Manager", items: [
        "In Commerce Manager, open your catalogue, then Data sources, and add items with a data feed.",
        "Choose a scheduled feed, paste the feed address and pick a daily or hourly update.",
        "Save. Meta lists any item it could not accept under Issues.",
      ] },
      { type: "p", text: "Only active products with a picture are listed; drafts, archived products and switched-off options are left out. Prices are in your store's currency and the brand is your store's name. A change shows up the next time Google or Meta fetch the feed; the feed itself is refreshed at most every 15 minutes." },
      { type: "warn", text: "Keep the address to yourself. If it has leaked, click New URL: the old address stops working at once, so paste the new one into Merchant Center and Commerce Manager straight away." },
      { type: "tip", text: "Turning the feed off makes the address stop working; turning it back on brings the same address back. Every change is recorded in the Audit log." },
    ],
    related: ["marketing", "products", "audit-log"],
  },
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npm test -- src/help/adminGuides.test.ts`
Expected: PASS (all, including "has unique slugs" and "links only to guides that exist").

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend/src/help/adminGuides.ts ecommerce-frontend/src/help/adminGuides.test.ts
git commit -m "docs(help): guide to listing products on Google and Meta with the product feed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task B5: Full frontend verification

**Files:** none.

- [ ] **Step 1: Run everything**

Run (from `ecommerce-frontend/`): `npm test && npm run lint && npm run build`
Expected: all test files pass (baseline + `clipboard.test.ts`, `FeedCard.test.tsx` and the new cases), lint prints nothing, build ends `✓ built in …`.

- [ ] **Step 2: Browser check (after Part A is merged into `feat/marketing--feed`)**

Run the app, open Admin → Marketing on a store with the `marketing` switch on: the Network tab shows exactly one `GET /api/v1/admin/marketing` on load, one `PUT …/feed` per toggle, one `POST …/feed/rotate` per New URL, nothing on Copy. Check the card at 375 px width (no horizontal scroll). Skip if Part A is not merged yet and say so.

- [ ] **Step 3: Report**

Report the commit list (`git log --oneline feat/marketing--feed..HEAD`) and ask before pushing or merging into `feat/marketing--feed`.

---

## Open risks (for the reviewer)

- **Brand.** The contract sets `g:brand` to the store name, but products already carry an optional `brand` field. A store reselling other brands' goods would send Google the wrong brand. Suggest `details.brand ?: store name` as a follow-up (backend-only, one line in `CatalogReads.Listing` + `FeedWriter`).
- **Item quality.** No `g:gtin`/`g:mpn`/`g:identifier_exists`, `g:color`, `g:size`, `g:google_product_category`: both platforms accept the feed, but Google may mark items "limited performance". Phase "done when" (no item errors in Merchant Center and Commerce Manager) still needs a manual check on a public test store.
- **Memory.** The rendered feed sits in memory (roughly 1 KB per item) and the cache holds up to 64 MB across stores; a store near the product limit with many variants could render tens of MB, and a single render holds one Caffeine bin lock while it runs.
- **Token in URLs.** The token is kept out of audit, app logs, traces and exports, but it is part of a URL: nginx access logs and any HTTP proxy in front of the platform still record it. Rotation is the remedy; scrubbing nginx logs is an ops follow-up.
- **Per-pod limits.** The 60/min limit and the cache are per backend instance, like the login limiter (ADR-007/008): with N replicas a client gets up to N×60 and each pod renders its own copy.
- **Store details changes** (name, currency, domain) reach the feed within 15 minutes, or at once when the owner turns the feed off and on.
- **Merge with Phase 3.** Small overlapping edits are expected in `AuditAction`, `MarketingAdminController` (constructor), `TenantExport`, `contracts.md` (GET row), `requirements.md` (FR13), `marketingAPI.ts` (`MarketingResponse`), `marketing.tsx` (mount line), `auditAPI.ts` and `adminGuides.ts`. Each is a few lines; resolve by keeping both sides.
- **Spring internals.** The 304 relies on Spring MVC's `ResponseEntity` ETag handling, and the trace masking on the protected `DefaultServerRequestObservationConvention.httpUrl`; both are pinned by tests, so an upgrade that changes them fails loudly.
