# Marketing Phase 0 + 1 — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A store admin saves and clears the store's own Google (GA4, Google Ads, GTM) and Meta Pixel IDs through `/api/v1/admin/marketing`, and the storefront receives them in `/api/v1/tenant/config` — all behind the per-store `marketing` switch and bound to one tenant.

**Architecture:** New Spring Modulith module `com.sixpack.marketing` (domain / application / infra / web, like `coupon`). Validation lives in a plain domain record (`BrowserTags`). Rows live in tenant-scoped `marketing_integrations` (via `TenantMongo` only). The `tenant` module reads the tags through a port in `shared/tenant` (`MarketingTags`) that `marketing` implements, so `tenant` never depends on `marketing`. The tag lookup is cached per tenant and evicted on every save.

**Tech Stack:** Java 21 / Spring Boot 3.5 / Spring Modulith 1.4 / Spring Data MongoDB (`TenantMongo`), Caffeine via `TenantCacheManager`; JUnit 5 + AssertJ + MockMvc + Testcontainers (Docker must be running), ArchUnit (already on the test classpath through Modulith).

**Spec:** `docs/superpowers/specs/2026-10-30-marketing-integrations-design.md` · **Phase plan:** `docs/superpowers/plans/2026-10-30-marketing-integrations.md` (this file details its Tasks 0.1, 1.1, 1.2, 1.3; the frontend tasks 0.2, 1.4, 1.5 have their own plan).

## Global Constraints

- **Branch.** Phase sub-branch per the phase plan: `feat/marketing--phase1-backend` cut from `feat/marketing`. If the frontend plan is executed at the same time, use your own worktree:
  `git -C /home/harsahavardhanap/Desktop/Harsha/Projects/Ecommerce-project/6Pack-marketing worktree add ../6Pack-marketing-be -b feat/marketing--phase1-backend feat/marketing`
  otherwise `git switch -c feat/marketing--phase1-backend` inside `6Pack-marketing`. Never commit on `dev`/`main`/`feat/marketing` directly; ask before push or merge.
- **Commands** run from `backend/`: one class `mvn -q test -Dtest=<Class>`, several `mvn -q test -Dtest='<A>,<B>'`, everything `mvn -q verify`. Integration tests need Docker (Testcontainers `mongo:7.0`).
- **Test class names end in `Test`/`Tests`.** The pom has no failsafe plugin, so surefire's default includes would silently skip a class named `*IT`. The ITs the contract calls `MarketingAdminIT` / `MarketingCrossTenantIT` / "TenantConfig IT" are `MarketingAdminIntegrationTest`, `MarketingCrossTenantIntegrationTest`, `MarketingTagsConfigIntegrationTest`.
- Every Mongo access through `TenantMongo`; the new document extends `TenantDocument`. No `MongoTemplate` in main code (`TenantIsolationArchitectureTest` enforces it).
- No Spring, Jackson or Mongo types in `com.sixpack.marketing.domain`.
- Errors go through `ApiException` → `GlobalExceptionHandler` (`{success:false, message}`); responses through `ApiResponse.of`.
- Out of scope here (later phases): `ERROR`/`DISABLED` status, secrets/encryption, outbox, feed, OAuth. No new dependencies.
- **Do not edit** `docs/foundations/*`, the spec, or the frontend — other agents own those in parallel.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Fixed API contract (the frontend plan relies on it — implement exactly)

| Call | Success | Errors |
|---|---|---|
| `GET /api/v1/admin/marketing` | `200 {success:true, integrations:[{provider:"google", status, browser:{ga4MeasurementId, adsConversionId, adsPurchaseLabel, gtmContainerId}, updatedAt}, {provider:"meta", status, browser:{pixelId}, updatedAt}]}` — always both, google first | feature off → 404 |
| `PUT /api/v1/admin/marketing/{provider}` body `{browser:{…}}` | `200 {success:true, integration:{…same card shape…}}` | invalid → `400 {success:false, message:"<field>: <reason>"}`; unknown provider → 404; feature off → 404 |
| `DELETE /api/v1/admin/marketing/{provider}` | `200 {success:true, integration:{provider, status:"NOT_CONNECTED", browser all null, updatedAt:null}}` | unknown provider → 404; feature off → 404 |
| `GET /api/v1/tenant/config` | adds `tenant.marketing = {tags:{ga4, adsId, adsPurchaseLabel, gtm, metaPixel}}` only when the feature is on **and** ≥1 ID is saved; otherwise the key is absent | — |

- `status` ∈ `NOT_CONNECTED` | `ACTIVE`. Unset IDs are `null` (present, not omitted). `updatedAt` is ISO-8601 UTC (`2026-10-30T10:00:00.123Z`) or `null`.
- PUT: values are trimmed, blank → `null`; all `null` → the row is deleted → `NOT_CONNECTED`. Fields of the other provider are ignored (not validated, not stored).
- All five tag keys are always present in `tenant.marketing.tags`; unset ones are `null`.
- Only the tenant's `ADMIN` role (403 otherwise); an admin who has not accepted the admin agreement gets the existing 403 `"Please accept the admin acceptable-use agreement to continue"`.
- **Exact 400 messages:**

| Field | Pattern | Message |
|---|---|---|
| `ga4MeasurementId` | `^G-[A-Z0-9]{4,12}$` | `ga4MeasurementId: must look like G-XXXXXXX` |
| `adsConversionId` | `^AW-\d{6,12}$` | `adsConversionId: must look like AW-123456789` |
| `adsPurchaseLabel` | `^[A-Za-z0-9_-]{1,40}$` | `adsPurchaseLabel: use 1-40 letters, digits, _ or -` |
| label without conversion ID | — | `adsPurchaseLabel: needs adsConversionId` |
| `gtmContainerId` | `^GTM-[A-Z0-9]{4,10}$` | `gtmContainerId: must look like GTM-XXXXXX` |
| `pixelId` | `^\d{10,20}$` (ASCII digits) | `pixelId: must be 10-20 digits` |
| body without `browser` | — | `browser: is required` |

- Audit: `INTEGRATION_UPDATED` on every successful PUT (also one that clears everything), `INTEGRATION_DISCONNECTED` on a DELETE that removed a row; entity type `marketing_integration`, entity id = provider key (`google`/`meta`), before/after diff of the five browser fields.

## Review Focus

1. **Cross-store leakage** — store A's IDs are never read, changed or removed through store B's host, admin or token; `/tenant/config` on B never contains A's IDs even right after A's tags were cached. Pinned by `MarketingCrossTenantIntegrationTest` (Tasks 3, 4) and `oneStoresTagsNeverAppearInAnothers` (Task 5).
2. **Script injection** — only pattern-matched IDs are ever stored or shipped; `<script>`, quotes, spaces, non-ASCII digits, lower-case, over-long values are rejected with a field-named 400, and nothing is written. Stored rows are re-validated when read. Pinned by `BrowserTagsTest` (Task 2) and `anInvalidIdIsRejectedNamingTheFieldAndNothingIsStored` (Task 4).
3. **Feature switch** — off → all three admin endpoints 404 and no `marketing` key in config. The switch is checked *outside* the tag cache, so turning it off takes effect at once. Pinned in Tasks 4 and 5.
4. **Stale tags** — a save or disconnect shows on the very next `/tenant/config` (cache evicted). Pinned by `aSaveOrDisconnectShowsOnTheNextConfig` (Task 5).
5. **Module direction** — `tenant` (and every other module) never depends on `marketing`. Pinned by `ModularityTests.nothingOutsideMarketingDependsOnIt` (Task 5).

## Changes from the phase plan / contract (decided while planning — read before starting)

- **Test names `*IntegrationTest`, not `*IT`** — see Global Constraints (surefire would skip `*IT`).
- **No `marketing/package-info.java` with `allowedDependencies`** (phase plan 0.1). No existing module declares one (only `shared`, as OPEN), and listing the OPEN `shared` module in `allowedDependencies` risks breaking `ModularityTests`. The module is the top-level package as for every other module; the "nothing depends on marketing" rule is enforced with an ArchUnit test instead (Task 5).
- **No `SecurityConfig` change** (phase plan 1.2). `/api/v1/admin/**` is already `hasRole(ADMIN)` and `ConsentWebConfig` applies `AdminAgreementInterceptor` to `/api/v1/**`. The ITs prove both.
- **Store A token on store B host → 401** (existing `TenantClaimValidator` `tid` check), which is what "rejected" means here.
- **`status` is not stored**; it is derived (a row exists only when ≥1 ID is set, so row ⇔ `ACTIVE`). Later phases add a stored status with `ERROR`.
- **Provider is stored as the enum name** (`"GOOGLE"`, `"META"`, as in spec §5.1); the wire uses the lower-case key.
- **New `BackfillTarget` for `marketing_integrations`** — the existing `TenantOffboardingIntegrationTest.everyTenantOwnedCollectionIsRegistered` requires every tenant collection to be registered so offboarding export/purge cover it. Phase 1 holds no secrets, so the export may include the rows.
- **`IntegrationTest.cleanDatabase` gains `marketing_integrations`** (one word in the shared base).
- **Feature check runs before body checks**, so an invalid-but-parseable body on a store without the feature still gets 404. An unparseable JSON body still gets Spring's 400 `Malformed request` before the controller runs.
- **A DELETE with nothing saved** returns 200 `NOT_CONNECTED` and writes no audit row.
- **Docs** (`docs/foundations/architecture.md` module row, `contracts.md` endpoints + call budget, `requirements.md` audit events) are **not** in this plan — the docs agent owns `docs/foundations/*`. The frontend's audit-action list (`ecommerce-frontend/src/redux/api/auditAPI.ts`) needs `INTEGRATION_UPDATED`, `INTEGRATION_DISCONNECTED` — frontend plan.

## File map

Main (`backend/src/main/java/com/sixpack/…`):

| File | Responsibility | Task |
|---|---|---|
| `shared/tenant/Feature.java` | `MARKETING("marketing")` | 1 |
| `../resources/application.yml` | `app.features.defaults.marketing: false` | 1 |
| `marketing/domain/Provider.java` | `GOOGLE`, `META`, wire key | 2 |
| `marketing/domain/BrowserTags.java` | five IDs, trim, patterns, per-provider filtering | 2 |
| `marketing/domain/Integration.java` | provider + tags + `updatedAt`, derived `status()` | 2 |
| `marketing/domain/IntegrationRepository.java` | port | 2 |
| `marketing/infra/mongo/MarketingIntegrationDocument.java` | `marketing_integrations`, unique `{tenantId, provider}` | 3 |
| `marketing/infra/mongo/MongoIntegrationRepository.java` | adapter over `TenantMongo` | 3 |
| `marketing/infra/mongo/MarketingBackfill.java` | `BackfillTarget` for offboarding | 3 |
| `audit/AuditAction.java` | `INTEGRATION_UPDATED`, `INTEGRATION_DISCONNECTED` | 4 |
| `marketing/application/MarketingAudit.java` | entity type, field allowlist, audit view | 4 |
| `marketing/application/IntegrationService.java` | list / save / disconnect (+ `MarketingTags` in Task 5) | 4, 5 |
| `marketing/web/MarketingAdminController.java` | the three admin routes | 4 |
| `shared/tenant/MarketingTags.java` | port + `Tags` record | 5 |
| `shared/config/CacheConfig.java` | `marketing.tags` cache | 5 |
| `tenant/web/TenantConfigController.java` | adds `marketing.tags` | 5 |

Tests (`backend/src/test/java/com/sixpack/…`): `it/TenantFeatureFlagsIntegrationTest.java` (1), `marketing/domain/BrowserTagsTest.java`, `marketing/domain/MarketingDomainTest.java` (2), `it/IntegrationTest.java` (cleanup), `it/MarketingIntegrationTest.java` (shared helpers), `it/MarketingCrossTenantIntegrationTest.java` (3, 4), `it/MarketingAdminIntegrationTest.java` (4), `it/MarketingTagsConfigIntegrationTest.java`, `ModularityTests.java` (5).

---

### Task 1 (phase plan 0.1): `marketing` feature switch, off by default

**Files:**
- Modify: `backend/src/main/java/com/sixpack/shared/tenant/Feature.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/sixpack/it/TenantFeatureFlagsIntegrationTest.java`

**Interfaces:**
- Produces: `Feature.MARKETING` (key `marketing`), platform default `app.features.defaults.marketing=false`; `GET /api/v1/tenant/config` → `tenant.features.marketing`; the platform tenant API accepts `features.marketing`.

- [ ] **Step 1: Write the failing test**

Add to `TenantFeatureFlagsIntegrationTest` (its imports already cover `get`, `jsonPath`, `status`, `Map`, `Tenant`):

```java
    @Test
    void marketingIsOffUntilAPlatformAdminTurnsItOnForAStore() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.tenant.features.marketing").value(false));

        setFeatures(acme, Map.of("marketing", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.features.marketing").value(true));

        mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.tenant.features.marketing").value(true));
        mvc.perform(get("/api/v1/tenant/config"))
                .andExpect(jsonPath("$.tenant.features.marketing").value(false));
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=TenantFeatureFlagsIntegrationTest`
Expected: FAIL in `marketingIsOffUntilAPlatformAdminTurnsItOnForAStore` with `No value at JSON path "$.tenant.features.marketing"`. The other five tests pass.

- [ ] **Step 3: Add the enum value and the platform default**

`Feature.java` — replace the constant list:

```java
    /** Products with size/colour options (docs/superpowers/plans/2026-10-18-product-variants.md). */
    VARIANTS("variants"),
    /** Admin → Marketing: Google and Meta tags (docs/superpowers/specs/2026-10-30-marketing-integrations-design.md). */
    MARKETING("marketing");
```

`application.yml` — under `app.features.defaults`, after `variants`:

```yaml
      # Admin → Marketing (Google and Meta tags). Off until a platform admin turns it on for a store.
      marketing: false
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest='TenantFeatureFlagsIntegrationTest,ModularityTests'`
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/shared/tenant/Feature.java backend/src/main/resources/application.yml \
        backend/src/test/java/com/sixpack/it/TenantFeatureFlagsIntegrationTest.java
git commit -m "feat(marketing): per-store marketing switch, off by default

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2 (phase plan 1.1): Domain — providers, tag IDs and their validation

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/domain/Provider.java`
- Create: `backend/src/main/java/com/sixpack/marketing/domain/BrowserTags.java`
- Create: `backend/src/main/java/com/sixpack/marketing/domain/Integration.java`
- Create: `backend/src/main/java/com/sixpack/marketing/domain/IntegrationRepository.java`
- Test: `backend/src/test/java/com/sixpack/marketing/domain/BrowserTagsTest.java`
- Test: `backend/src/test/java/com/sixpack/marketing/domain/MarketingDomainTest.java`

**Interfaces:**
- Produces:
  - `enum Provider { GOOGLE, META }` with `key(): String` (`"google"`, `"meta"`) and `static Optional<Provider> fromKey(String)` (exact, case-sensitive; `null` → empty). Declaration order = card order.
  - `record BrowserTags(String ga4MeasurementId, String adsConversionId, String adsPurchaseLabel, String gtmContainerId, String pixelId)` — compact constructor strips, blank → `null`, throws `IllegalArgumentException("<field>: <reason>")`; `static final NONE`; `static BrowserTags of(Provider, String ga4, String ads, String label, String gtm, String pixel)` keeps only that provider's fields; `boolean isEmpty()`.
  - `record Integration(Provider provider, BrowserTags browser, Instant updatedAt)` with `enum Status { NOT_CONNECTED, ACTIVE }`, `status()`, `static notConnected(Provider)`.
  - `interface IntegrationRepository { List<Integration> findAll(); Integration save(Integration); boolean delete(Provider); }`

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/sixpack/marketing/domain/BrowserTagsTest.java`:

```java
package com.sixpack.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BrowserTagsTest {

    @Test
    void acceptsWellFormedIds() {
        BrowserTags t = new BrowserTags("G-AB12CD3", "AW-123456789", "Purchase_label-1", "GTM-ABC123",
                "123456789012345");

        assertThat(t.ga4MeasurementId()).isEqualTo("G-AB12CD3");
        assertThat(t.adsConversionId()).isEqualTo("AW-123456789");
        assertThat(t.adsPurchaseLabel()).isEqualTo("Purchase_label-1");
        assertThat(t.gtmContainerId()).isEqualTo("GTM-ABC123");
        assertThat(t.pixelId()).isEqualTo("123456789012345");
        assertThat(t.isEmpty()).isFalse();
    }

    @Test
    void acceptsTheShortestAndLongestForms() {
        new BrowserTags("G-ABCD", "AW-123456", "a", "GTM-ABCD", "1234567890");
        new BrowserTags("G-ABCDEFGHIJ12", "AW-123456789012", "a".repeat(40), "GTM-ABCDEFGHIJ", "1".repeat(20));
    }

    @Test
    void trimsValuesAndTurnsBlankIntoNull() {
        BrowserTags t = new BrowserTags("  G-AB12CD3\t", "", "   ", null, " 1234567890 ");

        assertThat(t).isEqualTo(new BrowserTags("G-AB12CD3", null, null, null, "1234567890"));
    }

    @Test
    void allBlankIsEmpty() {
        assertThat(new BrowserTags(" ", "", null, "\n", null).isEmpty()).isTrue();
        assertThat(BrowserTags.NONE.isEmpty()).isTrue();
    }

    static Stream<Arguments> malformed() {
        String ga4 = "ga4MeasurementId: must look like G-XXXXXXX";
        String ads = "adsConversionId: must look like AW-123456789";
        String label = "adsPurchaseLabel: use 1-40 letters, digits, _ or -";
        String gtm = "gtmContainerId: must look like GTM-XXXXXX";
        String pixel = "pixelId: must be 10-20 digits";
        return Stream.of(
                Arguments.of("ga4MeasurementId", "G-x\"><script>alert(1)</script>", ga4),
                Arguments.of("ga4MeasurementId", "G-AB12CD3');alert(1);//", ga4),
                Arguments.of("ga4MeasurementId", "G-AB12CD3\n<script>", ga4),
                Arguments.of("ga4MeasurementId", "g-ab12cd3", ga4),
                Arguments.of("ga4MeasurementId", "G-ABC", ga4),
                Arguments.of("ga4MeasurementId", "G-ABCDEFGHIJ123", ga4),
                Arguments.of("ga4MeasurementId", "UA-12345-1", ga4),
                Arguments.of("adsConversionId", "AW-12345", ads),
                Arguments.of("adsConversionId", "AW-1234567890123", ads),
                Arguments.of("adsConversionId", "AW-123456789\" onload=\"x", ads),
                Arguments.of("adsConversionId", "123456789", ads),
                Arguments.of("adsPurchaseLabel", "has space", label),
                Arguments.of("adsPurchaseLabel", "a".repeat(41), label),
                Arguments.of("adsPurchaseLabel", "<b>", label),
                Arguments.of("gtmContainerId", "GTM-abc123", gtm),
                Arguments.of("gtmContainerId", "GTM-ABC", gtm),
                Arguments.of("gtmContainerId", "GTM-ABC123</script>", gtm),
                Arguments.of("pixelId", "1234567890123456789012345", pixel),
                Arguments.of("pixelId", "123456789", pixel),
                Arguments.of("pixelId", "1234567890 OR 1=1", pixel),
                // Arabic-Indic digits: \d must mean ASCII 0-9 only
                Arguments.of("pixelId", "١٢٣٤٥٦٧٨٩٠", pixel));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void rejectsMalformedIdsNamingTheField(String field, String value, String message) {
        assertThatThrownBy(() -> with(field, value))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void aPurchaseLabelNeedsAConversionId() {
        assertThatThrownBy(() -> new BrowserTags(null, null, "purchase", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("adsPurchaseLabel: needs adsConversionId");
        assertThatThrownBy(() -> new BrowserTags(null, "  ", "purchase", null, null))
                .hasMessage("adsPurchaseLabel: needs adsConversionId");
    }

    @Test
    void forAProviderOnlyItsOwnFieldsAreKeptAndTheOthersAreNotChecked() {
        BrowserTags meta = BrowserTags.of(Provider.META, "junk", "junk", "junk", "junk", "1234567890");
        assertThat(meta).isEqualTo(new BrowserTags(null, null, null, null, "1234567890"));

        BrowserTags google = BrowserTags.of(Provider.GOOGLE, "G-AB12CD3", null, null, null, "<script>");
        assertThat(google).isEqualTo(new BrowserTags("G-AB12CD3", null, null, null, null));
    }

    private static BrowserTags with(String field, String value) {
        return switch (field) {
            case "ga4MeasurementId" -> new BrowserTags(value, null, null, null, null);
            case "adsConversionId" -> new BrowserTags(null, value, null, null, null);
            case "adsPurchaseLabel" -> new BrowserTags(null, "AW-123456789", value, null, null);
            case "gtmContainerId" -> new BrowserTags(null, null, null, value, null);
            case "pixelId" -> new BrowserTags(null, null, null, null, value);
            default -> throw new AssertionError("unknown field " + field);
        };
    }
}
```

`backend/src/test/java/com/sixpack/marketing/domain/MarketingDomainTest.java`:

```java
package com.sixpack.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketingDomainTest {

    @Test
    void providersUseLowerCaseKeysWithGoogleFirst() {
        assertThat(Provider.values()).containsExactly(Provider.GOOGLE, Provider.META);
        assertThat(Provider.GOOGLE.key()).isEqualTo("google");
        assertThat(Provider.fromKey("google")).contains(Provider.GOOGLE);
        assertThat(Provider.fromKey("meta")).contains(Provider.META);
        assertThat(Provider.fromKey("GOOGLE")).isEmpty();
        assertThat(Provider.fromKey("tiktok")).isEmpty();
        assertThat(Provider.fromKey(null)).isEmpty();
    }

    @Test
    void anIntegrationWithIdsIsActiveAndWithoutIdsIsNotConnected() {
        Integration none = Integration.notConnected(Provider.META);
        assertThat(none.status()).isEqualTo(Integration.Status.NOT_CONNECTED);
        assertThat(none.browser()).isEqualTo(BrowserTags.NONE);
        assertThat(none.updatedAt()).isNull();

        Integration pixel = new Integration(Provider.META, new BrowserTags(null, null, null, null, "1234567890"),
                Instant.EPOCH);
        assertThat(pixel.status()).isEqualTo(Integration.Status.ACTIVE);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='BrowserTagsTest,MarketingDomainTest'`
Expected: FAIL — `COMPILATION ERROR … cannot find symbol … class BrowserTags` (and `Provider`, `Integration`).

- [ ] **Step 3: Write the domain**

`backend/src/main/java/com/sixpack/marketing/domain/Provider.java`:

```java
package com.sixpack.marketing.domain;

import java.util.Arrays;
import java.util.Optional;

/** A company the store sends data to: one admin card each, in this order. {@link #key()} is the URL/JSON name. */
public enum Provider {
    GOOGLE("google"),
    META("meta");

    private final String key;

    Provider(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<Provider> fromKey(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
    }
}
```

`backend/src/main/java/com/sixpack/marketing/domain/BrowserTags.java`:

```java
package com.sixpack.marketing.domain;

import java.util.regex.Pattern;

/**
 * The public tag IDs a store pastes into Admin → Marketing. Each one is later written into a {@code <script>} call
 * on the storefront, so it must match a strict pattern (spec §5.1) before it is kept. Values are stripped; blank
 * means "not set" (null). Google uses the first four fields, Meta only {@code pixelId}.
 */
public record BrowserTags(String ga4MeasurementId, String adsConversionId, String adsPurchaseLabel,
                          String gtmContainerId, String pixelId) {

    // Declared before NONE: static fields initialise in order and NONE runs the constructor.
    private static final Pattern GA4 = Pattern.compile("^G-[A-Z0-9]{4,12}$");
    private static final Pattern ADS = Pattern.compile("^AW-\\d{6,12}$");
    private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private static final Pattern GTM = Pattern.compile("^GTM-[A-Z0-9]{4,10}$");
    private static final Pattern PIXEL = Pattern.compile("^\\d{10,20}$");

    public static final BrowserTags NONE = new BrowserTags(null, null, null, null, null);

    public BrowserTags {
        ga4MeasurementId = check("ga4MeasurementId", ga4MeasurementId, GA4, "must look like G-XXXXXXX");
        adsConversionId = check("adsConversionId", adsConversionId, ADS, "must look like AW-123456789");
        adsPurchaseLabel = check("adsPurchaseLabel", adsPurchaseLabel, LABEL, "use 1-40 letters, digits, _ or -");
        gtmContainerId = check("gtmContainerId", gtmContainerId, GTM, "must look like GTM-XXXXXX");
        pixelId = check("pixelId", pixelId, PIXEL, "must be 10-20 digits");
        if (adsPurchaseLabel != null && adsConversionId == null) {
            throw new IllegalArgumentException("adsPurchaseLabel: needs adsConversionId");
        }
    }

    /** Only the fields that belong to {@code provider}; the other provider's are dropped without being checked. */
    public static BrowserTags of(Provider provider, String ga4MeasurementId, String adsConversionId,
                                 String adsPurchaseLabel, String gtmContainerId, String pixelId) {
        return switch (provider) {
            case GOOGLE -> new BrowserTags(ga4MeasurementId, adsConversionId, adsPurchaseLabel, gtmContainerId, null);
            case META -> new BrowserTags(null, null, null, null, pixelId);
        };
    }

    public boolean isEmpty() {
        return equals(NONE);
    }

    private static String check(String field, String value, Pattern pattern, String reason) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (!pattern.matcher(stripped).matches()) {
            throw new IllegalArgumentException(field + ": " + reason);
        }
        return stripped;
    }
}
```

`backend/src/main/java/com/sixpack/marketing/domain/Integration.java`:

```java
package com.sixpack.marketing.domain;

import java.time.Instant;

/** One provider's saved settings for the store. Phase 1 holds only the browser tag IDs. */
public record Integration(Provider provider, BrowserTags browser, Instant updatedAt) {

    public enum Status { NOT_CONNECTED, ACTIVE }

    public static Integration notConnected(Provider provider) {
        return new Integration(provider, BrowserTags.NONE, null);
    }

    /** A stored row always has at least one ID (saving none removes it), so having IDs means ACTIVE. */
    public Status status() {
        return browser.isEmpty() ? Status.NOT_CONNECTED : Status.ACTIVE;
    }
}
```

`backend/src/main/java/com/sixpack/marketing/domain/IntegrationRepository.java`:

```java
package com.sixpack.marketing.domain;

import java.util.List;

/** The current store's integrations. Implementations scope every call to the tenant in context and fail without one. */
public interface IntegrationRepository {

    List<Integration> findAll();

    /** Creates or replaces the store's row for {@code integration.provider()}. */
    Integration save(Integration integration);

    /** @return whether a row was removed */
    boolean delete(Provider provider);
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && mvn -q test -Dtest='BrowserTagsTest,MarketingDomainTest,ModularityTests'`
Expected: PASS (all; `ModularityTests` now sees the `marketing` module with no outside dependencies).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing backend/src/test/java/com/sixpack/marketing
git commit -m "feat(marketing): validated Google and Meta tag IDs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3 (phase plan 1.2, storage half): `marketing_integrations` behind `TenantMongo`

**Files:**
- Create: `backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingIntegrationDocument.java`
- Create: `backend/src/main/java/com/sixpack/marketing/infra/mongo/MongoIntegrationRepository.java`
- Create: `backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingBackfill.java`
- Modify: `backend/src/test/java/com/sixpack/it/IntegrationTest.java` (clean the new collection)
- Create: `backend/src/test/java/com/sixpack/it/MarketingIntegrationTest.java` (helpers for Tasks 3–5)
- Test: `backend/src/test/java/com/sixpack/it/MarketingCrossTenantIntegrationTest.java`

**Interfaces:**
- Consumes: `IntegrationRepository`, `Integration`, `BrowserTags`, `Provider` (Task 2).
- Produces: `IntegrationRepository` bean; collection `marketing_integrations` `{_id, tenantId, provider:"GOOGLE"|"META", browser:{ga4MeasurementId, adsConversionId, adsPurchaseLabel, gtmContainerId, pixelId}, updatedAt}`, unique index `tenant_provider` on `{tenantId:1, provider:1}`; `BackfillTarget` `marketing_integrations`.
- Test helpers (`MarketingIntegrationTest`): `marketing(Tenant, boolean): Tenant`, `list(Tenant, token)`, `save(Tenant, token, provider, Map browser)`, `disconnect(Tenant, token, provider)`, `browser(String... fieldValuePairs)`, `rows(Tenant): List<Document>`, `fieldNames(JsonNode): List<String>`, constant `BASE`.

- [ ] **Step 1: Clean the new collection between tests**

In `IntegrationTest.cleanDatabase`, add `"marketing_integrations"` to the array (after `"daily_sales_state"`):

```java
                "daily_sales", "daily_sales_state", "marketing_integrations"}) {
```

- [ ] **Step 2: Add the shared test helpers**

`backend/src/test/java/com/sixpack/it/MarketingIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Helpers for the Admin → Marketing tests: the feature switch, the three admin calls, raw rows. */
abstract class MarketingIntegrationTest extends TenantIntegrationTest {

    protected static final String BASE = "/api/v1/admin/marketing";

    /** The store with the marketing switch set, as a platform administrator would set it. */
    protected Tenant marketing(Tenant t, boolean on) {
        Map<String, Boolean> features = new HashMap<>(t.features());
        features.put("marketing", on);
        Tenant updated = tenantRepository.save(new Tenant(t.id(), t.slug(), t.name(), t.status(), t.domains(),
                t.theme(), t.locale(), t.currency(), t.createdAt(), t.legal(), t.payment(), t.mail(),
                t.storefront(), features, t.deletionDueAt(), t.limits(), t.trialEndsAt(), t.settings()));
        tenantRegistry.refresh();
        return updated;
    }

    protected ResultActions list(Tenant t, String token) throws Exception {
        return mvc.perform(get(BASE).with(host(hostOf(t))).header("Authorization", bearer(token)));
    }

    protected ResultActions save(Tenant t, String token, String provider, Map<String, String> browser)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("browser", browser);
        return mvc.perform(put(BASE + "/" + provider).with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions disconnect(Tenant t, String token, String provider) throws Exception {
        return mvc.perform(delete(BASE + "/" + provider).with(host(hostOf(t)))
                .header("Authorization", bearer(token)));
    }

    /** {@code browser("ga4MeasurementId", "G-…", "pixelId", "…")}; values may be blank. */
    protected static Map<String, String> browser(String... fieldsAndValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            m.put(fieldsAndValues[i], fieldsAndValues[i + 1]);
        }
        return m;
    }

    /** The store's rows in marketing_integrations, read straight from the collection. */
    protected List<Document> rows(Tenant t) {
        return mongo.getCollection("marketing_integrations").find(new Document("tenantId", t.id()))
                .into(new ArrayList<>());
    }

    protected static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
```

- [ ] **Step 3: Write the failing repository tests**

`backend/src/test/java/com/sixpack/it/MarketingCrossTenantIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Store A's marketing IDs are never read, changed or removed through store B. */
class MarketingCrossTenantIntegrationTest extends MarketingIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-30T10:00:00Z");
    private static final BrowserTags ACME = new BrowserTags("G-ACME1234", "AW-111111111", "acme_buy", null, null);
    private static final BrowserTags GLOBEX = new BrowserTags("G-GLOBEX12", null, null, null, null);

    @Autowired
    IntegrationRepository integrations;

    Tenant acme;
    Tenant globex;

    @BeforeEach
    void twoStoresWithMarketing() {
        acme = marketing(createTenant("acme", "Acme", "shop.acme.test"), true);
        globex = marketing(createTenant("globex", "Globex", "shop.globex.test"), true);
    }

    private List<Integration> savedIn(Tenant t) {
        return TenantContext.callAs(ref(t), integrations::findAll);
    }

    private void saveIn(Tenant t, BrowserTags tags) {
        TenantContext.runAs(ref(t), () -> integrations.save(new Integration(Provider.GOOGLE, tags, AT)));
    }

    @Test
    void eachStoreReadsAndRemovesOnlyItsOwnRow() {
        saveIn(acme, ACME);
        saveIn(globex, GLOBEX);

        assertThat(savedIn(acme)).extracting(Integration::browser).containsExactly(ACME);
        assertThat(savedIn(globex)).extracting(Integration::browser).containsExactly(GLOBEX);

        assertThat(TenantContext.callAs(ref(globex), () -> integrations.delete(Provider.GOOGLE))).isTrue();
        assertThat(savedIn(globex)).isEmpty();
        assertThat(savedIn(acme)).extracting(Integration::browser).containsExactly(ACME);
    }

    @Test
    void savingAgainReplacesTheStoresOneRowPerProvider() {
        saveIn(acme, ACME);
        BrowserTags changed = new BrowserTags("G-NEW12345", null, null, null, null);
        saveIn(acme, changed);

        assertThat(savedIn(acme)).containsExactly(new Integration(Provider.GOOGLE, changed, AT));
        List<Document> rows = rows(acme);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getString("provider")).isEqualTo("GOOGLE");
        assertThat(rows.get(0).get("browser", Document.class).getString("ga4MeasurementId")).isEqualTo("G-NEW12345");
    }

    @Test
    void theUniqueIndexIsPerStoreAndProvider() {
        saveIn(acme, ACME);

        List<Document> unique = mongo.getCollection("marketing_integrations").listIndexes().into(new ArrayList<>())
                .stream().filter(i -> Boolean.TRUE.equals(i.getBoolean("unique")))
                .map(i -> i.get("key", Document.class)).toList();
        assertThat(unique).contains(new Document("tenantId", 1).append("provider", 1));
    }

    @Test
    void withoutATenantTheRepositoryFailsClosed() {
        assertThatThrownBy(() -> TenantContext.callAs(null, integrations::findAll))
                .isInstanceOf(IllegalStateException.class).hasMessage("No tenant in context");
        assertThatThrownBy(() -> TenantContext.runAs(null, () -> integrations.delete(Provider.GOOGLE)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(null,
                () -> integrations.save(new Integration(Provider.GOOGLE, ACME, AT))))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 4: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest=MarketingCrossTenantIntegrationTest`
Expected: FAIL — the context does not start: `No qualifying bean of type 'com.sixpack.marketing.domain.IntegrationRepository'`.

- [ ] **Step 5: Write the Mongo adapter**

`backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingIntegrationDocument.java`:

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One provider's settings for one store (spec §5.1). Phase 1 stores only the public browser tag IDs. */
@Document("marketing_integrations")
@CompoundIndex(name = "tenant_provider", def = "{'tenantId': 1, 'provider': 1}", unique = true)
class MarketingIntegrationDocument extends TenantDocument {

    static class Browser {
        String ga4MeasurementId;
        String adsConversionId;
        String adsPurchaseLabel;
        String gtmContainerId;
        String pixelId;
    }

    @Id
    String id;
    /** {@code Provider} enum name. */
    String provider;
    Browser browser;
    Instant updatedAt;
}
```

`backend/src/main/java/com/sixpack/marketing/infra/mongo/MongoIntegrationRepository.java`:

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.tenant.TenantMongo;
import java.util.List;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoIntegrationRepository implements IntegrationRepository {

    private final TenantMongo mongo;

    MongoIntegrationRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<Integration> findAll() {
        return mongo.find(new Query(), MarketingIntegrationDocument.class).stream()
                .map(MongoIntegrationRepository::toDomain).toList();
    }

    /** One atomic upsert per {tenantId, provider}; TenantMongo copies the tenant onto a new row. */
    @Override
    public Integration save(Integration integration) {
        BrowserTags t = integration.browser();
        mongo.upsert(byProvider(integration.provider()), new Update()
                .set("browser.ga4MeasurementId", t.ga4MeasurementId())
                .set("browser.adsConversionId", t.adsConversionId())
                .set("browser.adsPurchaseLabel", t.adsPurchaseLabel())
                .set("browser.gtmContainerId", t.gtmContainerId())
                .set("browser.pixelId", t.pixelId())
                .set("updatedAt", integration.updatedAt()), MarketingIntegrationDocument.class);
        return integration;
    }

    @Override
    public boolean delete(Provider provider) {
        return mongo.remove(byProvider(provider), MarketingIntegrationDocument.class).getDeletedCount() > 0;
    }

    private static Query byProvider(Provider provider) {
        return Query.query(Criteria.where("provider").is(provider.name()));
    }

    /** Re-validates on read: a value edited by hand in the database never reaches a storefront script. */
    private static Integration toDomain(MarketingIntegrationDocument d) {
        MarketingIntegrationDocument.Browser b = d.browser == null ? new MarketingIntegrationDocument.Browser()
                : d.browser;
        return new Integration(Provider.valueOf(d.provider), new BrowserTags(b.ga4MeasurementId, b.adsConversionId,
                b.adsPurchaseLabel, b.gtmContainerId, b.pixelId), d.updatedAt);
    }
}
```

`backend/src/main/java/com/sixpack/marketing/infra/mongo/MarketingBackfill.java`:

```java
package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the collection so offboarding exports and purges it with the rest of the store. */
@Configuration
class MarketingBackfill {

    @Bean
    BackfillTarget marketingIntegrationsBackfill() {
        return () -> "marketing_integrations";
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='MarketingCrossTenantIntegrationTest,TenantOffboardingIntegrationTest,TenantIsolationArchitectureTest,ModularityTests'`
Expected: PASS (all). `TenantOffboardingIntegrationTest.everyTenantOwnedCollectionIsRegistered` would fail without `MarketingBackfill`; `TenantIsolationArchitectureTest` confirms the document extends `TenantDocument` and no `MongoTemplate` is used.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/sixpack/marketing/infra backend/src/test/java/com/sixpack/it/IntegrationTest.java \
        backend/src/test/java/com/sixpack/it/MarketingIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/MarketingCrossTenantIntegrationTest.java
git commit -m "feat(marketing): tenant-scoped marketing_integrations storage

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4 (phase plan 1.2, API half): Admin API with audit

**Files:**
- Modify: `backend/src/main/java/com/sixpack/audit/AuditAction.java`
- Create: `backend/src/main/java/com/sixpack/marketing/application/MarketingAudit.java`
- Create: `backend/src/main/java/com/sixpack/marketing/application/IntegrationService.java`
- Create: `backend/src/main/java/com/sixpack/marketing/web/MarketingAdminController.java`
- Test: `backend/src/test/java/com/sixpack/it/MarketingAdminIntegrationTest.java`
- Modify (test): `backend/src/test/java/com/sixpack/it/MarketingCrossTenantIntegrationTest.java` (HTTP cases)

**Interfaces:**
- Consumes: `IntegrationRepository` (Task 3), `AuditTrail`, `AuditEvent`, `Changes` (audit API), `TenantFeatures`, `Feature.MARKETING`, `ApiException`, `ApiResponse`.
- Produces: `AuditAction.INTEGRATION_UPDATED`, `AuditAction.INTEGRATION_DISCONNECTED`; `IntegrationService#all(): List<Integration>` (one per `Provider`, in order), `#save(Provider, BrowserTags): Integration`, `#disconnect(Provider): Integration`; the three routes of the contract table.

- [ ] **Step 1: Write the failing admin tests**

`backend/src/test/java/com/sixpack/it/MarketingAdminIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import java.time.LocalDate;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Admin → Marketing API: feature switch, the two cards, validation, disconnect, roles and audit. */
class MarketingAdminIntegrationTest extends MarketingIntegrationTest {

    Tenant acme;
    String admin;

    @BeforeEach
    void storeWithMarketing() throws Exception {
        acme = marketing(createTenant("acme", "Acme", "shop.acme.test"), true);
        admin = adminTokenFor(acme);
    }

    @Test
    void withTheFeatureOffEveryEndpointIsNotFound() throws Exception {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        String token = adminToken();

        list(def, token).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        save(def, token, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isNotFound());
        save(def, token, "google", browser("ga4MeasurementId", "<script>")).andExpect(status().isNotFound());
        disconnect(def, token, "google").andExpect(status().isNotFound());
        assertThat(rows(def)).isEmpty();
    }

    @Test
    void bothCardsStartNotConnectedWithGoogleFirst() throws Exception {
        JsonNode body = body(list(acme, admin).andExpect(status().isOk()).andReturn());

        assertThat(body.get("success").asBoolean()).isTrue();
        JsonNode cards = body.get("integrations");
        assertThat(cards).hasSize(2);

        JsonNode google = cards.get(0);
        assertThat(fieldNames(google)).containsExactly("provider", "status", "browser", "updatedAt");
        assertThat(google.get("provider").asText()).isEqualTo("google");
        assertThat(google.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(fieldNames(google.get("browser")))
                .containsExactly("ga4MeasurementId", "adsConversionId", "adsPurchaseLabel", "gtmContainerId");
        google.get("browser").forEach(v -> assertThat(v.isNull()).isTrue());
        assertThat(google.get("updatedAt").isNull()).isTrue();

        JsonNode meta = cards.get(1);
        assertThat(meta.get("provider").asText()).isEqualTo("meta");
        assertThat(meta.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(fieldNames(meta.get("browser"))).containsExactly("pixelId");
        assertThat(meta.get("browser").get("pixelId").isNull()).isTrue();
        assertThat(meta.get("updatedAt").isNull()).isTrue();
    }

    @Test
    void savingGoogleIdsActivatesOnlyTheGoogleCard() throws Exception {
        JsonNode card = body(save(acme, admin, "google", browser(
                        "ga4MeasurementId", "  G-AB12CD3 ", "adsConversionId", "AW-123456789",
                        "adsPurchaseLabel", "purchase_1", "gtmContainerId", "", "pixelId", "1234567890"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn())
                .get("integration");

        assertThat(card.get("provider").asText()).isEqualTo("google");
        assertThat(card.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(fieldNames(card.get("browser")))
                .containsExactly("ga4MeasurementId", "adsConversionId", "adsPurchaseLabel", "gtmContainerId");
        assertThat(card.get("browser").get("ga4MeasurementId").asText()).isEqualTo("G-AB12CD3");
        assertThat(card.get("browser").get("adsPurchaseLabel").asText()).isEqualTo("purchase_1");
        assertThat(card.get("browser").get("gtmContainerId").isNull()).isTrue();
        String updatedAt = card.get("updatedAt").asText();
        assertThat(updatedAt).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z");

        list(acme, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.integrations[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.integrations[0].browser.adsConversionId").value("AW-123456789"))
                .andExpect(jsonPath("$.integrations[0].updatedAt").value(updatedAt))
                .andExpect(jsonPath("$.integrations[1].status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.integrations[1].browser.pixelId").value(nullValue()));
    }

    @Test
    void metaKeepsOnlyThePixelAndIgnoresGoogleFields() throws Exception {
        save(acme, admin, "meta", browser("pixelId", "1234567890", "ga4MeasurementId", "<script>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.provider").value("meta"))
                .andExpect(jsonPath("$.integration.status").value("ACTIVE"))
                .andExpect(jsonPath("$.integration.browser.pixelId").value("1234567890"));

        list(acme, admin).andExpect(jsonPath("$.integrations[0].status").value("NOT_CONNECTED"));
    }

    @Test
    void anInvalidIdIsRejectedNamingTheFieldAndNothingIsStored() throws Exception {
        save(acme, admin, "google", browser("ga4MeasurementId", "G-x\"><script>alert(1)</script>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("ga4MeasurementId: must look like G-XXXXXXX"));
        save(acme, admin, "meta", browser("pixelId", "123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("pixelId: must be 10-20 digits"));

        assertThat(rows(acme)).isEmpty();
        assertThat(auditEntries("INTEGRATION_UPDATED")).isEmpty();
    }

    @Test
    void aPurchaseLabelNeedsAConversionId() throws Exception {
        save(acme, admin, "google", browser("ga4MeasurementId", "G-AB12CD3", "adsPurchaseLabel", "purchase"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("adsPurchaseLabel: needs adsConversionId"));
        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void aBodyWithoutBrowserIsRejected() throws Exception {
        mvc.perform(put(BASE + "/google").with(host(hostOf(acme))).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("browser: is required"));
    }

    @Test
    void anUnknownProviderIsNotFound() throws Exception {
        save(acme, admin, "tiktok", browser("pixelId", "1234567890")).andExpect(status().isNotFound());
        save(acme, admin, "GOOGLE", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isNotFound());
        disconnect(acme, admin, "tiktok").andExpect(status().isNotFound());
        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void clearingEveryIdDisconnects() throws Exception {
        save(acme, admin, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isOk());

        save(acme, admin, "google", browser("ga4MeasurementId", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.integration.browser.ga4MeasurementId").value(nullValue()))
                .andExpect(jsonPath("$.integration.updatedAt").value(nullValue()));
        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void deleteDisconnectsAndIsSafeToRepeat() throws Exception {
        save(acme, admin, "meta", browser("pixelId", "1234567890")).andExpect(status().isOk());

        disconnect(acme, admin, "meta").andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.integration.provider").value("meta"))
                .andExpect(jsonPath("$.integration.status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.integration.browser.pixelId").value(nullValue()))
                .andExpect(jsonPath("$.integration.updatedAt").value(nullValue()));
        disconnect(acme, admin, "meta").andExpect(status().isOk());

        list(acme, admin).andExpect(jsonPath("$.integrations[1].status").value("NOT_CONNECTED"));
        assertThat(rows(acme)).isEmpty();
        assertThat(auditEntries("INTEGRATION_DISCONNECTED")).hasSize(1);
    }

    @Test
    void onlyAStoreAdminWhoAcceptedTheAgreementGetsIn() throws Exception {
        mvc.perform(get(BASE).with(host(hostOf(acme)))).andExpect(status().isUnauthorized());

        String shopper = registerOn(acme, "shopper@acme.test");
        list(acme, shopper).andExpect(status().isForbidden());
        save(acme, shopper, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isForbidden());
        disconnect(acme, shopper, "google").andExpect(status().isForbidden());

        String email = "fresh-admin@acme.test";
        TenantContext.runAs(ref(acme), () -> users.save(new User(null, "Fresh Admin", email,
                encoder.encode("admin-password-123"), "", Role.ADMIN, Gender.MALE, LocalDate.of(1990, 1, 1), null)));
        String unaccepted = loginOn(acme, email, "admin-password-123");
        list(acme, unaccepted).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Please accept the admin acceptable-use agreement to continue"));

        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void savesAndDisconnectsAreAuditedWithActorEntityAndDiff() throws Exception {
        String adminId = TenantContext.callAs(ref(acme),
                () -> users.findByEmail("admin@acme.test").orElseThrow().id());

        save(acme, admin, "google", browser("ga4MeasurementId", "G-AB12CD3", "adsConversionId", "AW-123456789"))
                .andExpect(status().isOk());
        save(acme, admin, "google", browser("ga4MeasurementId", "G-NEW12345", "adsConversionId", "AW-123456789"))
                .andExpect(status().isOk());
        disconnect(acme, admin, "google").andExpect(status().isOk());

        List<Document> updates = auditEntries("INTEGRATION_UPDATED");
        assertThat(updates).hasSize(2);

        Document latest = updates.get(0);
        assertThat(latest.getString("tenantId")).isEqualTo(acme.id());
        assertThat(latest.getString("actorId")).isEqualTo(adminId);
        assertThat(latest.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(latest.getString("entityType")).isEqualTo("marketing_integration");
        assertThat(latest.getString("entityId")).isEqualTo("google");
        assertThat(latest.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("ga4MeasurementId");
        assertThat(changeOf(latest, "ga4MeasurementId").get("before")).isEqualTo("G-AB12CD3");
        assertThat(changeOf(latest, "ga4MeasurementId").get("after")).isEqualTo("G-NEW12345");

        Document first = updates.get(1);
        assertThat(changeOf(first, "adsConversionId").get("before")).isNull();
        assertThat(changeOf(first, "adsConversionId").get("after")).isEqualTo("AW-123456789");

        Document gone = auditEntries("INTEGRATION_DISCONNECTED").get(0);
        assertThat(gone.getString("actorId")).isEqualTo(adminId);
        assertThat(gone.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(gone.getString("entityType")).isEqualTo("marketing_integration");
        assertThat(gone.getString("entityId")).isEqualTo("google");
        assertThat(changeOf(gone, "ga4MeasurementId").get("before")).isEqualTo("G-NEW12345");
        assertThat(changeOf(gone, "ga4MeasurementId").get("after")).isNull();
    }
}
```

- [ ] **Step 2: Add the HTTP cross-store cases**

Append to `MarketingCrossTenantIntegrationTest` and add the static imports
`import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;` and
`import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;`:

```java
    @Test
    void oneStoresAdminCannotSeeAnotherStoresIds() throws Exception {
        String acmeAdmin = adminTokenFor(acme);
        String globexAdmin = adminTokenFor(globex);
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234")).andExpect(status().isOk());

        String body = list(globex, globexAdmin).andExpect(status().isOk())
                .andExpect(jsonPath("$.integrations[0].status").value("NOT_CONNECTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("G-ACME1234");
    }

    @Test
    void oneStoresAdminCannotChangeOrDisconnectAnotherStoresIds() throws Exception {
        String acmeAdmin = adminTokenFor(acme);
        String globexAdmin = adminTokenFor(globex);
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234")).andExpect(status().isOk());

        save(globex, globexAdmin, "google", browser("ga4MeasurementId", "G-GLOBEX12")).andExpect(status().isOk());
        disconnect(globex, globexAdmin, "google").andExpect(status().isOk());

        list(acme, acmeAdmin)
                .andExpect(jsonPath("$.integrations[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.integrations[0].browser.ga4MeasurementId").value("G-ACME1234"));
        assertThat(rows(acme)).hasSize(1);
        assertThat(rows(globex)).isEmpty();
    }

    @Test
    void aTokenFromOneStoreIsRejectedOnAnotherStoresHost() throws Exception {
        String acmeAdmin = adminTokenFor(acme);

        list(globex, acmeAdmin).andExpect(status().isUnauthorized());
        save(globex, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234"))
                .andExpect(status().isUnauthorized());
        disconnect(globex, acmeAdmin, "google").andExpect(status().isUnauthorized());
        assertThat(rows(globex)).isEmpty();
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='MarketingAdminIntegrationTest,MarketingCrossTenantIntegrationTest'`
Expected: FAIL — there is no route yet, so authorised calls get `404` (GET) or `404`/`405` (PUT/DELETE), e.g. `bothCardsStartNotConnectedWithGoogleFirst: Status expected:<200> but was:<404>`. Some tests may already pass because security runs before routing — `aTokenFromOneStoreIsRejectedOnAnotherStoresHost` (401 from the `tid` check), `onlyAStoreAdminWhoAcceptedTheAgreementGetsIn` — plus the Task 3 repository cases; `withTheFeatureOffEveryEndpointIsNotFound` passes for GET and may fail with 405 for PUT/DELETE. All of them must pass after Step 6.

- [ ] **Step 4: Add the audit actions**

`AuditAction.java` — after `STORE_SETTINGS_UPDATED,`:

```java
    /** A store's marketing tag IDs saved or cleared, and a provider disconnected (Admin → Marketing). */
    INTEGRATION_UPDATED, INTEGRATION_DISCONNECTED,
```

- [ ] **Step 5: Write the service and its audit view**

`backend/src/main/java/com/sixpack/marketing/application/MarketingAudit.java`:

```java
package com.sixpack.marketing.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.marketing.domain.BrowserTags;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the audit log may say about an integration. Public tag IDs are logged with values; secrets (phase 3) never. */
final class MarketingAudit {

    static final String ENTITY = "marketing_integration";

    static final FieldPolicy POLICY = Changes.policy(List.of("ga4MeasurementId", "adsConversionId",
            "adsPurchaseLabel", "gtmContainerId", "pixelId"), List.of());

    private MarketingAudit() {
    }

    static Map<String, Object> view(BrowserTags t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ga4MeasurementId", t.ga4MeasurementId());
        m.put("adsConversionId", t.adsConversionId());
        m.put("adsPurchaseLabel", t.adsPurchaseLabel());
        m.put("gtmContainerId", t.gtmContainerId());
        m.put("pixelId", t.pixelId());
        return m;
    }
}
```

`backend/src/main/java/com/sixpack/marketing/application/IntegrationService.java`:

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The store's marketing integrations. Callers check {@code Feature.MARKETING} first. */
@Service
public class IntegrationService {

    private final IntegrationRepository integrations;
    private final AuditTrail audit;

    IntegrationService(IntegrationRepository integrations, AuditTrail audit) {
        this.integrations = integrations;
        this.audit = audit;
    }

    /** One entry per provider in {@link Provider} order; a provider with nothing saved is NOT_CONNECTED. */
    public List<Integration> all() {
        Map<Provider, Integration> saved = new EnumMap<>(Provider.class);
        integrations.findAll().forEach(i -> saved.put(i.provider(), i));
        return Arrays.stream(Provider.values())
                .map(p -> saved.getOrDefault(p, Integration.notConnected(p))).toList();
    }

    /** Saves the provider's IDs; with none left the row is removed and the card is NOT_CONNECTED again. */
    public Integration save(Provider provider, BrowserTags tags) {
        Integration before = find(provider);
        Integration after;
        if (tags.isEmpty()) {
            integrations.delete(provider);
            after = Integration.notConnected(provider);
        } else {
            // Millisecond precision: what Mongo keeps, so the response matches the next GET.
            after = integrations.save(new Integration(provider, tags, Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        }
        audit.record(AuditEvent.of(AuditAction.INTEGRATION_UPDATED).on(MarketingAudit.ENTITY, provider.key())
                .changing(Changes.diff(MarketingAudit.view(before.browser()), MarketingAudit.view(after.browser()),
                        MarketingAudit.POLICY)));
        return after;
    }

    /** Removes the provider's row; audited only when there was one. */
    public Integration disconnect(Provider provider) {
        Integration before = find(provider);
        if (integrations.delete(provider)) {
            audit.record(AuditEvent.of(AuditAction.INTEGRATION_DISCONNECTED).on(MarketingAudit.ENTITY, provider.key())
                    .changing(Changes.diff(MarketingAudit.view(before.browser()), Map.of(), MarketingAudit.POLICY)));
        }
        return Integration.notConnected(provider);
    }

    private Integration find(Provider provider) {
        return all().stream().filter(i -> i.provider() == provider).findFirst().orElseThrow();
    }
}
```

- [ ] **Step 6: Write the controller**

`backend/src/main/java/com/sixpack/marketing/web/MarketingAdminController.java`:

```java
package com.sixpack.marketing.web;

import com.sixpack.marketing.application.IntegrationService;
import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.web.ApiResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Marketing. ADMIN-only via the existing {@code /api/v1/admin/**} rule; the admin agreement interceptor
 * covers it too. A store without the feature, and an unknown provider, get 404 as if the page did not exist.
 */
@RestController
@RequestMapping("/api/v1/admin/marketing")
class MarketingAdminController {

    record SaveRequest(BrowserFields browser) {
    }

    record BrowserFields(String ga4MeasurementId, String adsConversionId, String adsPurchaseLabel,
                         String gtmContainerId, String pixelId) {
    }

    /** One card. Only the provider's own fields appear in {@code browser}; unset ones are null. */
    record IntegrationView(String provider, String status, Map<String, String> browser, Instant updatedAt) {
        static IntegrationView from(Integration i) {
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
            return new IntegrationView(i.provider().key(), i.status().name(), browser, i.updatedAt());
        }
    }

    private final IntegrationService service;
    private final TenantFeatures features;

    MarketingAdminController(IntegrationService service, TenantFeatures features) {
        this.service = service;
        this.features = features;
    }

    @GetMapping
    Map<String, Object> all() {
        requireEnabled();
        return ApiResponse.of("integrations", service.all().stream().map(IntegrationView::from).toList());
    }

    @PutMapping("/{provider}")
    Map<String, Object> save(@PathVariable String provider, @RequestBody SaveRequest request) {
        Provider p = provider(provider);
        BrowserFields b = request.browser();
        if (b == null) {
            throw ApiException.badRequest("browser: is required");
        }
        BrowserTags tags;
        try {
            tags = BrowserTags.of(p, b.ga4MeasurementId(), b.adsConversionId(), b.adsPurchaseLabel(),
                    b.gtmContainerId(), b.pixelId());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return ApiResponse.of("integration", IntegrationView.from(service.save(p, tags)));
    }

    @DeleteMapping("/{provider}")
    Map<String, Object> disconnect(@PathVariable String provider) {
        return ApiResponse.of("integration", IntegrationView.from(service.disconnect(provider(provider))));
    }

    private Provider provider(String key) {
        requireEnabled();
        return Provider.fromKey(key).orElseThrow(() -> ApiException.notFound("Not found"));
    }

    private void requireEnabled() {
        if (!features.enabled(Feature.MARKETING)) {
            throw ApiException.notFound("Not found");
        }
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='MarketingAdminIntegrationTest,MarketingCrossTenantIntegrationTest,ModularityTests,AuditApiIntegrationTest,ChangesTest'`
Expected: PASS (all).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/sixpack/audit/AuditAction.java backend/src/main/java/com/sixpack/marketing \
        backend/src/test/java/com/sixpack/it/MarketingAdminIntegrationTest.java \
        backend/src/test/java/com/sixpack/it/MarketingCrossTenantIntegrationTest.java
git commit -m "feat(marketing): store owners save Google and Meta tag IDs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5 (phase plan 1.3): Public tags in `/tenant/config`

**Files:**
- Create: `backend/src/main/java/com/sixpack/shared/tenant/MarketingTags.java`
- Modify: `backend/src/main/java/com/sixpack/shared/config/CacheConfig.java`
- Modify: `backend/src/main/java/com/sixpack/marketing/application/IntegrationService.java` (implements the port, cache + evict)
- Modify: `backend/src/main/java/com/sixpack/tenant/web/TenantConfigController.java`
- Test: `backend/src/test/java/com/sixpack/it/MarketingTagsConfigIntegrationTest.java`
- Modify (test): `backend/src/test/java/com/sixpack/ModularityTests.java` (guard)

**Interfaces:**
- Produces: `interface MarketingTags { Tags current(); record Tags(String ga4, String adsId, String adsPurchaseLabel, String gtm, String metaPixel) { static NONE; boolean hasAny(); } }` in `com.sixpack.shared.tenant`; `CacheConfig.MARKETING_TAGS = "marketing.tags"` (60 s, per tenant via `TenantCacheManager`); `tenant.marketing.tags` in `/api/v1/tenant/config`.
- Note: the presence check is `hasAny()`, not `isEmpty()`, because Jackson would serialise an `isEmpty()` getter on the record as an extra `"empty"` key.

- [ ] **Step 1: Write the failing config tests**

`backend/src/test/java/com/sixpack/it/MarketingTagsConfigIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** /tenant/config ships a store's own public tag IDs, only with marketing on and at least one ID saved. */
class MarketingTagsConfigIntegrationTest extends MarketingIntegrationTest {

    Tenant acme;
    Tenant globex;
    String acmeAdmin;
    String globexAdmin;

    @BeforeEach
    void twoStoresWithMarketing() throws Exception {
        acme = marketing(createTenant("acme", "Acme", "shop.acme.test"), true);
        globex = marketing(createTenant("globex", "Globex", "shop.globex.test"), true);
        acmeAdmin = adminTokenFor(acme);
        globexAdmin = adminTokenFor(globex);
    }

    private JsonNode config(Tenant t) throws Exception {
        return body(mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(t))))
                .andExpect(status().isOk()).andReturn()).get("tenant");
    }

    @Test
    void savedIdsAreShippedAsTheFiveTagsAndNothingElse() throws Exception {
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234", "adsConversionId", "AW-111111111",
                "adsPurchaseLabel", "acme_buy", "gtmContainerId", "GTM-ACME12")).andExpect(status().isOk());
        save(acme, acmeAdmin, "meta", browser("pixelId", "1111111111")).andExpect(status().isOk());

        JsonNode marketing = config(acme).get("marketing");
        assertThat(fieldNames(marketing)).containsExactly("tags");
        JsonNode tags = marketing.get("tags");
        assertThat(fieldNames(tags)).containsExactly("ga4", "adsId", "adsPurchaseLabel", "gtm", "metaPixel");
        assertThat(tags.get("ga4").asText()).isEqualTo("G-ACME1234");
        assertThat(tags.get("adsId").asText()).isEqualTo("AW-111111111");
        assertThat(tags.get("adsPurchaseLabel").asText()).isEqualTo("acme_buy");
        assertThat(tags.get("gtm").asText()).isEqualTo("GTM-ACME12");
        assertThat(tags.get("metaPixel").asText()).isEqualTo("1111111111");
    }

    @Test
    void withNothingSavedThereIsNoMarketingKey() throws Exception {
        assertThat(config(acme).has("marketing")).isFalse();
    }

    @Test
    void turningTheFeatureOffHidesSavedTagsAtOnce() throws Exception {
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234")).andExpect(status().isOk());
        assertThat(config(acme).get("marketing").get("tags").get("ga4").asText()).isEqualTo("G-ACME1234");

        acme = marketing(acme, false);

        assertThat(config(acme).has("marketing")).isFalse();
    }

    @Test
    void aSaveOrDisconnectShowsOnTheNextConfig() throws Exception {
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234")).andExpect(status().isOk());
        assertThat(config(acme).get("marketing").get("tags").get("ga4").asText()).isEqualTo("G-ACME1234");

        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME5678")).andExpect(status().isOk());
        assertThat(config(acme).get("marketing").get("tags").get("ga4").asText()).isEqualTo("G-ACME5678");

        disconnect(acme, acmeAdmin, "google").andExpect(status().isOk());
        assertThat(config(acme).has("marketing")).isFalse();
    }

    @Test
    void oneStoresTagsNeverAppearInAnothers() throws Exception {
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234")).andExpect(status().isOk());
        assertThat(config(acme).get("marketing").get("tags").get("ga4").asText()).isEqualTo("G-ACME1234");
        assertThat(config(globex).has("marketing")).isFalse(); // acme's tags are cached now; globex must not get them

        save(globex, globexAdmin, "meta", browser("pixelId", "2222222222")).andExpect(status().isOk());

        JsonNode acmeTags = config(acme).get("marketing").get("tags");
        JsonNode globexTags = config(globex).get("marketing").get("tags");
        assertThat(acmeTags.get("metaPixel").isNull()).isTrue();
        assertThat(globexTags.get("ga4").isNull()).isTrue();
        assertThat(globexTags.get("metaPixel").asText()).isEqualTo("2222222222");
        assertThat(config(globex).toString()).doesNotContain("G-ACME1234");
    }
}
```

Add the guard to `backend/src/test/java/com/sixpack/ModularityTests.java` (imports `com.tngtech.archunit.core.importer.ClassFileImporter`, `com.tngtech.archunit.core.importer.ImportOption`, `com.tngtech.archunit.lang.syntax.ArchRuleDefinition`):

```java
    /** Spec §4.1: switching marketing off must never break checkout, so nothing may depend on it. */
    @Test
    void nothingOutsideMarketingDependsOnIt() {
        ArchRuleDefinition.noClasses().that().resideOutsideOfPackage("com.sixpack.marketing..")
                .should().dependOnClassesThat().resideInAPackage("com.sixpack.marketing..")
                .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.sixpack"));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && mvn -q test -Dtest='MarketingTagsConfigIntegrationTest,ModularityTests'`
Expected: FAIL — `savedIdsAreShippedAsTheFiveTagsAndNothingElse`, `turningTheFeatureOffHidesSavedTagsAtOnce`, `aSaveOrDisconnectShowsOnTheNextConfig` and `oneStoresTagsNeverAppearInAnothers` throw `NullPointerException` (`config(...).get("marketing")` is null). `withNothingSavedThereIsNoMarketingKey` and `ModularityTests` pass (guards).

- [ ] **Step 3: Add the port**

`backend/src/main/java/com/sixpack/shared/tenant/MarketingTags.java`:

```java
package com.sixpack.shared.tenant;

/**
 * The public tag IDs the storefront loads for the store in {@link TenantContext}. Lives here so the {@code tenant}
 * module can ship them in {@code /tenant/config} without depending on {@code marketing}, which implements it.
 */
public interface MarketingTags {

    /** Never null; all fields null when the store saved nothing. Callers check {@link Feature#MARKETING} first. */
    Tags current();

    /** Field names are the JSON keys of {@code tenant.marketing.tags}. */
    record Tags(String ga4, String adsId, String adsPurchaseLabel, String gtm, String metaPixel) {

        public static final Tags NONE = new Tags(null, null, null, null, null);

        /** Not {@code isEmpty()}: Jackson would serialise that getter as an extra "empty" key. */
        public boolean hasAny() {
            return !equals(NONE);
        }
    }
}
```

- [ ] **Step 4: Add the cache**

`CacheConfig.java` — add the constant after `DASHBOARD`:

```java
    public static final String MARKETING_TAGS = "marketing.tags";
```

and register it before `return`:

```java
        // Read on every storefront /tenant/config; evicted by IntegrationService on save and disconnect.
        manager.registerCustomCache(MARKETING_TAGS, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1_000).recordStats().build());
```

- [ ] **Step 5: Implement the port in `IntegrationService`**

Change the class declaration and add the imports
`com.sixpack.shared.config.CacheConfig`, `com.sixpack.shared.tenant.MarketingTags`,
`org.springframework.cache.annotation.CacheEvict`, `org.springframework.cache.annotation.Cacheable`:

```java
public class IntegrationService implements MarketingTags {
```

Annotate `save` and `disconnect` (both, directly above `public Integration …`):

```java
    @CacheEvict(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
```

Add the port method (one Mongo read for both providers; the per-tenant key comes from `TenantCacheManager`):

```java
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
```

- [ ] **Step 6: Ship the tags in `/tenant/config`**

`TenantConfigController.java` — add imports `com.sixpack.shared.tenant.Feature` and `com.sixpack.shared.tenant.MarketingTags`, a field and constructor parameter:

```java
    private final MarketingTags marketing;

    TenantConfigController(TenantRegistry registry, TenantFeatures features, StoreConfig store,
                           MarketingTags marketing) {
        this.registry = registry;
        this.features = features;
        this.store = store;
        this.marketing = marketing;
    }
```

and in `config()`, after `view.put("timeZone", rules.timeZone());`:

```java
        // The switch is checked outside the cached tags, so turning it off takes effect at once.
        if (features.enabled(Feature.MARKETING)) {
            MarketingTags.Tags tags = marketing.current();
            if (tags.hasAny()) {
                view.put("marketing", Map.of("tags", tags));
            }
        }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='MarketingTagsConfigIntegrationTest,MarketingAdminIntegrationTest,TenantFeatureFlagsIntegrationTest,TenantConfigurationIntegrationTest,ModularityTests'`
Expected: PASS (all).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/sixpack/shared backend/src/main/java/com/sixpack/marketing \
        backend/src/main/java/com/sixpack/tenant/web/TenantConfigController.java \
        backend/src/test/java/com/sixpack/it/MarketingTagsConfigIntegrationTest.java \
        backend/src/test/java/com/sixpack/ModularityTests.java
git commit -m "feat(marketing): ship a store's tag IDs in /tenant/config

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Full verification

- [ ] **Step 1: Run the whole backend build**

Run: `cd backend && mvn -q verify`
Expected: exit code 0 and no `ERROR`/`FAILURE` lines (quiet mode prints nothing else on success). This includes `ModularityTests`, `TenantIsolationArchitectureTest`, `TenantOffboardingIntegrationTest` and every existing IT. Paste the tail of the output and the exit code (`echo $?`) into the task report.

If it fails: read the first failure, fix the cause in the task that introduced it (with a failing test first if it is a behaviour bug), re-run the single class, then `mvn -q verify` again. Do not skip or `@Disabled` tests.

- [ ] **Step 2: Check the diff touches only the planned files**

Run: `git diff --stat feat/marketing...HEAD`
Expected: only files from the File map above (plus `IntegrationTest.java`); nothing under `docs/foundations/`, `ecommerce-frontend/`, or the spec.

- [ ] **Step 3: Report**

No commit in this task. Report: commits made, `mvn -q verify` result, and the follow-ups for other plans — docs agent: `contracts.md` (three routes, call budget 1, `tenant.marketing.tags`), `requirements.md` (two audit actions), `architecture.md` (module row); frontend plan: `auditAPI.ts` action list. Ask before pushing or opening a PR into `feat/marketing`.
