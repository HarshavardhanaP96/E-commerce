# Multi-tenant Console and Store Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-page `/platform` console with a console of its own pages (store list, onboarding with store types, a per-store workspace with linkable tabs), add store types that pre-set a new store once, and split the owner's Store settings into tabs.

**Architecture:** Backend: a `StoreType` enum in `shared.tenant` carries each preset; `Tenant` gains a `type`; creating a store writes the preset as the store's own features and `StoreSettings`. The single-store platform endpoint also returns the owner's settings, usage (counted inside that store's tenant context) and platform defaults. Frontend: the console reuses the admin shell (`AdminLayout`, `DetailLayout`, `DataTable`) with its own sidebar; pure logic lives in small `.ts` modules with Vitest tests; components stay thin.

**Tech Stack:** Spring Boot 3 / Java 21, MongoDB, Testcontainers + MockMvc; React 18, TypeScript, RTK Query, react-router 6.23, react-icons (`lu`), Vitest (no React Testing Library in the repo: test logic modules, not components), SCSS.

**Spec:** `docs/superpowers/specs/2026-11-05-multitenant-console-design.md`

## Global Constraints

- Store types: exactly `GENERAL`, `FASHION`, `GROCERY`, `ELECTRONICS`; presets exactly as spec §2 (Fashion: variants on, GST 5, low stock 3 · Grocery: variants off, GST 5, shipping 40 / free above 500, low stock 10, stuck days 1 · Electronics: variants on, GST 18, low stock 2, stuck days 2 · General: nothing).
- A preset is applied **once**, at create. `marketing` is never set by a preset. A `PUT` with `type` changes the label only.
- `type` null in the database reads as `GENERAL`. No data migration.
- Unknown type → 400 with message `type: unknown store type`.
- No impersonation / "open as store". No new write endpoints except accepting `type` on create and update.
- Admin pages need their own scroll container (`flex:1; min-height:0; overflow-y:auto`) because `.admin-container > main.admin-main` has `overflow: hidden`.
- No new npm or Maven dependencies.
- Copy rules: plain English, sentence case, no "Tenant" in UI copy (say "store"), dates `en-IN` medium.
- Branch `feat/multitenant-console`; commit after each task; never commit on `dev`/`main`.
- Backend tests need Docker (Testcontainers): run Maven outside the sandbox.
- Frontend commands run in `ecommerce-frontend/` (`npx vitest run`, `npx tsc --noEmit -p .`, `npm run -s lint`).

## Review Focus

1. **Deep link to a store workspace** (`/platform/stores/acme/limits` opened in a fresh tab) must load: the slug is resolved from the tenants list, then the store loads. Test: `findBySlug` in `storeList.test.ts` (Task 6) and the browser pass (Task 11).
2. **Unknown slug or deleted store in the URL** must show "Store not found" with a link back, never a blank page or a crash. Test: `findBySlug` returns `undefined` for unknown slugs (Task 6); the workspace renders the not-found state (Task 8, browser pass).
3. **Saving one Store settings tab must not save half-typed values from another tab.** Switching tabs with unsaved changes asks first; discarding resets to saved. Test: `tabDirty` and `toTabPayload` (Task 10).
4. **Limits entered in MB/GB** must round-trip without drift (1.5 GB shows as 1.5 GB after save) and empty must mean platform default (`null`), never `0`. Test: `bytesFromSize` / `sizeFromBytes` (Task 9).
5. **The `default` store** gets no status moves, and a store scheduled for deletion only offers Restore. Test: `statusMoves` (Task 8).

---

## File map

Backend (`backend/src/main/java/com/sixpack/…`):
- Create `shared/tenant/StoreType.java`: types, labels, descriptions, presets, `parse`.
- Modify `tenant/domain/Tenant.java`: `type` component.
- Modify `tenant/infra/mongo/TenantDocument.java`, `MongoTenantRepository.java`: persist `type`.
- Modify `tenant/application/TenantAdminService.java`: apply preset on create, label on update.
- Modify `tenant/application/TenantAudit.java`: audit `type` and preset-able settings.
- Create `tenant/application/TenantUsage.java`: per-store usage.
- Modify `storage/FileStorage.java`, `storage/infra/local/LocalFileStorage.java`: `usedBytes()`.
- Modify `product/CatalogReads.java`, `product/application/ProductCatalogService.java`: `count()`.
- Modify `tenant/web/PlatformTenantController.java`: `type` in/out, detail payload.
- Create `tenant/web/PlatformStoreTypeController.java`: `GET /platform/store-types`.
- Modify `tenant/application/StoreSettingsService.java`, `tenant/web/StoreSettingsController.java`: `type` in the view.

Frontend (`ecommerce-frontend/src/…`):
- Create `components/admin/Tabs.tsx`, `components/admin/tabsKeyboard.ts` (+ test), `components/admin/ConfirmDialog.tsx`.
- Modify `components/admin/AdminSidebar.tsx` (extract `Sidebar`), `AdminLayout.tsx`, `DetailLayout.tsx` (`sidebar` prop).
- Modify `utils/routes.ts` (+ test), `components/footer.tsx`, `App.tsx`.
- Modify `redux/api/platformAPI.ts`, `redux/api/storeAPI.ts`.
- Rewrite `pages/platform/platform.tsx` (router); create `pages/platform/PlatformLogin.tsx`, `PlatformSidebar.tsx`, `PlatformAudit.tsx`, `StoreList.tsx`, `storeList.ts` (+ test), `OnboardStore.tsx`, `onboarding.ts` (+ test), `StoreWorkspace.tsx`, `statusMoves.ts` (+ test), `workspaceForms.ts` (+ test), `tabs/OverviewTab.tsx`, `tabs/ProfileTab.tsx`, `tabs/FeaturesTab.tsx`, `tabs/SimpleTabs.tsx` (Payments, Email, Legal), `tabs/LimitsTab.tsx`.
- Modify `pages/admin/settings.tsx`, `pages/admin/storeSettingsForm.ts` (+ test).
- Rewrite `styles/_platform.scss`; add tab/dialog styles to `styles/admin-styles/_admin-ui.scss`.

Docs: `docs/foundations/architecture.md` (ADR-030), `contracts.md`, `requirements.md`, `ecommerce-frontend/src/help/adminGuides.ts`, the spec's call-budget table.

---

### Task 1: Store types on the backend

**Files:**
- Create: `backend/src/main/java/com/sixpack/shared/tenant/StoreType.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/domain/Tenant.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/infra/mongo/TenantDocument.java`, `MongoTenantRepository.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/application/TenantAdminService.java`, `TenantAudit.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/web/PlatformTenantController.java`
- Test: `backend/src/test/java/com/sixpack/shared/tenant/StoreTypeTest.java`, `backend/src/test/java/com/sixpack/it/PlatformStoreTypesIntegrationTest.java`

**Interfaces:**
- Produces: `enum StoreType { GENERAL, FASHION, GROCERY, ELECTRONICS }` with `String label()`, `String description()`, `Map<String, Boolean> features()`, `StoreSettings settings()`, `static StoreType parse(String)`.
- Produces: `Tenant#type()` (never null; `GENERAL` when unset); canonical constructor gains a trailing `StoreType type`; the previous 18-argument constructor keeps working (type null → `GENERAL`).
- Produces: `CreateCommand(..., AdminCommand admin, StoreType type)`, `UpdateCommand(..., TenantLimits limits, StoreType type)`.
- Produces: JSON `tenant.type` (string) on every platform tenant response; request field `type` on `POST` and `PUT /api/v1/platform/tenants`.

- [ ] **Step 1: Write the failing unit test**

`backend/src/test/java/com/sixpack/shared/tenant/StoreTypeTest.java`:

```java
package com.sixpack.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.ApiException;
import org.junit.jupiter.api.Test;

class StoreTypeTest {

    @Test
    void blankMeansGeneralAndCaseDoesNotMatter() {
        assertThat(StoreType.parse(null)).isEqualTo(StoreType.GENERAL);
        assertThat(StoreType.parse(" ")).isEqualTo(StoreType.GENERAL);
        assertThat(StoreType.parse("fashion")).isEqualTo(StoreType.FASHION);
    }

    @Test
    void anUnknownTypeIsABadRequest() {
        assertThatThrownBy(() -> StoreType.parse("bakery"))
                .isInstanceOf(ApiException.class).hasMessage("type: unknown store type");
    }

    @Test
    void groceryPresetMatchesTheSpec() {
        StoreSettings s = StoreType.GROCERY.settings();
        assertThat(StoreType.GROCERY.features()).containsExactlyEntriesOf(java.util.Map.of("variants", false));
        assertThat(s.commerce().shippingFee()).isEqualTo(40.0);
        assertThat(s.commerce().freeShippingAbove()).isEqualTo(500.0);
        assertThat(s.commerce().defaultTaxRate()).isEqualTo(5.0);
        assertThat(s.commerce().stuckOrderDays()).isEqualTo(1);
        assertThat(s.catalog().lowStockThreshold()).isEqualTo(10);
    }

    @Test
    void generalSetsNothingAndNoPresetTouchesMarketing() {
        assertThat(StoreType.GENERAL.features()).isEmpty();
        assertThat(StoreType.GENERAL.settings()).isEqualTo(StoreSettings.NONE);
        for (StoreType t : StoreType.values()) {
            assertThat(t.features()).doesNotContainKey("marketing");
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run (in `backend/`): `mvn -q -o test -Dtest=StoreTypeTest`
Expected: compilation error, `cannot find symbol: class StoreType`.

- [ ] **Step 3: Add `StoreType`**

`backend/src/main/java/com/sixpack/shared/tenant/StoreType.java`:

```java
package com.sixpack.shared.tenant;

import com.sixpack.shared.ApiException;
import java.util.Locale;
import java.util.Map;

/**
 * The kinds of store the platform onboards. A preset is applied once, when the store is created: its values become
 * the store's own, and afterwards the type is only a label (ADR-030). {@code marketing} is never part of a preset.
 */
public enum StoreType {
    GENERAL("General", "Anything else. Starts on the platform defaults.", Map.of(), StoreSettings.NONE),
    FASHION("Fashion", "Clothing, shoes and accessories in sizes and colours.", Map.of("variants", true),
            new StoreSettings(new StoreSettings.Commerce(null, null, 5.0, null, null, null, null),
                    new StoreSettings.Catalog(null, 3), null, null)),
    GROCERY("Grocery", "Food and daily needs: low GST, cheap local delivery, quick turnaround.",
            Map.of("variants", false),
            new StoreSettings(new StoreSettings.Commerce(40.0, 500.0, 5.0, null, null, 1, null),
                    new StoreSettings.Catalog(null, 10), null, null)),
    ELECTRONICS("Electronics", "Gadgets and appliances with storage or colour options.", Map.of("variants", true),
            new StoreSettings(new StoreSettings.Commerce(null, null, 18.0, null, null, 2, null),
                    new StoreSettings.Catalog(null, 2), null, null));

    private final String label;
    private final String description;
    private final Map<String, Boolean> features;
    private final StoreSettings settings;

    StoreType(String label, String description, Map<String, Boolean> features, StoreSettings settings) {
        this.label = label;
        this.description = description;
        this.features = features;
        this.settings = settings;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Feature switches the preset sets, by {@link Feature} key. */
    public Map<String, Boolean> features() {
        return features;
    }

    /** Shop rules the preset sets; null fields are left to the platform default. */
    public StoreSettings settings() {
        return settings;
    }

    /** Blank → {@code GENERAL}; otherwise the type's name in any case. */
    public static StoreType parse(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("type: unknown store type");
        }
    }
}
```

- [ ] **Step 4: Run the unit test**

Run: `mvn -q -o test -Dtest=StoreTypeTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/PlatformStoreTypesIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.shared.tenant.StoreType;
import com.sixpack.tenant.domain.Tenant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123"})
class PlatformStoreTypesIntegrationTest extends TenantIntegrationTest {

    private static final String API = "/api/v1/platform/tenants";

    private String platformToken() throws Exception {
        return body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private ResultActions create(String slug, String type, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("slug", slug);
        body.put("name", slug + " store");
        body.put("domains", List.of("shop." + slug + ".test"));
        if (type != null) {
            body.put("type", type);
        }
        return mvc.perform(post(API).header("Authorization", bearer(platformToken()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private Tenant stored(String slug) {
        return tenantRepository.findBySlug(slug).orElseThrow();
    }

    @Test
    void aFashionStoreStartsWithItsPresetAsItsOwnSettings() throws Exception {
        create("acme", "fashion", Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.type").value("FASHION"))
                .andExpect(jsonPath("$.tenant.features.variants").value(true));
        Tenant acme = stored("acme");
        assertThat(acme.settings().commerce().defaultTaxRate()).isEqualTo(5.0);
        assertThat(acme.settings().catalog().lowStockThreshold()).isEqualTo(3);
        assertThat(acme.settings().commerce().shippingFee()).isNull();
    }

    @Test
    void anExplicitFeatureInTheRequestWinsOverThePreset() throws Exception {
        create("grocer", "GROCERY", Map.of("features", Map.of("variants", true))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.features.variants").value(true));
    }

    @Test
    void anUnknownTypeIsRejected() throws Exception {
        create("bakery", "bakery", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("type: unknown store type"));
        assertThat(tenantRepository.findBySlug("bakery")).isEmpty();
    }

    @Test
    void aStoreWithoutATypeIsGeneral() throws Exception {
        create("plain", null, Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.type").value("GENERAL"));
        Tenant legacy = createTenant("legacy", "Legacy", "shop.legacy.test");
        mvc.perform(get(API + "/" + legacy.id()).header("Authorization", bearer(platformToken())))
                .andExpect(jsonPath("$.tenant.type").value("GENERAL"));
    }

    @Test
    void changingTheTypeIsOnlyALabel() throws Exception {
        create("grocer", "grocery", Map.of()).andExpect(status().isCreated());
        Tenant grocer = stored("grocer");
        mvc.perform(put(API + "/" + grocer.id()).header("Authorization", bearer(platformToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("type", "fashion"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenant.type").value("FASHION"))
                .andExpect(jsonPath("$.tenant.features.variants").value(false));
        Tenant after = stored("grocer");
        assertThat(after.type()).isEqualTo(StoreType.FASHION);
        assertThat(after.settings().catalog().lowStockThreshold()).isEqualTo(10);
    }

    @Test
    void theCreationIsAuditedWithTheTypeAndThePresetValues() throws Exception {
        create("grocer", "grocery", Map.of()).andExpect(status().isCreated());
        Document entry = auditEntries("TENANT_CREATED").get(0);
        assertThat(changeOf(entry, "type").get("after")).isEqualTo("GROCERY");
        assertThat(changeOf(entry, "settings.commerce.shippingFee").get("after")).isEqualTo(40.0);
        assertThat(changeOf(entry, "settings.catalog.lowStockThreshold").get("after")).isEqualTo(10);
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run (outside the sandbox, Docker needed): `mvn -q -o test -Dtest=PlatformStoreTypesIntegrationTest`
Expected: compilation error on `after.type()` (`cannot find symbol: method type()`).

- [ ] **Step 7: Add `type` to `Tenant`**

In `Tenant.java`, add the import `com.sixpack.shared.tenant.StoreType;`, make `StoreType type` the last record component, normalise it, keep an 18-argument constructor, and carry `type` through the `with…` methods:

```java
public record Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                     Map<String, String> theme, String locale, String currency, Instant createdAt,
                     LegalSettings legal, PaymentSettings payment, MailSettings mail,
                     StorefrontSettings storefront, Map<String, Boolean> features, Instant deletionDueAt,
                     TenantLimits limits, Instant trialEndsAt, StoreSettings settings, StoreType type) {

    public Tenant {
        // ... existing normalisation lines unchanged ...
        settings = settings == null ? StoreSettings.NONE : settings;
        type = type == null ? StoreType.GENERAL : type;
    }

    /** Without a store type: reads as {@link StoreType#GENERAL}. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt,
                  LegalSettings legal, PaymentSettings payment, MailSettings mail,
                  StorefrontSettings storefront, Map<String, Boolean> features, Instant deletionDueAt,
                  TenantLimits limits, Instant trialEndsAt, StoreSettings settings) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail, storefront,
                features, deletionDueAt, limits, trialEndsAt, settings, null);
    }
```

Replace the bodies of `withOwnerSettings` and `withStatus` so they pass `type` as the last argument:

```java
    public Tenant withOwnerSettings(StoreSettings newSettings, StorefrontSettings newStorefront) {
        return new Tenant(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail,
                newStorefront, features, deletionDueAt, limits, trialEndsAt, newSettings, type);
    }

    public Tenant withStatus(TenantStatus newStatus, Instant newDeletionDueAt) {
        return new Tenant(id, slug, name, newStatus, newStatus == TenantStatus.DELETED ? List.of() : domains, theme,
                locale, currency, createdAt, legal, payment, mail, storefront, features, newDeletionDueAt, limits,
                trialEndsAt, settings, type);
    }
```

The "Without the owner's shop rules" 17-argument constructor keeps delegating with `null` settings; it now resolves to the 18-argument one, which is fine.

- [ ] **Step 8: Persist `type`**

`tenant/infra/mongo/TenantDocument.java`: add a field after `settings`:

```java
    /** {@link com.sixpack.shared.tenant.StoreType} name; null on stores created before store types (= GENERAL). */
    String type;
```

`MongoTenantRepository.save`: after `doc.settings = t.settings();` add `doc.type = t.type().name();`.
`MongoTenantRepository.toDomain`: change the last line to pass the type, and import `com.sixpack.shared.tenant.StoreType`:

```java
                d.trialEndsAt, d.settings, d.type == null ? null : StoreType.valueOf(d.type));
```

- [ ] **Step 9: Apply the preset on create, label on update**

`TenantAdminService.java`: import `com.sixpack.shared.tenant.StoreType` and `java.util.TreeMap`. Add `StoreType type` as the last component of both commands:

```java
    public record CreateCommand(String slug, String name, List<String> domains, Map<String, String> theme,
                                String locale, String currency, LegalSettings legal, PaymentSettings payment,
                                MailSettings mail, StorefrontSettings storefront, Map<String, Boolean> features,
                                TenantLimits limits, boolean trial, AdminCommand admin, StoreType type) {
    }

    public record UpdateCommand(String name, List<String> domains, Map<String, String> theme, String locale,
                                String currency, LegalSettings legal, PaymentSettings payment, MailSettings mail,
                                StorefrontSettings storefront, Map<String, Boolean> features,
                                TenantLimits limits, StoreType type) {
    }
```

In `create`, replace `Map<String, Boolean> features = validFeatures(c.features());` with:

```java
        StoreType type = c.type() == null ? StoreType.GENERAL : c.type();
        Map<String, Boolean> features = new TreeMap<>(type.features());
        features.putAll(validFeatures(c.features())); // what the operator asked for wins over the preset
```

and replace the `tenants.save(new Tenant(...))` call's trailing `features, null, limits, trialEndsAt));` with
`features, null, limits, trialEndsAt, type.settings(), type));`.

In `update`, replace the trailing `current.trialEndsAt(), current.settings()));` with:

```java
                current.trialEndsAt(), current.settings(),
                u.type() != null ? u.type() : current.type())); // a label only: the preset is never re-applied
```

- [ ] **Step 10: Audit the type and the preset-able settings**

`TenantAudit.java`: in `fields()` add, after `"limits.requestsPerMinute"`:
`"type", "settings.commerce.shippingFee", "settings.commerce.freeShippingAbove", "settings.commerce.defaultTaxRate", "settings.commerce.stuckOrderDays", "settings.catalog.lowStockThreshold"`.
In `view(Tenant t)` add before the features loop:

```java
        v.put("type", t.type().name());
        v.put("settings.commerce.shippingFee", t.settings().commerce().shippingFee());
        v.put("settings.commerce.freeShippingAbove", t.settings().commerce().freeShippingAbove());
        v.put("settings.commerce.defaultTaxRate", t.settings().commerce().defaultTaxRate());
        v.put("settings.commerce.stuckOrderDays", t.settings().commerce().stuckOrderDays());
        v.put("settings.catalog.lowStockThreshold", t.settings().catalog().lowStockThreshold());
```

- [ ] **Step 11: Accept and return `type` in the controller**

`PlatformTenantController.java`: import `com.sixpack.shared.tenant.StoreType`. Add `@Size(max = 20) String type` as the last component of `CreateRequest` and of `UpdateRequest`. Add `String type` as the last component of `TenantView`, and pass `t.type().name()` last in `TenantView.from`. Build the commands with the type:

```java
        Tenant created = service.create(new CreateCommand(r.slug(), r.name(), r.domains(), r.theme(), r.locale(),
                r.currency(), r.legal(), r.payment(), r.mail(), r.storefront(), r.features(), r.limits(),
                Boolean.TRUE.equals(r.trial()), admin, StoreType.parse(r.type())));
```

```java
        Tenant updated = service.update(id, new UpdateCommand(r.name(), r.domains(), r.theme(), r.locale(),
                r.currency(), r.legal(), r.payment(), r.mail(), r.storefront(), r.features(), r.limits(),
                r.type() == null ? null : StoreType.parse(r.type())));
```

`StoreType.parse` runs before `service.create`, so an unknown type never creates anything.

- [ ] **Step 12: Run the tests**

Run: `mvn -q -o test -Dtest='StoreTypeTest,PlatformStoreTypesIntegrationTest,PlatformAdminIntegrationTest,TenantConfigurationIntegrationTest,TenantStatusLifecycleIntegrationTest,StoreSettingsIntegrationTest'`
Expected: all PASS. If an older test fails on a changed audit diff (new `type`/`settings.*` fields), the diff now lists more fields: update only that assertion, not the behaviour.

- [ ] **Step 13: Commit**

```bash
git add backend/src
git commit -m "feat(tenant): store types preset a new store once

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Store details for the workspace (settings, usage, defaults)

**Files:**
- Modify: `backend/src/main/java/com/sixpack/storage/FileStorage.java`, `storage/infra/local/LocalFileStorage.java`
- Modify: `backend/src/main/java/com/sixpack/product/CatalogReads.java`, `product/application/ProductCatalogService.java`
- Create: `backend/src/main/java/com/sixpack/tenant/application/TenantUsage.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/web/PlatformTenantController.java`
- Test: `backend/src/test/java/com/sixpack/it/PlatformStoreDetailsIntegrationTest.java`

**Interfaces:**
- Consumes: `Tenant#type()`, `TenantView` (Task 1).
- Produces: `long FileStorage#usedBytes()` (tenant in context), `long CatalogReads#count()` (tenant in context).
- Produces: `TenantUsage#of(Tenant) : Optional<TenantUsage.Usage>`, `record Usage(long products, long uploadBytes)`.
- Produces: `GET /api/v1/platform/tenants/{id}` → `{success, tenant, settings, usage|null, defaults:{features:{variants,marketing}, limits:{products, uploadBytes, requestsPerMinute}}}`. The list endpoint is unchanged apart from `type`.

- [ ] **Step 1: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/PlatformStoreDetailsIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.shared.tenant.StoreSettings;
import com.sixpack.tenant.domain.Tenant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123",
        "app.tenant.limits.products=50",
        "app.tenant.limits.requests-per-minute=100000"})
class PlatformStoreDetailsIntegrationTest extends TenantIntegrationTest {

    private String platformToken() throws Exception {
        return body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private ResultActions details(Tenant t) throws Exception {
        return mvc.perform(get("/api/v1/platform/tenants/" + t.id()).header("Authorization", bearer(platformToken())));
    }

    private void newProduct(Tenant tenant, String token, String name) throws Exception {
        mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .with(host(hostOf(tenant)))
                        .param("name", name).param("price", "10").param("stock", "1").param("category", "misc")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated());
    }

    @Test
    void detailsCarryTheOwnersSettingsAndThePlatformDefaults() throws Exception {
        Tenant acme = createTenant("acme", "Acme", "shop.acme.test");
        tenantRepository.save(acme.withOwnerSettings(new StoreSettings(
                new StoreSettings.Commerce(49.0, null, null, null, null, null, null), null, null, null),
                acme.storefront()));
        details(acme).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("acme"))
                .andExpect(jsonPath("$.settings.commerce.shippingFee").value(49.0))
                .andExpect(jsonPath("$.defaults.features.marketing").value(false))
                .andExpect(jsonPath("$.defaults.limits.products").value(50))
                .andExpect(jsonPath("$.defaults.limits.requestsPerMinute").value(100000))
                .andExpect(jsonPath("$.usage.products").value(0));
    }

    @Test
    void usageCountsOnlyThatStoresProductsAndFiles() throws Exception {
        Tenant acme = createTenant("acme", "Acme", "shop.acme.test");
        Tenant globex = createTenant("globex", "Globex", "shop.globex.test");
        String adminA = adminTokenFor(acme);
        newProduct(acme, adminA, "One");
        newProduct(acme, adminA, "Two");
        newProduct(globex, adminTokenFor(globex), "Solo");

        JsonNode a = body(details(acme).andReturn()).get("usage");
        JsonNode g = body(details(globex).andReturn()).get("usage");
        assertThat(a.get("products").asLong()).isEqualTo(2);
        assertThat(g.get("products").asLong()).isEqualTo(1);
        assertThat(g.get("uploadBytes").asLong()).isPositive();
        assertThat(a.get("uploadBytes").asLong()).isEqualTo(2 * g.get("uploadBytes").asLong());
    }

    @Test
    void theListCarriesEachStoresType() throws Exception {
        createTenant("acme", "Acme", "shop.acme.test");
        mvc.perform(get("/api/v1/platform/tenants").header("Authorization", bearer(platformToken())))
                .andExpect(jsonPath("$.tenants[?(@.slug == 'acme')].type").value("GENERAL"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -o test -Dtest=PlatformStoreDetailsIntegrationTest`
Expected: FAIL: `No value at JSON path "$.settings.commerce.shippingFee"`.

- [ ] **Step 3: Add `usedBytes()` and `count()`**

`storage/FileStorage.java`, after `tenantFiles()`:

```java
    /** Bytes stored for the tenant in context (shown against its upload limit). */
    long usedBytes();
```

`LocalFileStorage.java`, next to `tenantFiles()`:

```java
    @Override
    public long usedBytes() {
        return sizeOf(tenantDir());
    }
```

`product/CatalogReads.java`, inside the interface:

```java
    /** Products in the store in context (shown against its product limit). */
    long count();
```

`ProductCatalogService.java`:

```java
    @Override
    public long count() {
        return products.count();
    }
```

- [ ] **Step 4: Add `TenantUsage`**

`backend/src/main/java/com/sixpack/tenant/application/TenantUsage.java`:

```java
package com.sixpack.tenant.application;

import com.sixpack.product.CatalogReads;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.storage.FileStorage;
import com.sixpack.tenant.domain.Tenant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** What one store uses of its limits, counted inside that store's context (like an export). */
@Component
public class TenantUsage {

    private static final Logger log = LoggerFactory.getLogger(TenantUsage.class);

    public record Usage(long products, long uploadBytes) {
    }

    private final CatalogReads catalog;
    private final FileStorage storage;

    TenantUsage(CatalogReads catalog, FileStorage storage) {
        this.catalog = catalog;
        this.storage = storage;
    }

    /** Empty when counting fails: the store details still load, without usage. */
    public Optional<Usage> of(Tenant tenant) {
        try {
            return Optional.of(TenantContext.callAs(new TenantRef(tenant.id(), tenant.slug()),
                    () -> new Usage(catalog.count(), storage.usedBytes())));
        } catch (RuntimeException e) {
            log.warn("Could not count usage of tenant '{}'", tenant.slug(), e);
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 5: Return the details from `GET /{id}`**

`PlatformTenantController.java`: import `com.sixpack.shared.tenant.Feature`, `com.sixpack.tenant.application.FeatureProperties`, `LimitProperties`, `TenantUsage`, `java.util.LinkedHashMap`. Inject them:

```java
    private final TenantUsage usage;
    private final FeatureProperties featureDefaults;
    private final LimitProperties limitDefaults;

    PlatformTenantController(TenantAdminService service, TenantExport export, TenantUsage usage,
                             FeatureProperties featureDefaults, LimitProperties limitDefaults) {
        this.service = service;
        this.export = export;
        this.usage = usage;
        this.featureDefaults = featureDefaults;
        this.limitDefaults = limitDefaults;
    }
```

Replace `get`:

```java
    /** One store for its workspace: the store, the owner's own shop rules, usage and the platform defaults. */
    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id) {
        Tenant t = service.get(id);
        Map<String, Boolean> features = new LinkedHashMap<>();
        for (Feature f : Feature.values()) {
            features.put(f.key(), featureDefaults.defaults().getOrDefault(f.key(), false));
        }
        Map<String, Object> limits = Map.of("products", limitDefaults.products(),
                "uploadBytes", limitDefaults.uploads().toBytes(),
                "requestsPerMinute", limitDefaults.requestsPerMinute());
        Map<String, Object> body = new LinkedHashMap<>(ApiResponse.of("tenant", TenantView.from(t),
                "settings", t.settings(), "defaults", Map.of("features", features, "limits", limits)));
        body.put("usage", usage.of(t).orElse(null)); // Map.of refuses nulls
        return body;
    }
```

- [ ] **Step 6: Run the tests**

Run: `mvn -q -o test -Dtest='PlatformStoreDetailsIntegrationTest,TenantLimitsIntegrationTest,TenantOffboardingIntegrationTest,ModularityTests'`
Expected: all PASS (`ModularityTests` proves tenant → product adds no cycle).

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(platform): store details with owner settings, usage and platform defaults

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Store-types catalogue endpoint and the type on Store settings

**Files:**
- Create: `backend/src/main/java/com/sixpack/tenant/web/PlatformStoreTypeController.java`
- Modify: `backend/src/main/java/com/sixpack/tenant/application/StoreSettingsService.java`, `tenant/web/StoreSettingsController.java`
- Test: `backend/src/test/java/com/sixpack/it/PlatformStoreTypesIntegrationTest.java` (add tests), `backend/src/test/java/com/sixpack/it/StoreSettingsIntegrationTest.java` (add a test)

**Interfaces:**
- Consumes: `StoreType` (Task 1).
- Produces: `GET /api/v1/platform/store-types` → `{success, types:[{key, label, description, features, settings}]}` in enum order.
- Produces: `GET/PUT /api/v1/store/settings` response gains `type` (string). `StoreSettingsService.View` gains a trailing `StoreType type`.

Why an endpoint: the onboarding page shows what each type pre-sets; serving the presets from the backend keeps one source of truth (the spec's onboarding call budget becomes 1 on first load; Task 11 updates the spec).

- [ ] **Step 1: Write the failing tests**

Add to `PlatformStoreTypesIntegrationTest`:

```java
    @Test
    void theOperatorCanListTheStoreTypesAndTheirPresets() throws Exception {
        mvc.perform(get("/api/v1/platform/store-types").header("Authorization", bearer(platformToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types.length()").value(4))
                .andExpect(jsonPath("$.types[0].key").value("GENERAL"))
                .andExpect(jsonPath("$.types[1].label").value("Fashion"))
                .andExpect(jsonPath("$.types[1].features.variants").value(true))
                .andExpect(jsonPath("$.types[2].settings.commerce.shippingFee").value(40.0));
        mvc.perform(get("/api/v1/platform/store-types")).andExpect(status().isUnauthorized());
    }
```

Add to `StoreSettingsIntegrationTest` (import `com.sixpack.shared.tenant.StoreType` and `com.sixpack.tenant.domain.Tenant` if not already imported):

```java
    @Test
    void theSettingsNameTheStoresType() throws Exception {
        getSettings(acme, adminA).andExpect(jsonPath("$.type").value("GENERAL"));
        Tenant a = tenantRepository.findById(acme.id()).orElseThrow();
        tenantRepository.save(new Tenant(a.id(), a.slug(), a.name(), a.status(), a.domains(), a.theme(), a.locale(),
                a.currency(), a.createdAt(), a.legal(), a.payment(), a.mail(), a.storefront(), a.features(),
                a.deletionDueAt(), a.limits(), a.trialEndsAt(), a.settings(), StoreType.FASHION));
        tenantRegistry.refresh(); // StoreSettingsService.get reads through the cached registry
        getSettings(acme, adminA).andExpect(jsonPath("$.type").value("FASHION"));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -o test -Dtest='PlatformStoreTypesIntegrationTest,StoreSettingsIntegrationTest'`
Expected: FAIL: store-types returns 404; `No value at JSON path "$.type"`.

- [ ] **Step 3: Add the catalogue controller**

`backend/src/main/java/com/sixpack/tenant/web/PlatformStoreTypeController.java`:

```java
package com.sixpack.tenant.web;

import com.sixpack.shared.tenant.StoreType;
import com.sixpack.shared.web.ApiResponse;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The store types the operator can onboard, with what each one pre-sets (ADR-030). */
@RestController
@RequestMapping("/api/v1/platform/store-types")
class PlatformStoreTypeController {

    @GetMapping
    Map<String, Object> list() {
        return ApiResponse.of("types", Arrays.stream(StoreType.values()).map(t -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("key", t.name());
            v.put("label", t.label());
            v.put("description", t.description());
            v.put("features", t.features());
            v.put("settings", t.settings());
            return v;
        }).toList());
    }
}
```

- [ ] **Step 4: Put the type on the Store settings view**

`StoreSettingsService.java`: import `com.sixpack.shared.tenant.StoreType`; change `View` and `view`:

```java
    /** @param defaults what each rule returns to when the store clears its own value */
    public record View(StoreSettings settings, StorefrontSettings storefront, StoreConfig.Effective effective,
                       StoreConfig.Effective defaults, StoreType type) {
    }
```

```java
    private View view(Tenant t) {
        return new View(t.settings(), t.storefront(), StoreConfigService.resolve(t.settings(), defaults),
                StoreConfigService.resolve(StoreSettings.NONE, defaults), t.type());
    }
```

`StoreSettingsController.body`: replace the return with

```java
        Map<String, Object> out = new java.util.LinkedHashMap<>(
                ApiResponse.of("settings", settings, "effective", v.effective(), "defaults", v.defaults()));
        out.put("type", v.type().name()); // ApiResponse.of stops at three pairs
        return out;
```

- [ ] **Step 5: Run the tests**

Run: `mvn -q -o test -Dtest='PlatformStoreTypesIntegrationTest,StoreSettingsIntegrationTest'`
Expected: all PASS.

- [ ] **Step 6: Run the whole backend suite (background, ~10 min)**

Run: `mvn -o verify > "$TMPDIR/verify.log" 2>&1; echo exit=$? >> "$TMPDIR/verify.log"`
Expected: `BUILD SUCCESS`, `exit=0`.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(platform): list store types and show the type on Store settings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Shared console building blocks (tabs, confirm dialog, sidebar slot, routes)

**Files:**
- Create: `ecommerce-frontend/src/components/admin/tabsKeyboard.ts`, `tabsKeyboard.test.ts`, `Tabs.tsx`, `ConfirmDialog.tsx`
- Modify: `ecommerce-frontend/src/components/admin/AdminSidebar.tsx`, `AdminLayout.tsx`, `DetailLayout.tsx`
- Modify: `ecommerce-frontend/src/utils/routes.ts`, `utils/routes.test.ts`, `components/footer.tsx`
- Modify: `ecommerce-frontend/src/styles/admin-styles/_admin-ui.scss`

**Interfaces:**
- Produces: `nextTabIndex(key: string, current: number, count: number): number | null`.
- Produces: `<Tabs tabs={TabDef[]} active={string} hrefFor={(key) => string} label={string} canLeave?={(key: string) => boolean} />`, `type TabDef = { key: string; label: string; Icon?: IconType }`. Tab ids are `tab-<key>`; panels use `aria-labelledby="tab-<key>"`.
- Produces: `<ConfirmDialog open title body confirmLabel danger? requireText? busy? onConfirm onCancel />`.
- Produces: `Sidebar({ head, sections })` exported from `AdminSidebar.tsx`, `type SidebarSection = { title: string; items: { url: string; text: string; Icon: IconType; end?: boolean }[] }`.
- Produces: `AdminLayout` and `DetailLayout` accept `sidebar?: ReactNode` (default `<AdminSidebar />`).
- Produces: `isConsolePath(pathname): boolean` (true for `/admin…` and `/platform…`).

- [ ] **Step 1: Write the failing tests**

`ecommerce-frontend/src/components/admin/tabsKeyboard.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { nextTabIndex } from "./tabsKeyboard";

describe("nextTabIndex", () => {
  it("moves right and left, wrapping at the ends", () => {
    expect(nextTabIndex("ArrowRight", 0, 3)).toBe(1);
    expect(nextTabIndex("ArrowRight", 2, 3)).toBe(0);
    expect(nextTabIndex("ArrowLeft", 0, 3)).toBe(2);
  });

  it("jumps to the first and last tab", () => {
    expect(nextTabIndex("Home", 2, 5)).toBe(0);
    expect(nextTabIndex("End", 0, 5)).toBe(4);
  });

  it("ignores other keys", () => {
    expect(nextTabIndex("Enter", 1, 3)).toBeNull();
  });
});
```

Add to `ecommerce-frontend/src/utils/routes.test.ts` (import `isConsolePath`, `showsBottomNav`, `surfaceFor` as needed):

```ts
describe("console paths", () => {
  it("treats the platform console like the admin", () => {
    expect(isConsolePath("/platform")).toBe(true);
    expect(isConsolePath("/platform/stores/acme")).toBe(true);
    expect(isConsolePath("/admin/settings")).toBe(true);
    expect(isConsolePath("/platformer")).toBe(false);
    expect(surfaceFor("/platform/stores")).toBe("admin");
    expect(showsBottomNav("/platform/stores")).toBe(false);
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run (in `ecommerce-frontend/`): `npx vitest run src/components/admin/tabsKeyboard.test.ts src/utils/routes.test.ts`
Expected: FAIL, `Failed to resolve import "./tabsKeyboard"` and `isConsolePath is not a function`.

- [ ] **Step 3: Implement the keyboard helper and routes**

`ecommerce-frontend/src/components/admin/tabsKeyboard.ts`:

```ts
/** WAI-ARIA tabs: Left/Right wrap, Home/End jump. Null for keys the tab list does not handle. */
export const nextTabIndex = (key: string, current: number, count: number): number | null => {
  switch (key) {
    case "ArrowRight":
      return (current + 1) % count;
    case "ArrowLeft":
      return (current - 1 + count) % count;
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
};
```

`ecommerce-frontend/src/utils/routes.ts`: add and use `isConsolePath`:

```ts
/** The store admin and the platform console: own sidebar, admin tokens, no storefront footer or bottom nav. */
export const isConsolePath = (pathname: string) => /^\/(admin|platform)(\/|$)/.test(pathname);

/** Routes that keep the full header instead: consoles have their own sidebar, checkout has its own CTA. */
export const showsBottomNav = (pathname: string) => !isConsolePath(pathname) && !pathname.startsWith("/shipping");
```

and

```ts
/** Which token set (see _tokens.scss) a path uses: the admin and the platform console are admin. */
export const surfaceFor = (pathname: string): "store" | "admin" => (isConsolePath(pathname) ? "admin" : "store");
```

`components/footer.tsx`: import `isConsolePath` from `../utils/routes` and replace `if (pathname.startsWith("/admin")) return null;` with `if (isConsolePath(pathname)) return null;`.

- [ ] **Step 4: Run the tests**

Run: `npx vitest run src/components/admin/tabsKeyboard.test.ts src/utils/routes.test.ts`
Expected: PASS.

- [ ] **Step 5: Add `Tabs` and `ConfirmDialog`**

`ecommerce-frontend/src/components/admin/Tabs.tsx`:

```tsx
import { KeyboardEvent, MouseEvent, useRef } from "react";
import { IconType } from "react-icons";
import { Link, useNavigate } from "react-router-dom";
import { nextTabIndex } from "./tabsKeyboard";

export type TabDef = { key: string; label: string; Icon?: IconType };

type Props = {
  tabs: TabDef[];
  active: string;
  hrefFor: (key: string) => string;
  /** Accessible name of the tab list, e.g. "Store settings sections". */
  label: string;
  /** Return false to stay on the current tab; receives the tab the user asked for (e.g. to ask about unsaved changes). */
  canLeave?: (key: string) => boolean;
};

/** Linkable tabs: each tab is a URL, so Back and deep links work. The caller renders the matching tabpanel. */
const Tabs = ({ tabs, active, hrefFor, label, canLeave }: Props) => {
  const navigate = useNavigate();
  const refs = useRef<(HTMLAnchorElement | null)[]>([]);
  const current = Math.max(0, tabs.findIndex((t) => t.key === active));

  const go = (index: number) => {
    const tab = tabs[index];
    if (tab.key === active || (canLeave && !canLeave(tab.key))) return;
    navigate(hrefFor(tab.key));
    refs.current[index]?.focus();
  };

  const onKeyDown = (e: KeyboardEvent) => {
    const next = nextTabIndex(e.key, current, tabs.length);
    if (next === null) return;
    e.preventDefault();
    go(next);
  };

  const onClick = (e: MouseEvent, key: string) => {
    if (key !== active && canLeave && !canLeave(key)) e.preventDefault();
  };

  return (
    <div className="tabs" role="tablist" aria-label={label} onKeyDown={onKeyDown}>
      {tabs.map(({ key, label: text, Icon }, i) => (
        <Link
          key={key}
          ref={(el) => (refs.current[i] = el)}
          id={`tab-${key}`}
          role="tab"
          aria-selected={key === active}
          tabIndex={key === active ? 0 : -1}
          className={`tabs__tab${key === active ? " tabs__tab--active" : ""}`}
          to={hrefFor(key)}
          onClick={(e) => onClick(e, key)}
        >
          {Icon && <Icon aria-hidden />}
          {text}
        </Link>
      ))}
    </div>
  );
};

export default Tabs;
```

`ecommerce-frontend/src/components/admin/ConfirmDialog.tsx`:

```tsx
import { ReactNode, useEffect, useRef, useState } from "react";

type Props = {
  open: boolean;
  title: string;
  body: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  /** When set, the confirm button stays disabled until this exact text is typed (e.g. the store's slug). */
  requireText?: string;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

/** A modal confirm built on <dialog>: focus is trapped, Escape cancels. Replaces window.confirm in the consoles. */
const ConfirmDialog = ({ open, title, body, confirmLabel, danger, requireText, busy, onConfirm, onCancel }: Props) => {
  const ref = useRef<HTMLDialogElement>(null);
  const [typed, setTyped] = useState("");

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      setTyped("");
      dialog.showModal();
    }
    if (!open && dialog.open) dialog.close();
  }, [open]);

  const blocked = busy || (requireText !== undefined && typed !== requireText);

  return (
    <dialog ref={ref} className="confirm-dialog" aria-labelledby="confirm-dialog-title" onCancel={(e) => {
      e.preventDefault();
      onCancel();
    }}>
      <h2 id="confirm-dialog-title">{title}</h2>
      <div className="confirm-dialog__body">{body}</div>
      {requireText !== undefined && (
        <label className="confirm-dialog__check">
          Type <code>{requireText}</code> to confirm
          <input value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" spellCheck={false} />
        </label>
      )}
      <div className="confirm-dialog__actions">
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>Cancel</button>
        <button type="button" className={`btn ${danger ? "btn--danger" : "btn--primary"}`} onClick={onConfirm} disabled={blocked}>
          {busy ? "Working…" : confirmLabel}
        </button>
      </div>
    </dialog>
  );
};

export default ConfirmDialog;
```

Check `btn--danger` exists (`grep -rn "btn--danger" ecommerce-frontend/src/styles`); if not, add it in Step 7 next to `btn--primary` using the existing danger token (`grep -n "danger\|--x\b\|error" ecommerce-frontend/src/styles/_tokens.scss`).

- [ ] **Step 6: Extract `Sidebar` and add the `sidebar` slot**

`AdminSidebar.tsx`: turn the component body into an exported generic `Sidebar`, keep `AdminSidebar` as its store-admin use. The drawer state, backdrop, hamburger and classes stay exactly as they are:

```tsx
export type SidebarSection = { title: string; items: { url: string; text: string; Icon: IconType; end?: boolean }[] };

/** Fixed sidebar on wide screens, an icon rail at 1101–1280px, an off-canvas drawer below (see .admin-sidebar in app.scss). */
export const Sidebar = ({ head, sections }: { head: ReactNode; sections: SidebarSection[] }) => {
  const [open, setOpen] = useState(false);
  const { pathname } = useLocation();

  // Close the drawer after navigating.
  useEffect(() => setOpen(false), [pathname]);

  return (
    <>
      <button id="hamburger" onClick={() => setOpen(true)} aria-label="Open menu">
        <LuMenu />
      </button>
      {open && <div className="admin-sidebar__backdrop" onClick={() => setOpen(false)} />}

      <aside className={`admin-sidebar${open ? " admin-sidebar--open" : ""}`}>
        <div className="admin-sidebar__top">
          <strong>Menu</strong>
          <button className="icon-btn" onClick={() => setOpen(false)} aria-label="Close menu">
            <LuX />
          </button>
        </div>

        <div className="admin-sidebar__store">{head}</div>

        {sections.map((section) => (
          <div key={section.title}>
            <h5>{section.title}</h5>
            <ul>
              {section.items.map(({ url, text, Icon, end }) => (
                <li key={url}>
                  <NavLink to={url} title={text} end={end}>
                    <Icon aria-hidden />
                    <span className="admin-sidebar__label">{text}</span>
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </aside>
    </>
  );
};

const AdminSidebar = () => (
  <Sidebar
    head={
      <>
        <strong>{brand.name}</strong>
        <Link to="/">View store</Link>
      </>
    }
    sections={[
      { title: "Manage", items: NAV.filter(({ url }) => adminPageVisible(url, features)) },
      { title: "Support", items: SUPPORT },
    ]}
  />
);

export default AdminSidebar;
```

Add `ReactNode` to the React import. Check the drawer and rail CSS still target `.admin-sidebar h5` / `ul` (the extra `<div>` wrapper per section): `grep -n "admin-sidebar" ecommerce-frontend/src/styles/app.scss`. If selectors use `> h5` or `> ul`, drop the wrapper and use a `<Fragment key=…>` instead.

`AdminLayout.tsx`:

```tsx
type Props = { children: ReactNode; title?: string; description?: string; actions?: ReactNode; sidebar?: ReactNode };

/** Admin shell. With a `title` the page gets the shared PageHeader; pages with their own header leave it out. */
const AdminLayout = ({ children, title, description, actions, sidebar }: Props) => (
  <div className="admin-container">
    {sidebar ?? <AdminSidebar />}
    <main className="admin-main">
      {title && <PageHeader title={title} description={description} actions={actions} />}
      {children}
    </main>
  </div>
);
```

`DetailLayout.tsx`: add `sidebar?: ReactNode` to `Props`, destructure it, and render `<AdminLayout sidebar={sidebar}>`.

- [ ] **Step 7: Styles for tabs and the dialog**

Append to `ecommerce-frontend/src/styles/admin-styles/_admin-ui.scss` (use the file's existing variables; check names with `grep -n "^\$" ecommerce-frontend/src/styles/admin-styles/_mixin.scss`):

```scss
// Linkable tabs (components/admin/Tabs.tsx). Scrolls sideways on narrow screens.
.tabs {
  display: flex;
  gap: 0.25rem;
  overflow-x: auto;
  border-bottom: 1px solid var(--border);
  margin-bottom: 1rem;
  scrollbar-width: thin;

  &__tab {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0.6rem 0.9rem;
    white-space: nowrap;
    color: var(--text-muted);
    border-bottom: 2px solid transparent;
    text-decoration: none;

    &:hover {
      color: var(--text);
    }

    &:focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: -2px;
    }

    &--active {
      color: var(--text);
      border-bottom-color: var(--accent);
      font-weight: 600;
    }
  }
}

// Modal confirm (components/admin/ConfirmDialog.tsx).
.confirm-dialog {
  max-width: min(28rem, calc(100vw - 2rem));
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 1.25rem;
  background: var(--surface, #fff);
  color: var(--text);

  &::backdrop {
    background: rgb(0 0 0 / 40%);
  }

  h2 {
    margin: 0 0 0.5rem;
    font-size: 1.1rem;
  }

  &__check {
    display: grid;
    gap: 0.35rem;
    margin-top: 1rem;
  }

  &__actions {
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
    margin-top: 1.25rem;
  }
}
```

Use the real token names from `_tokens.scss` where these differ (`--surface`, `--radius-md`, `--text-muted`).

- [ ] **Step 8: Check the store admin still works**

Run: `npx vitest run && npx tsc --noEmit -p . && npm run -s lint`
Expected: all tests pass, no type or lint errors. (The admin sidebar renders the same markup; the browser pass in Task 11 checks it visually.)

- [ ] **Step 9: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(admin): linkable tabs, confirm dialog and a sidebar slot for the consoles

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Platform console shell (routes, sign-in, sidebar, audit page)

**Files:**
- Modify: `ecommerce-frontend/src/redux/api/platformAPI.ts`
- Create: `ecommerce-frontend/src/pages/platform/PlatformLogin.tsx`, `PlatformSidebar.tsx`, `PlatformAudit.tsx`
- Rewrite: `ecommerce-frontend/src/pages/platform/platform.tsx`
- Modify: `ecommerce-frontend/src/App.tsx`
- Rewrite: `ecommerce-frontend/src/styles/_platform.scss`

**Interfaces:**
- Consumes: `Sidebar`, `SidebarSection`, `AdminLayout` `sidebar` prop (Task 4); store details and store types (Tasks 1–3).
- Produces in `platformAPI.ts`:
  - `type StoreTypeKey = "GENERAL" | "FASHION" | "GROCERY" | "ELECTRONICS"`
  - `PlatformTenant` gains `type: StoreTypeKey`
  - `type StoreTypeInfo = { key: StoreTypeKey; label: string; description: string; features: Record<string, boolean>; settings: Omit<StoreSettings, "storefront"> }`
  - `type TenantDetails = { tenant: PlatformTenant; settings: Omit<StoreSettings, "storefront">; usage: { products: number; uploadBytes: number } | null; defaults: { features: Record<string, boolean>; limits: { products: number; uploadBytes: number; requestsPerMinute: number } } }`
  - `NewTenantInput` gains `type?: StoreTypeKey`
  - hooks `useTenantQuery(id)`, `useStoreTypesQuery()` (plus the existing ones); `updateTenant`/`setTenantStatus` also invalidate `{ type: "tenant", id }`
  - `PLATFORM_EMAIL_KEY`, `PLATFORM_SIGNED_OUT` (window event name), `signOutPlatform()`
- Produces: `<PlatformShell>` children wrapper: `({ children }) => <AdminLayout sidebar={<PlatformSidebar />}>{children}</AdminLayout>` exported from `PlatformSidebar.tsx`.
- Produces routes: `/platform` → `stores`, `/platform/stores`, `/platform/stores/new`, `/platform/stores/:slug/:tab?`, `/platform/audit`.

- [ ] **Step 1: Extend the platform API**

In `storeAPI.ts`, next to `StoreSettings`, add (the store admin needs it too, and this keeps the import one-way: `platformAPI` → `storeAPI`):

```ts
export type StoreTypeKey = "GENERAL" | "FASHION" | "GROCERY" | "ELECTRONICS";
```

In `platformAPI.ts` (import `StoreSettings` and `StoreTypeKey` from `./storeAPI`, and re-export `StoreTypeKey` with `export type { StoreTypeKey } from "./storeAPI";`):

```ts
export type OwnerSettings = Omit<StoreSettings, "storefront">;
export type StoreTypeInfo = {
  key: StoreTypeKey;
  label: string;
  description: string;
  features: Record<string, boolean>;
  settings: OwnerSettings;
};
export type PlatformDefaults = {
  features: Record<string, boolean>;
  limits: { products: number; uploadBytes: number; requestsPerMinute: number };
};
export type TenantDetails = {
  success: boolean;
  tenant: PlatformTenant;
  settings: OwnerSettings;
  usage: { products: number; uploadBytes: number } | null;
  defaults: PlatformDefaults;
};

export const PLATFORM_EMAIL_KEY = "sixpack.platform.email";
/** Fired when the platform session ends (sign-out or a 401), so the console returns to sign-in. */
export const PLATFORM_SIGNED_OUT = "sixpack:platform-signed-out";

export const signOutPlatform = () => {
  localStorage.removeItem(PLATFORM_TOKEN_KEY);
  localStorage.removeItem(PLATFORM_EMAIL_KEY);
  window.dispatchEvent(new Event(PLATFORM_SIGNED_OUT));
};
```

Add `type: StoreTypeKey;` to `PlatformTenant` and `type?: StoreTypeKey;` to `NewTenantInput`.

Wrap the base query so any 401 signs out (replace `baseQuery: fetchBaseQuery({...})` with `baseQuery: platformBaseQuery` defined above `createApi`):

```ts
const rawPlatformQuery = fetchBaseQuery({
  baseUrl: `${server}/api/v1/platform/`,
  prepareHeaders: (headers) => {
    const token = localStorage.getItem(PLATFORM_TOKEN_KEY);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    return headers;
  },
});

const platformBaseQuery: typeof rawPlatformQuery = async (args, api, extra) => {
  const result = await rawPlatformQuery(args, api, extra);
  if (result.error?.status === 401 && localStorage.getItem(PLATFORM_TOKEN_KEY)) signOutPlatform();
  return result;
};
```

Add `"tenant"` and `"storeTypes"` to `tagTypes`, and these endpoints:

```ts
    tenant: builder.query<TenantDetails, string>({
      query: (id) => `tenants/${id}`,
      providesTags: (_r, _e, id) => [{ type: "tenant", id }],
    }),
    storeTypes: builder.query<{ success: boolean; types: StoreTypeInfo[] }, void>({
      query: () => "store-types",
      providesTags: ["storeTypes"],
      keepUnusedDataFor: 3600, // presets change only with a release
    }),
```

Change `updateTenant` and `setTenantStatus` to `invalidatesTags: (_r, _e, { id }) => ["tenants", "audit", { type: "tenant", id }]`. Export `useTenantQuery` and `useStoreTypesQuery`.

- [ ] **Step 2: Sign-in page and sidebar**

`ecommerce-frontend/src/pages/platform/PlatformLogin.tsx`: move the existing `Login` component here unchanged, except: export it as default `PlatformLogin`, store the email on success (`localStorage.setItem(PLATFORM_EMAIL_KEY, email);` next to the token), and wrap the form in `<div className="platform-login">` instead of `container platform`.

`ecommerce-frontend/src/pages/platform/PlatformSidebar.tsx`:

```tsx
import { ReactNode } from "react";
import { LuHistory, LuLogOut, LuPlusCircle, LuStore } from "react-icons/lu";
import AdminLayout from "../../components/admin/AdminLayout";
import { Sidebar } from "../../components/admin/AdminSidebar";
import { PLATFORM_EMAIL_KEY, signOutPlatform } from "../../redux/api/platformAPI";

const PlatformSidebar = () => (
  <Sidebar
    head={
      <>
        <strong>Platform console</strong>
        <small className="platform-sidebar__who">{localStorage.getItem(PLATFORM_EMAIL_KEY) ?? ""}</small>
        <button type="button" className="btn btn--sm platform-sidebar__out" onClick={signOutPlatform}>
          <LuLogOut aria-hidden /> Sign out
        </button>
      </>
    }
    sections={[
      {
        title: "Stores",
        items: [
          { url: "/platform/stores", text: "Stores", Icon: LuStore, end: true },
          { url: "/platform/stores/new", text: "Onboard store", Icon: LuPlusCircle },
        ],
      },
      { title: "Records", items: [{ url: "/platform/audit", text: "Audit log", Icon: LuHistory }] },
    ]}
  />
);

/** Every signed-in console page: the admin shell with the platform sidebar. */
export const PlatformShell = ({ children }: { children: ReactNode }) => (
  <AdminLayout sidebar={<PlatformSidebar />}>{children}</AdminLayout>
);
```

`end: true` on Stores keeps it from highlighting on `/platform/stores/new`. A store workspace (`/platform/stores/acme`) highlights Stores only if `end` is false; that is acceptable: the breadcrumb shows the way back. If `LuPlusCircle` is not exported by the installed `react-icons` version, use `LuPlus` (check: `grep -o "LuPlusCircle" node_modules/react-icons/lu/index.d.ts | head -1`).

- [ ] **Step 3: Audit page with a store filter from the URL**

`ecommerce-frontend/src/pages/platform/PlatformAudit.tsx`: move `PlatformAudit` here as the default export with these changes:
- No `tenants` prop: read them with `useTenantsQuery()`.
- Initial store filter from `?tenant=` (`const [params, setParams] = useSearchParams(); const tenantId = params.get("tenant") ?? "";`); changing the select calls `setParams(id ? { tenant: id } : {})` and resets the page to 1.
- Wrap in `<PlatformShell>`, with `<PageHeader title="Audit log" description="Every store's log, newest first. Read-only." />` and the table inside `<section className="card platform-scroll">`.
- Keep the table markup and the pager as they are.

- [ ] **Step 4: The console router**

Rewrite `ecommerce-frontend/src/pages/platform/platform.tsx`:

```tsx
import { lazy, Suspense, useEffect, useState } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import Loader from "../../components/admin/Loader";
import { PLATFORM_SIGNED_OUT, PLATFORM_TOKEN_KEY } from "../../redux/api/platformAPI";
import PlatformLogin from "./PlatformLogin";

const StoreList = lazy(() => import("./StoreList"));
const OnboardStore = lazy(() => import("./OnboardStore"));
const StoreWorkspace = lazy(() => import("./StoreWorkspace"));
const PlatformAudit = lazy(() => import("./PlatformAudit"));

/** Operator console for stores. Reachable on any domain; it signs in with platform credentials only. */
const Platform = () => {
  const [signedIn, setSignedIn] = useState(() => !!localStorage.getItem(PLATFORM_TOKEN_KEY));

  useEffect(() => {
    const out = () => setSignedIn(false);
    window.addEventListener(PLATFORM_SIGNED_OUT, out);
    return () => window.removeEventListener(PLATFORM_SIGNED_OUT, out);
  }, []);

  if (!signedIn) return <PlatformLogin onDone={() => setSignedIn(true)} />;
  return (
    <Suspense fallback={<Loader />}>
      <Routes>
        <Route index element={<Navigate to="stores" replace />} />
        <Route path="stores" element={<StoreList />} />
        <Route path="stores/new" element={<OnboardStore />} />
        <Route path="stores/:slug/:tab?" element={<StoreWorkspace />} />
        <Route path="audit" element={<PlatformAudit />} />
        <Route path="*" element={<Navigate to="stores" replace />} />
      </Routes>
    </Suspense>
  );
};

export default Platform;
```

Check `components/admin/Loader.tsx`'s export name and use it (or the app's `components/loader`).

Until Tasks 6–8 land, create placeholder files so the build passes, each exactly:

```tsx
import { PlatformShell } from "./PlatformSidebar";
// Replaced in Task 6/7/8.
const Placeholder = () => <PlatformShell><p>Coming in the next task.</p></PlatformShell>;
export default Placeholder;
```

for `StoreList.tsx`, `OnboardStore.tsx`, `StoreWorkspace.tsx`.

`App.tsx`: change `<Route path="/platform" element={<Platform />} />` to `<Route path="/platform/*" element={<Platform />} />`.

- [ ] **Step 5: Console styles**

Replace `ecommerce-frontend/src/styles/_platform.scss` with the console's own rules (the old `.platform__json`/`__actions` rules go away with the JSON editor):

```scss
// Platform console (pages/platform). Pages sit in the admin shell; these are console-only pieces.
.platform-login {
  max-width: 26rem;
  margin: 3rem auto;
  padding: 0 1rem;
}

.platform-sidebar__who {
  display: block;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
}

.platform-sidebar__out {
  margin-top: 0.5rem;
}

// admin-main hides overflow, so every console page scrolls inside its own container.
.platform-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.platform-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: center;
  margin-bottom: 1rem;
}

.platform-table {
  width: 100%;
  border-collapse: collapse;

  th,
  td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid var(--border);
  }
}
```

In `PlatformAudit.tsx` use `platform-filters` and `platform-table` in place of the old `platform__filters` / `platform__table` classes.

- [ ] **Step 6: Check**

Run: `npx vitest run && npx tsc --noEmit -p . && npm run -s lint`
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(platform): console shell with its own routes, sidebar and audit page

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Store list

**Files:**
- Create: `ecommerce-frontend/src/pages/platform/storeList.ts`, `storeList.test.ts`
- Create: `ecommerce-frontend/src/utils/storeTypes.ts` (type labels, shared with the store admin in Task 10)
- Replace placeholder: `ecommerce-frontend/src/pages/platform/StoreList.tsx`

**Interfaces:**
- Consumes: `useTenantsQuery`, `PlatformTenant` (Task 5), `DataTable`, `StatusBadge`, `PageHeader`.
- Produces: `type StatusChip = "all" | "active" | "trial" | "suspended" | "closing"`, `CHIPS: { key: StatusChip; label: string }[]`, `byChip(tenants, chip): PlatformTenant[]`, `domainSummary(domains: string[]): string`, `findBySlug(tenants: PlatformTenant[] | undefined, slug: string | undefined): PlatformTenant | undefined`, `typeLabel(type: StoreTypeKey): string`, `statusNote(t): string | null`.

- [ ] **Step 1: Write the failing tests**

`ecommerce-frontend/src/pages/platform/storeList.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { PlatformTenant } from "../../redux/api/platformAPI";
import { byChip, domainSummary, findBySlug, statusNote, typeLabel } from "./storeList";

const store = (slug: string, status: PlatformTenant["status"], extra: Partial<PlatformTenant> = {}): PlatformTenant => ({
  id: `id-${slug}`, slug, name: slug, status, domains: [`${slug}.test`], theme: {}, type: "GENERAL", ...extra,
});

const all = [
  store("a", "ACTIVE"), store("t", "TRIAL"), store("s", "SUSPENDED"),
  store("c", "CANCELLED"), store("d", "SCHEDULED_FOR_DELETION"),
];

describe("store list", () => {
  it("filters by status chip, with Closing covering cancelled and scheduled-for-deletion stores", () => {
    expect(byChip(all, "all")).toHaveLength(5);
    expect(byChip(all, "active").map((t) => t.slug)).toEqual(["a"]);
    expect(byChip(all, "closing").map((t) => t.slug)).toEqual(["c", "d"]);
  });

  it("shows the first domain and how many more", () => {
    expect(domainSummary(["a.test"])).toBe("a.test");
    expect(domainSummary(["a.test", "b.test", "c.test"])).toBe("a.test +2");
    expect(domainSummary([])).toBe("—");
  });

  it("finds a store by slug for deep links, and nothing for an unknown slug", () => {
    expect(findBySlug(all, "t")?.id).toBe("id-t");
    expect(findBySlug(all, "nope")).toBeUndefined();
    expect(findBySlug(undefined, "t")).toBeUndefined();
  });

  it("names types and explains dates", () => {
    expect(typeLabel("ELECTRONICS")).toBe("Electronics");
    expect(statusNote(store("t", "TRIAL", { trialEndsAt: "2026-11-20T00:00:00Z" }))).toBe("Trial ends 20 Nov 2026");
    expect(statusNote(store("d", "SCHEDULED_FOR_DELETION", { deletionDueAt: "2026-12-05T00:00:00Z" })))
      .toBe("Data deleted on 5 Dec 2026");
    expect(statusNote(store("a", "ACTIVE"))).toBeNull();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/pages/platform/storeList.test.ts`
Expected: FAIL, `Failed to resolve import "./storeList"`.

- [ ] **Step 3: Implement the list logic**

`ecommerce-frontend/src/pages/platform/storeList.ts`:

```ts
import type { PlatformTenant } from "../../redux/api/platformAPI";

export type StatusChip = "all" | "active" | "trial" | "suspended" | "closing";

export const CHIPS: { key: StatusChip; label: string }[] = [
  { key: "all", label: "All" },
  { key: "active", label: "Active" },
  { key: "trial", label: "Trial" },
  { key: "suspended", label: "Suspended" },
  { key: "closing", label: "Closing" },
];

const CHIP_STATUSES: Record<Exclude<StatusChip, "all">, PlatformTenant["status"][]> = {
  active: ["ACTIVE"],
  trial: ["TRIAL"],
  suspended: ["SUSPENDED"],
  closing: ["CANCELLED", "SCHEDULED_FOR_DELETION"],
};

export const byChip = (tenants: PlatformTenant[], chip: StatusChip) =>
  chip === "all" ? tenants : tenants.filter((t) => CHIP_STATUSES[chip].includes(t.status));

export const domainSummary = (domains: string[]) =>
  domains.length === 0 ? "—" : domains.length === 1 ? domains[0] : `${domains[0]} +${domains.length - 1}`;

/** The workspace URL carries the slug; the API is addressed by id. */
export const findBySlug = (tenants: PlatformTenant[] | undefined, slug: string | undefined) =>
  slug ? tenants?.find((t) => t.slug === slug) : undefined;

export { typeLabel } from "../../utils/storeTypes";

const day = (iso: string) => new Date(iso).toLocaleDateString("en-IN", { dateStyle: "medium", timeZone: "UTC" });

export const statusNote = (t: PlatformTenant) =>
  t.status === "TRIAL" && t.trialEndsAt ? `Trial ends ${day(t.trialEndsAt)}`
    : t.status === "SCHEDULED_FOR_DELETION" && t.deletionDueAt ? `Data deleted on ${day(t.deletionDueAt)}`
      : null;
```

`ecommerce-frontend/src/utils/storeTypes.ts` (kept outside the platform chunk so the store admin can use it):

```ts
import type { StoreTypeKey } from "../redux/api/storeAPI";

const TYPE_LABELS: Record<StoreTypeKey, string> = {
  GENERAL: "General", FASHION: "Fashion", GROCERY: "Grocery", ELECTRONICS: "Electronics",
};

export const typeLabel = (type: StoreTypeKey | undefined) => TYPE_LABELS[type ?? "GENERAL"];
```

- [ ] **Step 4: Run the tests**

Run: `npx vitest run src/pages/platform/storeList.test.ts`
Expected: PASS. (If `en-IN` medium renders "20-Nov-2026" in Node's ICU, change the expected strings to what `toLocaleDateString("en-IN", { dateStyle: "medium" })` prints and keep the implementation.)

- [ ] **Step 5: The page**

Replace `ecommerce-frontend/src/pages/platform/StoreList.tsx`:

```tsx
import { useMemo, useState } from "react";
import { LuPlus } from "react-icons/lu";
import { Link, useNavigate } from "react-router-dom";
import { Column } from "react-table";
import DataTable from "../../components/admin/DataTable";
import PageHeader from "../../components/admin/PageHeader";
import StatusBadge from "../../components/admin/StatusBadge";
import { errorMessage } from "../../redux/api/baseQuery";
import { PlatformTenant, useTenantsQuery } from "../../redux/api/platformAPI";
import { PlatformShell } from "./PlatformSidebar";
import { byChip, CHIPS, domainSummary, StatusChip, statusNote, typeLabel } from "./storeList";

type Row = { slug: string; name: string; type: string; domains: string; status: string; note: string | null; created: string };

const columns: Column<Row>[] = [
  {
    Header: "Store",
    accessor: "name",
    Cell: ({ row }) => (
      <span>
        <strong>{row.original.name}</strong> <small className="muted">{row.original.slug}</small>
      </span>
    ),
  },
  { Header: "Type", accessor: "type" },
  { Header: "Domains", accessor: "domains" },
  {
    Header: "Status",
    accessor: "status",
    Cell: ({ row }) => (
      <span>
        <StatusBadge status={row.original.status} />
        {row.original.note && <small className="muted platform-note">{row.original.note}</small>}
      </span>
    ),
  },
  { Header: "Created", accessor: "created" },
];

const toRow = (t: PlatformTenant): Row => ({
  slug: t.slug,
  name: t.name,
  type: typeLabel(t.type),
  domains: domainSummary(t.domains),
  status: t.status,
  note: statusNote(t),
  created: t.createdAt ? new Date(t.createdAt).toLocaleDateString("en-IN", { dateStyle: "medium" }) : "—",
});

/** Every store: search, status chips and sort run in the browser (one call). Click a row to open the store. */
const StoreList = () => {
  const { data, isLoading, isError, error } = useTenantsQuery();
  const [chip, setChip] = useState<StatusChip>("all");
  const navigate = useNavigate();
  const rows = useMemo(() => byChip(data?.tenants ?? [], chip).map(toRow), [data, chip]);

  return (
    <PlatformShell>
      <PageHeader
        title="Stores"
        description="Every store on the platform. Open one to configure it."
        actions={<Link className="btn btn--primary" to="/platform/stores/new"><LuPlus aria-hidden /> Onboard store</Link>}
      />
      {isError && <p role="alert">{errorMessage(error)}</p>}
      <DataTable<Row>
        columns={columns}
        data={rows}
        isLoading={isLoading}
        searchPlaceholder="Search by name, slug or domain"
        resetKey={chip}
        onRowClick={(r) => navigate(`/platform/stores/${r.slug}`)}
        emptyText="No stores match"
        filters={
          <div className="chips" role="group" aria-label="Status">
            {CHIPS.map((c) => (
              <button key={c.key} type="button" className={`chip${chip === c.key ? " chip--active" : ""}`}
                      aria-pressed={chip === c.key} onClick={() => setChip(c.key)}>
                {c.label}
              </button>
            ))}
          </div>
        }
      />
    </PlatformShell>
  );
};

export default StoreList;
```

Domain search: `DataTable`'s global filter matches the `domains` column text, which holds only the first domain and "+n". To search every domain, add a hidden accessor instead: give `Row` a `search: string` field (`[t.name, t.slug, ...t.domains].join(" ")`) and a column `{ id: "search", accessor: "search" }` hidden via the table's `initialState.hiddenColumns` if `DataTable` supports it; if it does not, skip the hidden column and note "search covers the first domain" in Task 11's report. Check `chip`/`chip--active`/`muted` classes exist (`grep -rn "\.chip\b\|chip--active\|\.muted" ecommerce-frontend/src/styles`); if not, add to `_platform.scss`:

```scss
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}

.chip {
  padding: 0.3rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: transparent;
  color: var(--text);
  cursor: pointer;

  &--active {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--accent-contrast, #fff);
  }
}

.platform-note {
  display: block;
  margin-top: 0.2rem;
}
```

Rows open with Enter only if `DataTable` makes clickable rows focusable; check how `onRowClick` renders (`grep -n "onRowClick" -A6 ecommerce-frontend/src/components/admin/DataTable.tsx`). If rows get no `tabIndex`/`onKeyDown`, add them there (`tabIndex={0}`, Enter/Space call `onRowClick`); this helps every admin table.

- [ ] **Step 6: Check**

Run: `npx vitest run && npx tsc --noEmit -p . && npm run -s lint`
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(platform): searchable store list with status chips

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Onboard store

**Files:**
- Create: `ecommerce-frontend/src/pages/platform/onboarding.ts`, `onboarding.test.ts`
- Replace placeholder: `ecommerce-frontend/src/pages/platform/OnboardStore.tsx`

**Interfaces:**
- Consumes: `useStoreTypesQuery`, `useCreateTenantMutation`, `StoreTypeInfo`, `StoreTypeKey` (Task 5).
- Produces: `slugFromName(name: string): string`, `presetSummary(type: StoreTypeInfo): string`, `fieldOf(message: string): string | null` (the field a `field: reason` API message names).

- [ ] **Step 1: Write the failing tests**

`ecommerce-frontend/src/pages/platform/onboarding.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { StoreTypeInfo } from "../../redux/api/platformAPI";
import { fieldOf, presetSummary, slugFromName } from "./onboarding";

const none = { shippingFee: null, freeShippingAbove: null, defaultTaxRate: null, maxCouponAmount: null,
  minimumAge: null, stuckOrderDays: null, orderPrefix: null };
const type = (features: Record<string, boolean>, commerce = {}, catalog = {}): StoreTypeInfo => ({
  key: "GROCERY", label: "Grocery", description: "",
  features,
  settings: {
    commerce: { ...none, ...commerce },
    catalog: { pageSize: null, lowStockThreshold: null, ...catalog },
    invoice: { gstin: null, state: null },
    regional: { timeZone: null },
  },
});

describe("onboarding", () => {
  it("makes a slug from the name", () => {
    expect(slugFromName("Acme Traders")).toBe("acme-traders");
    expect(slugFromName("  Café & Co.  ")).toBe("cafe-co");
    expect(slugFromName("x".repeat(50))).toHaveLength(40);
    expect(slugFromName("---")).toBe("");
  });

  it("summarises what a type pre-sets", () => {
    expect(presetSummary(type({ variants: false }, { shippingFee: 40, freeShippingAbove: 500, defaultTaxRate: 5, stuckOrderDays: 1 },
      { lowStockThreshold: 10 })))
      .toBe("Variants off · GST 5% · shipping ₹40, free above ₹500 · low stock at 10 · orders flagged after 1 day");
    expect(presetSummary(type({}))).toBe("Platform defaults");
  });

  it("finds the field an API error names", () => {
    expect(fieldOf("slug: use 1-40 lowercase letters, digits or dashes")).toBe("slug");
    expect(fieldOf("admin.password: 12 to 72 characters")).toBe("admin.password");
    expect(fieldOf("A tenant with this slug already exists")).toBeNull();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/pages/platform/onboarding.test.ts`
Expected: FAIL, `Failed to resolve import "./onboarding"`.

- [ ] **Step 3: Implement**

`ecommerce-frontend/src/pages/platform/onboarding.ts`:

```ts
import type { StoreTypeInfo } from "../../redux/api/platformAPI";

/** Same rule as the API: 1–40 lowercase letters, digits or dashes, not starting or ending with a dash. */
export const slugFromName = (name: string) =>
  name
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+/, "")
    .slice(0, 40)
    .replace(/-+$/, "");

/** "Variants on · GST 5% · low stock at 3" — what a type writes into the new store. */
export const presetSummary = ({ features, settings }: StoreTypeInfo) => {
  const c = settings.commerce;
  const parts: string[] = [];
  if (features.variants !== undefined) parts.push(`Variants ${features.variants ? "on" : "off"}`);
  if (c.defaultTaxRate !== null) parts.push(`GST ${c.defaultTaxRate}%`);
  if (c.shippingFee !== null) {
    parts.push(`shipping ₹${c.shippingFee}${c.freeShippingAbove !== null ? `, free above ₹${c.freeShippingAbove}` : ""}`);
  }
  if (settings.catalog.lowStockThreshold !== null) parts.push(`low stock at ${settings.catalog.lowStockThreshold}`);
  if (c.stuckOrderDays !== null) parts.push(`orders flagged after ${c.stuckOrderDays} day${c.stuckOrderDays === 1 ? "" : "s"}`);
  return parts.length ? parts.join(" · ") : "Platform defaults";
};

/** API validation messages start with the field: "slug: use 1-40 …". */
export const fieldOf = (message: string) => /^([a-zA-Z.]+):\s/.exec(message)?.[1] ?? null;
```

- [ ] **Step 4: Run the tests**

Run: `npx vitest run src/pages/platform/onboarding.test.ts`
Expected: PASS.

- [ ] **Step 5: The page**

Replace `ecommerce-frontend/src/pages/platform/OnboardStore.tsx`:

```tsx
import { FormEvent, useState } from "react";
import { IconType } from "react-icons";
import { LuCpu, LuShirt, LuShoppingBasket, LuStore } from "react-icons/lu";
import { useNavigate } from "react-router-dom";
import toast from "react-hot-toast";
import PageHeader from "../../components/admin/PageHeader";
import { errorMessage } from "../../redux/api/baseQuery";
import { StoreTypeKey, useCreateTenantMutation, useStoreTypesQuery } from "../../redux/api/platformAPI";
import { fieldOf, presetSummary, slugFromName } from "./onboarding";
import { PlatformShell } from "./PlatformSidebar";

const ICONS: Record<StoreTypeKey, IconType> = {
  GENERAL: LuStore, FASHION: LuShirt, GROCERY: LuShoppingBasket, ELECTRONICS: LuCpu,
};

/** One page: pick a type, fill in the basics, optionally the first admin. */
const OnboardStore = () => {
  const { data: types, isLoading: typesLoading } = useStoreTypesQuery();
  const [create, { isLoading }] = useCreateTenantMutation();
  const navigate = useNavigate();
  const [type, setType] = useState<StoreTypeKey>("GENERAL");
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const [domain, setDomain] = useState("");
  const [trial, setTrial] = useState(false);
  const [admin, setAdmin] = useState({ name: "", email: "", password: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});

  const chosen = types?.types.find((t) => t.key === type);
  const onName = (value: string) => {
    setName(value);
    if (!slugEdited) setSlug(slugFromName(value));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    const res = await create({
      slug: slug.trim(), name: name.trim(), domains: [domain.trim()], trial, type,
      admin: admin.email ? admin : undefined,
    });
    if ("error" in res) {
      const message = errorMessage(res.error);
      const field = fieldOf(message);
      if (field) setErrors({ [field.split(".")[0] === "domains" ? "domain" : field]: message });
      else toast.error(message);
      return;
    }
    toast.success(`${res.data.tenant.name} is ready`);
    navigate(`/platform/stores/${res.data.tenant.slug}`);
  };

  const err = (key: string) => errors[key] && <small className="field-error" role="alert">{errors[key]}</small>;

  return (
    <PlatformShell>
      <PageHeader title="Onboard store" description="Pick what kind of store it is; you can change everything later." />
      <form className="platform-scroll onboard" onSubmit={submit}>
        <fieldset className="card">
          <legend>Store type</legend>
          {typesLoading && <p>Loading…</p>}
          <div className="type-cards" role="radiogroup" aria-label="Store type">
            {types?.types.map((t) => {
              const Icon = ICONS[t.key];
              return (
                <label key={t.key} className={`type-card${type === t.key ? " type-card--active" : ""}`}>
                  <input type="radio" name="type" value={t.key} checked={type === t.key} onChange={() => setType(t.key)} />
                  <Icon aria-hidden className="type-card__icon" />
                  <strong>{t.label}</strong>
                  <span>{t.description}</span>
                </label>
              );
            })}
          </div>
          {chosen && <p className="onboard__summary">Sets: {presetSummary(chosen)}</p>}
        </fieldset>

        <fieldset className="card form-grid">
          <legend>Basics</legend>
          <label>
            Store name
            <input value={name} onChange={(e) => onName(e.target.value)} maxLength={80} required />
            {err("name")}
          </label>
          <label>
            Slug
            <input value={slug} onChange={(e) => { setSlug(e.target.value); setSlugEdited(true); }}
                   pattern="[a-z0-9]([a-z0-9\-]{0,38}[a-z0-9])?" title="lowercase letters, digits, dashes" required />
            {err("slug")}
          </label>
          <label className="span-2">
            Domain
            <input value={domain} onChange={(e) => setDomain(e.target.value)} placeholder="shop.example.com" required />
            {err("domain")}
          </label>
          <label className="span-2 platform-check">
            <input type="checkbox" checked={trial} onChange={(e) => setTrial(e.target.checked)} />
            Start as a trial (suspended when the trial ends unless activated)
          </label>
        </fieldset>

        <fieldset className="card form-grid">
          <legend>First admin (optional)</legend>
          <label>
            Name
            <input value={admin.name} onChange={(e) => setAdmin({ ...admin, name: e.target.value })} />
            {err("admin")}
          </label>
          <label>
            Email
            <input type="email" value={admin.email} onChange={(e) => setAdmin({ ...admin, email: e.target.value })} />
          </label>
          <label className="span-2">
            Password (12+ characters)
            <input type="password" value={admin.password} minLength={12} autoComplete="new-password"
                   onChange={(e) => setAdmin({ ...admin, password: e.target.value })} required={!!admin.email} />
          </label>
        </fieldset>

        <div className="onboard__actions">
          <button type="button" className="btn" onClick={() => navigate("/platform/stores")}>Cancel</button>
          <button className="btn btn--primary" disabled={isLoading}>{isLoading ? "Creating…" : "Create store"}</button>
        </div>
      </form>
    </PlatformShell>
  );
};

export default OnboardStore;
```

Check the icon names exist in the installed react-icons (`grep -oE "LuShirt|LuShoppingBasket|LuCpu" node_modules/react-icons/lu/index.d.ts | sort -u`); substitute close ones (`LuPackage`, `LuSmartphone`) if missing. Check `field-error` exists (`grep -rn "field-error" ecommerce-frontend/src/styles`); add it if not.

Append to `_platform.scss`:

```scss
.onboard {
  display: grid;
  gap: 1rem;
  align-content: start;

  fieldset {
    border: 1px solid var(--border);
  }

  &__summary {
    margin: 0.75rem 0 0;
    color: var(--text-muted);
  }

  &__actions {
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
    padding-bottom: 1rem;
  }
}

.type-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 0.75rem;
}

.type-card {
  position: relative;
  display: grid;
  gap: 0.35rem;
  padding: 1rem;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  cursor: pointer;

  input {
    position: absolute;
    opacity: 0;
  }

  &:focus-within {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }

  &__icon {
    font-size: 1.5rem;
    color: var(--accent);
  }

  span {
    color: var(--text-muted);
  }

  &--active {
    border-color: var(--accent);
    box-shadow: inset 0 0 0 1px var(--accent);
  }
}

.platform-check {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
```

- [ ] **Step 6: Check**

Run: `npx vitest run && npx tsc --noEmit -p . && npm run -s lint`
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(platform): onboard a store with a store type

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Store workspace (header, status moves, Overview)

**Files:**
- Create: `ecommerce-frontend/src/pages/platform/statusMoves.ts`, `statusMoves.test.ts`, `tabs/OverviewTab.tsx`
- Create: `ecommerce-frontend/src/pages/platform/workspaceForms.ts` (only `formatBytes` and `usagePercent` in this task; Task 9 adds the rest) and `workspaceForms.test.ts`
- Replace placeholder: `ecommerce-frontend/src/pages/platform/StoreWorkspace.tsx`

**Interfaces:**
- Consumes: `useTenantsQuery`, `useTenantQuery`, `useSetTenantStatusMutation`, `usePlatformAuditQuery`, `downloadTenantExport`, `TenantDetails` (Task 5); `findBySlug`, `typeLabel`, `statusNote` (Task 6); `Tabs`, `ConfirmDialog`, `DetailLayout` `sidebar` (Task 4).
- Produces: `type StatusAction = "activate" | "suspend" | "cancel" | "offboard" | "restore"`, `statusMoves(t: Pick<PlatformTenant, "slug" | "status">): StatusAction[]`, `MOVE_COPY: Record<StatusAction, { label: string; title: string; body: (name: string) => string; danger: boolean }>`.
- Produces: `formatBytes(n: number): string`, `usagePercent(used: number, limit: number): number` (0–100, rounded).
- Produces: `WORKSPACE_TABS: TabDef[]` with keys `overview | profile | features | payments | email | legal | limits`; each tab component receives `{ details: TenantDetails }`.

- [ ] **Step 1: Write the failing tests**

`ecommerce-frontend/src/pages/platform/statusMoves.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { statusMoves } from "./statusMoves";

describe("status moves", () => {
  it("offers what each status allows", () => {
    expect(statusMoves({ slug: "acme", status: "ACTIVE" })).toEqual(["suspend", "cancel", "offboard"]);
    expect(statusMoves({ slug: "acme", status: "TRIAL" })).toEqual(["activate", "suspend", "cancel", "offboard"]);
    expect(statusMoves({ slug: "acme", status: "SUSPENDED" })).toEqual(["activate", "cancel", "offboard"]);
    expect(statusMoves({ slug: "acme", status: "CANCELLED" })).toEqual(["activate", "offboard"]);
  });

  it("only restores a store scheduled for deletion, and nothing for a deleted one", () => {
    expect(statusMoves({ slug: "acme", status: "SCHEDULED_FOR_DELETION" })).toEqual(["restore"]);
    expect(statusMoves({ slug: "acme", status: "DELETED" })).toEqual([]);
  });

  it("never moves the default store", () => {
    expect(statusMoves({ slug: "default", status: "ACTIVE" })).toEqual([]);
  });
});
```

`ecommerce-frontend/src/pages/platform/workspaceForms.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { formatBytes, usagePercent } from "./workspaceForms";

describe("usage", () => {
  it("formats bytes in the largest sensible unit", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(2 * 1024 ** 3)).toBe("2 GB");
  });

  it("gives a capped whole percentage", () => {
    expect(usagePercent(1, 3)).toBe(33);
    expect(usagePercent(10, 5)).toBe(100);
    expect(usagePercent(1, 0)).toBe(0);
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/pages/platform/statusMoves.test.ts src/pages/platform/workspaceForms.test.ts`
Expected: FAIL (modules missing).

- [ ] **Step 3: Implement**

`ecommerce-frontend/src/pages/platform/statusMoves.ts`:

```ts
import type { PlatformTenant } from "../../redux/api/platformAPI";

export type StatusAction = "activate" | "suspend" | "cancel" | "offboard" | "restore";

/** Same rules as the API (TenantAdminService): the default store never moves; a scheduled deletion only restores. */
export const statusMoves = (t: Pick<PlatformTenant, "slug" | "status">): StatusAction[] => {
  if (t.status === "DELETED") return [];
  if (t.status === "SCHEDULED_FOR_DELETION") return ["restore"];
  if (t.slug === "default") return [];
  const moves: StatusAction[] = [];
  if (t.status !== "ACTIVE") moves.push("activate");
  if (t.status === "ACTIVE" || t.status === "TRIAL") moves.push("suspend");
  if (t.status !== "CANCELLED") moves.push("cancel");
  moves.push("offboard");
  return moves;
};

export const MOVE_COPY: Record<StatusAction, { label: string; title: string; body: (name: string) => string; danger: boolean }> = {
  activate: { label: "Activate", title: "Activate store", danger: false,
    body: (n) => `${n} opens to shoppers again. A trial becomes a normal store.` },
  suspend: { label: "Suspend", title: "Suspend store", danger: true,
    body: (n) => `${n}'s storefront stops working until you activate it again. Nothing is deleted.` },
  cancel: { label: "Cancel", title: "Cancel store", danger: true,
    body: (n) => `Use this when the owner leaves. ${n}'s storefront closes; you can activate or offboard it later.` },
  offboard: { label: "Offboard", title: "Offboard store", danger: true,
    body: (n) => `${n}'s storefront closes now, and all its data and photos are deleted after the grace period. Export it first if its data is needed.` },
  restore: { label: "Restore", title: "Restore store", danger: false,
    body: (n) => `Cancels the scheduled deletion. ${n} becomes active again.` },
};
```

`ecommerce-frontend/src/pages/platform/workspaceForms.ts` (start of the file):

```ts
const UNITS = ["B", "KB", "MB", "GB", "TB"];

export const formatBytes = (n: number) => {
  let value = n;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${Number(value.toFixed(1))} ${UNITS[unit]}`;
};

export const usagePercent = (used: number, limit: number) =>
  limit <= 0 ? 0 : Math.min(100, Math.round((used / limit) * 100));
```

- [ ] **Step 4: Run the tests**

Run: `npx vitest run src/pages/platform/statusMoves.test.ts src/pages/platform/workspaceForms.test.ts`
Expected: PASS.

- [ ] **Step 5: Overview tab**

`ecommerce-frontend/src/pages/platform/tabs/OverviewTab.tsx`:

```tsx
import { Link } from "react-router-dom";
import StatusBadge from "../../../components/admin/StatusBadge";
import { summariseChanges } from "../../../redux/api/auditAPI";
import { TenantDetails, usePlatformAuditQuery } from "../../../redux/api/platformAPI";
import { statusNote } from "../storeList";
import { formatBytes, usagePercent } from "../workspaceForms";

const Meter = ({ label, used, limit, format }: { label: string; used: number; limit: number; format: (n: number) => string }) => (
  <div className="meter">
    <div className="meter__head">
      <span>{label}</span>
      <span>{format(used)} of {format(limit)}</span>
    </div>
    <progress max={100} value={usagePercent(used, limit)} aria-label={`${label}: ${usagePercent(used, limit)}% used`} />
  </div>
);

/** Status, usage against limits, the owner's own rules (read-only) and the latest activity. */
const OverviewTab = ({ details }: { details: TenantDetails }) => {
  const { tenant, settings, usage, defaults } = details;
  const { data: audit } = usePlatformAuditQuery({ tenantId: tenant.id, page: 1 });
  const limits = {
    products: tenant.limits?.products ?? defaults.limits.products,
    uploadBytes: tenant.limits?.uploadBytes ?? defaults.limits.uploadBytes,
  };
  const c = settings.commerce;
  const rule = (v: number | string | null, unit = "") => (v === null ? "Platform default" : `${v}${unit}`);

  return (
    <div className="overview">
      <section className="card">
        <h3>Status</h3>
        <p><StatusBadge status={tenant.status} /> {statusNote(tenant)}</p>
        <p className="muted">Domains: {tenant.domains.join(", ") || "—"}</p>
      </section>

      <section className="card">
        <h3>Usage</h3>
        {usage ? (
          <>
            <Meter label="Products" used={usage.products} limit={limits.products} format={String} />
            <Meter label="Photo storage" used={usage.uploadBytes} limit={limits.uploadBytes} format={formatBytes} />
          </>
        ) : (
          <p className="muted">Usage unavailable.</p>
        )}
      </section>

      <section className="card">
        <h3>Owner&apos;s shop rules</h3>
        <dl className="overview__rules">
          <dt>Default GST</dt><dd>{rule(c.defaultTaxRate, "%")}</dd>
          <dt>Shipping</dt><dd>{rule(c.shippingFee)}{c.freeShippingAbove !== null ? `, free above ${c.freeShippingAbove}` : ""}</dd>
          <dt>Order prefix</dt><dd>{rule(c.orderPrefix)}</dd>
          <dt>Time zone</dt><dd>{rule(settings.regional.timeZone)}</dd>
        </dl>
        <p className="muted">Set by the store owner in Store settings.</p>
      </section>

      <section className="card overview__activity">
        <h3>Recent activity</h3>
        <ul>
          {(audit?.entries ?? []).slice(0, 10).map((e) => (
            <li key={e._id}>
              <time dateTime={e.at}>{new Date(e.at).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}</time>{" "}
              <strong>{e.action}</strong> <span className="muted">{summariseChanges(e.changes)}</span>
            </li>
          ))}
          {audit && audit.entries.length === 0 && <li className="muted">No activity yet.</li>}
        </ul>
        <Link to={`/platform/audit?tenant=${tenant.id}`}>View all</Link>
      </section>
    </div>
  );
};

export default OverviewTab;
```

- [ ] **Step 6: Workspace page**

Replace `ecommerce-frontend/src/pages/platform/StoreWorkspace.tsx`:

```tsx
import { lazy, Suspense, useState } from "react";
import toast from "react-hot-toast";
import { LuCreditCard, LuExternalLink, LuGauge, LuLayoutDashboard, LuMail, LuScale, LuStore, LuToggleRight } from "react-icons/lu";
import { Link, Navigate, useParams } from "react-router-dom";
import ConfirmDialog from "../../components/admin/ConfirmDialog";
import DetailLayout from "../../components/admin/DetailLayout";
import Tabs, { TabDef } from "../../components/admin/Tabs";
import { errorMessage } from "../../redux/api/baseQuery";
import { downloadTenantExport, useSetTenantStatusMutation, useTenantQuery, useTenantsQuery } from "../../redux/api/platformAPI";
import PlatformSidebar, { PlatformShell } from "./PlatformSidebar";
import { MOVE_COPY, StatusAction, statusMoves } from "./statusMoves";
import { findBySlug, typeLabel } from "./storeList";
import OverviewTab from "./tabs/OverviewTab";

const ProfileTab = lazy(() => import("./tabs/ProfileTab"));
const FeaturesTab = lazy(() => import("./tabs/FeaturesTab"));
const LimitsTab = lazy(() => import("./tabs/LimitsTab"));
const PaymentsTab = lazy(() => import("./tabs/SimpleTabs").then((m) => ({ default: m.PaymentsTab })));
const EmailTab = lazy(() => import("./tabs/SimpleTabs").then((m) => ({ default: m.EmailTab })));
const LegalTab = lazy(() => import("./tabs/SimpleTabs").then((m) => ({ default: m.LegalTab })));

export const WORKSPACE_TABS: TabDef[] = [
  { key: "overview", label: "Overview", Icon: LuLayoutDashboard },
  { key: "profile", label: "Profile", Icon: LuStore },
  { key: "features", label: "Features", Icon: LuToggleRight },
  { key: "payments", label: "Payments", Icon: LuCreditCard },
  { key: "email", label: "Email", Icon: LuMail },
  { key: "legal", label: "Legal", Icon: LuScale },
  { key: "limits", label: "Limits", Icon: LuGauge },
];

/** One store: header actions, status moves and linkable configuration tabs. */
const StoreWorkspace = () => {
  const { slug, tab = "overview" } = useParams();
  const { data: list, isLoading: listLoading } = useTenantsQuery();
  const summary = findBySlug(list?.tenants, slug);
  const { data: details, isLoading, isError, error } = useTenantQuery(summary?.id ?? "", { skip: !summary });
  const [setStatus, { isLoading: moving }] = useSetTenantStatusMutation();
  const [move, setMove] = useState<StatusAction | null>(null);

  if (!WORKSPACE_TABS.some((t) => t.key === tab)) return <Navigate to={`/platform/stores/${slug}`} replace />;
  if (listLoading || (summary && isLoading)) return <PlatformShell><p>Loading…</p></PlatformShell>;
  if (!summary || summary.status === "DELETED") {
    return (
      <PlatformShell>
        <div className="card"><h1>Store not found</h1><p><Link to="/platform/stores">Back to stores</Link></p></div>
      </PlatformShell>
    );
  }
  if (isError || !details) return <PlatformShell><p role="alert">{errorMessage(error)}</p></PlatformShell>;

  const t = details.tenant;
  const confirmMove = async () => {
    if (!move) return;
    const res = await setStatus({ id: t.id, action: move });
    if ("error" in res) toast.error(errorMessage(res.error));
    else toast.success(res.data.message);
    setMove(null);
  };
  const exportStore = async () => {
    try {
      await downloadTenantExport(t);
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  const panels: Record<string, JSX.Element> = {
    overview: <OverviewTab details={details} />,
    profile: <ProfileTab details={details} />,
    features: <FeaturesTab details={details} />,
    payments: <PaymentsTab details={details} />,
    email: <EmailTab details={details} />,
    legal: <LegalTab details={details} />,
    limits: <LimitsTab details={details} />,
  };

  return (
    <DetailLayout
      sidebar={<PlatformSidebar />}
      title={t.name}
      parent={{ to: "/platform/stores", label: "Stores" }}
      subtitle={<span>{t.slug} · <span className="badge">{typeLabel(t.type)}</span> · <span className={`badge badge--${t.status.toLowerCase()}`}>{t.status.toLowerCase().replace(/_/g, " ")}</span></span>}
      actions={
        <>
          {t.domains[0] && (
            <a className="btn" href={`https://${t.domains[0]}`} target="_blank" rel="noreferrer">
              <LuExternalLink aria-hidden /> Open storefront
            </a>
          )}
          <button className="btn" onClick={exportStore}>Export</button>
          {statusMoves(t).length > 0 && (
            <select className="btn" aria-label="Change status" value="" onChange={(e) => setMove(e.target.value as StatusAction)}>
              <option value="" disabled>Status…</option>
              {statusMoves(t).map((m) => <option key={m} value={m}>{MOVE_COPY[m].label}</option>)}
            </select>
          )}
        </>
      }
    >
      <Tabs tabs={WORKSPACE_TABS} active={tab} label="Store sections" hrefFor={(k) => `/platform/stores/${t.slug}${k === "overview" ? "" : `/${k}`}`} />
      <div role="tabpanel" aria-labelledby={`tab-${tab}`} className="platform-scroll">
        <Suspense fallback={<p>Loading…</p>}>{panels[tab]}</Suspense>
      </div>
      {move && (
        <ConfirmDialog
          open
          title={MOVE_COPY[move].title}
          body={<p>{MOVE_COPY[move].body(t.name)}</p>}
          confirmLabel={MOVE_COPY[move].label}
          danger={MOVE_COPY[move].danger}
          requireText={move === "offboard" ? t.slug : undefined}
          busy={moving}
          onConfirm={confirmMove}
          onCancel={() => setMove(null)}
        />
      )}
    </DetailLayout>
  );
};

export default StoreWorkspace;
```

Notes for this step:
- `PlatformSidebar.tsx` must also `export default PlatformSidebar;` (add it in this task).
- Verify each icon exists in the installed version (`grep -oE "LuGauge|LuScale|LuToggleRight|LuLayoutDashboard" node_modules/react-icons/lu/index.d.ts | sort -u`); substitute close ones if missing.
- Until Task 9, create `tabs/ProfileTab.tsx`, `tabs/FeaturesTab.tsx`, `tabs/LimitsTab.tsx` and `tabs/SimpleTabs.tsx` stubs exporting components that render `<p>Coming in the next task.</p>` and accept `{ details: TenantDetails }`, so the build passes.
- A status `<select>` keeps the header to one control on phones; it resets to "Status…" because its value is always `""`.

Append to `_platform.scss`:

```scss
.overview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(18rem, 1fr));
  gap: 1rem;
  align-content: start;

  h3 {
    margin-top: 0;
  }

  &__rules {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 0.35rem 1rem;

    dt {
      color: var(--text-muted);
    }

    dd {
      margin: 0;
    }
  }

  &__activity ul {
    list-style: none;
    padding: 0;
    display: grid;
    gap: 0.4rem;
  }
}

.meter {
  margin-bottom: 0.75rem;

  &__head {
    display: flex;
    justify-content: space-between;
    margin-bottom: 0.25rem;
  }

  progress {
    width: 100%;
    accent-color: var(--accent);
  }
}
```

- [ ] **Step 7: Check**

Run: `npx vitest run && npx tsc --noEmit -p . && npm run -s lint`
Expected: green.

- [ ] **Step 8: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(platform): store workspace with status moves and an overview

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Workspace configuration tabs (Profile, Features, Payments, Email, Legal, Limits)

**Files:**
- Modify: `ecommerce-frontend/src/pages/platform/workspaceForms.ts`, `workspaceForms.test.ts`
- Replace stubs: `ecommerce-frontend/src/pages/platform/tabs/ProfileTab.tsx`, `FeaturesTab.tsx`, `SimpleTabs.tsx`, `LimitsTab.tsx`
- Create: `ecommerce-frontend/src/pages/platform/tabs/useTabSave.ts`, `ecommerce-frontend/src/utils/useUnsavedGuard.ts`

**Interfaces:**
- Consumes: `TenantDetails`, `useUpdateTenantMutation` (Task 5), `fieldOf` (Task 7).
- Produces in `workspaceForms.ts`: `type FeatureChoice = "default" | "on" | "off"`, `featureChoice(features, key): FeatureChoice`, `featuresPayload(choices: Record<string, FeatureChoice>): Record<string, boolean>`, `type SizeUnit = "MB" | "GB"`, `bytesFromSize(value: string, unit: SizeUnit): number | null`, `sizeFromBytes(bytes: number | null | undefined): { value: string; unit: SizeUnit }`, `nullableInt(value: string): number | null`, `addDomain(domains: string[], raw: string): string[]`, `ACCENT = "--accent"`.
- Produces: `useTabSave(id: string)` → `{ save(body: Record<string, unknown>): Promise<boolean>; saving: boolean; error: string | null; fieldError: string | null }`.

- [ ] **Step 1: Write the failing tests**

Append to `ecommerce-frontend/src/pages/platform/workspaceForms.test.ts` (extend the import):

```ts
import { addDomain, bytesFromSize, featureChoice, featuresPayload, nullableInt, sizeFromBytes } from "./workspaceForms";

describe("features", () => {
  it("reads a missing key as the platform default", () => {
    expect(featureChoice({ variants: true }, "variants")).toBe("on");
    expect(featureChoice({ variants: false }, "variants")).toBe("off");
    expect(featureChoice({}, "marketing")).toBe("default");
  });

  it("sends only explicit choices", () => {
    expect(featuresPayload({ variants: "on", marketing: "default" })).toEqual({ variants: true });
    expect(featuresPayload({ variants: "default", marketing: "default" })).toEqual({});
  });
});

describe("limits", () => {
  it("converts MB and GB to bytes, empty meaning the platform default", () => {
    expect(bytesFromSize("1.5", "GB")).toBe(1610612736);
    expect(bytesFromSize("500", "MB")).toBe(524288000);
    expect(bytesFromSize("", "GB")).toBeNull();
    expect(bytesFromSize("  ", "MB")).toBeNull();
  });

  it("shows bytes back in the unit they were entered in", () => {
    expect(sizeFromBytes(1610612736)).toEqual({ value: "1.5", unit: "GB" });
    expect(sizeFromBytes(524288000)).toEqual({ value: "500", unit: "MB" });
    expect(sizeFromBytes(null)).toEqual({ value: "", unit: "GB" });
  });

  it("parses whole numbers, empty as null", () => {
    expect(nullableInt("250")).toBe(250);
    expect(nullableInt("")).toBeNull();
  });
});

describe("domains", () => {
  it("adds a trimmed lower-case domain once", () => {
    expect(addDomain(["a.test"], " B.Test ")).toEqual(["a.test", "b.test"]);
    expect(addDomain(["a.test"], "A.test")).toEqual(["a.test"]);
    expect(addDomain(["a.test"], "  ")).toEqual(["a.test"]);
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/pages/platform/workspaceForms.test.ts`
Expected: FAIL (`featureChoice` etc. not exported).

- [ ] **Step 3: Implement the mappers**

Append to `workspaceForms.ts`:

```ts
export type FeatureChoice = "default" | "on" | "off";

export const featureChoice = (features: Record<string, boolean> | undefined, key: string): FeatureChoice =>
  features?.[key] === undefined ? "default" : features[key] ? "on" : "off";

/** "default" leaves the key out, so the store follows the platform default. */
export const featuresPayload = (choices: Record<string, FeatureChoice>) =>
  Object.fromEntries(Object.entries(choices).filter(([, c]) => c !== "default").map(([k, c]) => [k, c === "on"]));

export type SizeUnit = "MB" | "GB";
const UNIT_BYTES: Record<SizeUnit, number> = { MB: 1024 ** 2, GB: 1024 ** 3 };

export const bytesFromSize = (value: string, unit: SizeUnit) =>
  value.trim() === "" ? null : Math.round(Number(value) * UNIT_BYTES[unit]);

/** GB when it is a tidy number of GB (one decimal), else MB. */
export const sizeFromBytes = (bytes: number | null | undefined): { value: string; unit: SizeUnit } => {
  if (bytes === null || bytes === undefined) return { value: "", unit: "GB" };
  const gb = bytes / UNIT_BYTES.GB;
  if (gb >= 1 && Number(gb.toFixed(1)) === gb) return { value: String(gb), unit: "GB" };
  return { value: String(Number((bytes / UNIT_BYTES.MB).toFixed(1))), unit: "MB" };
};

export const nullableInt = (value: string) => (value.trim() === "" ? null : Math.trunc(Number(value)));

export const addDomain = (domains: string[], raw: string) => {
  const host = raw.trim().toLowerCase();
  return host === "" || domains.includes(host) ? domains : [...domains, host];
};

export const ACCENT = "--accent";
```

- [ ] **Step 4: Run the tests**

Run: `npx vitest run src/pages/platform/workspaceForms.test.ts`
Expected: PASS.

- [ ] **Step 5: Shared save hook**

`ecommerce-frontend/src/pages/platform/tabs/useTabSave.ts`:

```ts
import { useState } from "react";
import toast from "react-hot-toast";
import { errorMessage } from "../../../redux/api/baseQuery";
import { useUpdateTenantMutation } from "../../../redux/api/platformAPI";
import { fieldOf } from "../onboarding";

/** One PUT with only this tab's group. A `field: reason` error is kept for the form; anything else is a toast. */
export const useTabSave = (id: string) => {
  const [update, { isLoading }] = useUpdateTenantMutation();
  const [error, setError] = useState<string | null>(null);

  const save = async (body: Record<string, unknown>) => {
    setError(null);
    const res = await update({ id, body });
    if ("error" in res) {
      const message = errorMessage(res.error);
      if (fieldOf(message)) setError(message);
      else toast.error(message);
      return false;
    }
    toast.success("Saved");
    return true;
  };

  return { save, saving: isLoading, error, fieldError: error ? fieldOf(error) : null };
};
```

- [ ] **Step 6: The tabs**

Each tab: local form state initialised from `details`, a dirty flag, Save and Discard buttons, a `beforeunload` guard while dirty, and the error message under the form. Shared footer pattern used by all tabs:

```tsx
<div className="tab-actions">
  {error && <p className="field-error" role="alert">{error}</p>}
  <button type="button" className="btn" disabled={!dirty || saving} onClick={reset}>Discard</button>
  <button type="submit" className="btn btn--primary" disabled={!dirty || saving}>{saving ? "Saving…" : "Save"}</button>
</div>
```

Add the guard as a tiny hook in `ecommerce-frontend/src/utils/useUnsavedGuard.ts` (Task 10 reuses it for Store settings):

```ts
import { useEffect } from "react";

/** Warns before closing or reloading the page while a tab has unsaved changes. */
export const useUnsavedGuard = (dirty: boolean) => {
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
};
```

`shortcut: in-app navigation away from a dirty tab is not blocked (BrowserRouter has no useBlocker); move to a data router to block it.` Put this line as a comment above `useUnsavedGuard`.

`tabs/FeaturesTab.tsx`:

```tsx
import { FormEvent, useMemo, useState } from "react";
import { TenantDetails } from "../../../redux/api/platformAPI";
import { FeatureChoice, featureChoice, featuresPayload } from "../workspaceForms";
import { useUnsavedGuard } from "../../../utils/useUnsavedGuard";
import { useTabSave } from "./useTabSave";

const FEATURES: { key: string; label: string; help: string }[] = [
  { key: "variants", label: "Product variants", help: "Products with sizes, colours or other options." },
  { key: "marketing", label: "Marketing", help: "Admin → Marketing: Google and Meta tags, product feed, server events." },
];

const FeaturesTab = ({ details }: { details: TenantDetails }) => {
  const { tenant, defaults } = details;
  const initial = useMemo(() => Object.fromEntries(FEATURES.map((f) => [f.key, featureChoice(tenant.features, f.key)])) as Record<string, FeatureChoice>, [tenant.features]);
  const [choices, setChoices] = useState(initial);
  const { save, saving, error } = useTabSave(tenant.id);
  const dirty = JSON.stringify(choices) !== JSON.stringify(initial);
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await save({ features: featuresPayload(choices) });
  };

  return (
    <form className="card tab-form" onSubmit={submit}>
      {FEATURES.map((f) => (
        <fieldset key={f.key} className="feature-row">
          <legend>{f.label}</legend>
          <p className="muted">{f.help}</p>
          <div className="segmented" role="radiogroup" aria-label={f.label}>
            {(["default", "on", "off"] as FeatureChoice[]).map((c) => (
              <label key={c} className={choices[f.key] === c ? "segmented__opt segmented__opt--active" : "segmented__opt"}>
                <input type="radio" name={f.key} checked={choices[f.key] === c} onChange={() => setChoices({ ...choices, [f.key]: c })} />
                {c === "default" ? `Platform default (${defaults.features[f.key] ? "on" : "off"})` : c === "on" ? "On" : "Off"}
              </label>
            ))}
          </div>
        </fieldset>
      ))}
      <div className="tab-actions">
        {error && <p className="field-error" role="alert">{error}</p>}
        <button type="button" className="btn" disabled={!dirty || saving} onClick={() => setChoices(initial)}>Discard</button>
        <button type="submit" className="btn btn--primary" disabled={!dirty || saving}>{saving ? "Saving…" : "Save"}</button>
      </div>
    </form>
  );
};

export default FeaturesTab;
```

When a new `Feature` is added to the backend enum, add a row to `FEATURES` (the backend rejects unknown keys, so a missing row only hides a switch).

`tabs/LimitsTab.tsx`:

```tsx
import { FormEvent, useMemo, useState } from "react";
import { TenantDetails } from "../../../redux/api/platformAPI";
import { bytesFromSize, formatBytes, nullableInt, SizeUnit, sizeFromBytes } from "../workspaceForms";
import { useUnsavedGuard } from "../../../utils/useUnsavedGuard";
import { useTabSave } from "./useTabSave";

const LimitsTab = ({ details }: { details: TenantDetails }) => {
  const { tenant, defaults, usage } = details;
  const initial = useMemo(() => {
    const size = sizeFromBytes(tenant.limits?.uploadBytes);
    return {
      products: tenant.limits?.products?.toString() ?? "",
      upload: size.value,
      unit: size.unit,
      rpm: tenant.limits?.requestsPerMinute?.toString() ?? "",
    };
  }, [tenant.limits]);
  const [form, setForm] = useState(initial);
  const { save, saving, error } = useTabSave(tenant.id);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await save({ limits: {
      products: nullableInt(form.products),
      uploadBytes: bytesFromSize(form.upload, form.unit),
      requestsPerMinute: nullableInt(form.rpm),
    } });
  };

  return (
    <form className="card form-grid tab-form" onSubmit={submit}>
      <p className="span-2 muted">Leave a field empty to use the platform default shown in it.</p>
      <label>
        Products
        <input type="number" min={1} step={1} value={form.products} onChange={(e) => setForm({ ...form, products: e.target.value })}
               placeholder={`Default: ${defaults.limits.products}`} />
        {usage && <small className="muted">In use: {usage.products}</small>}
      </label>
      <label>
        Photo storage
        <span className="input-with-unit">
          <input type="number" min={0.1} step={0.1} value={form.upload} onChange={(e) => setForm({ ...form, upload: e.target.value })}
                 placeholder={`Default: ${formatBytes(defaults.limits.uploadBytes)}`} />
          <select aria-label="Unit" value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value as SizeUnit })}>
            <option value="MB">MB</option>
            <option value="GB">GB</option>
          </select>
        </span>
        {usage && <small className="muted">In use: {formatBytes(usage.uploadBytes)}</small>}
      </label>
      <label>
        Requests per minute
        <input type="number" min={1} step={1} value={form.rpm} onChange={(e) => setForm({ ...form, rpm: e.target.value })}
               placeholder={`Default: ${defaults.limits.requestsPerMinute}`} />
      </label>
      <div className="span-2 tab-actions">
        {error && <p className="field-error" role="alert">{error}</p>}
        <button type="button" className="btn" disabled={!dirty || saving} onClick={() => setForm(initial)}>Discard</button>
        <button type="submit" className="btn btn--primary" disabled={!dirty || saving}>{saving ? "Saving…" : "Save"}</button>
      </div>
    </form>
  );
};

export default LimitsTab;
```

`tabs/SimpleTabs.tsx` (Payments, Email, Legal share one small field-form component):

```tsx
import { FormEvent, useMemo, useState } from "react";
import { TenantDetails } from "../../../redux/api/platformAPI";
import { useUnsavedGuard } from "../../../utils/useUnsavedGuard";
import { useTabSave } from "./useTabSave";

type Field = { key: string; label: string; hint?: string; maxLength?: number };

/** A group of optional text fields saved as one object (`payment`, `mail` or `legal`). Empty sends null. */
const GroupForm = ({ details, group, fields, note }: {
  details: TenantDetails; group: "payment" | "mail" | "legal"; fields: Field[]; note: string;
}) => {
  const values = (details.tenant[group] ?? {}) as Record<string, string | null>;
  const initial = useMemo(() => Object.fromEntries(fields.map((f) => [f.key, values[f.key] ?? ""])), [values, fields]);
  const [form, setForm] = useState<Record<string, string>>(initial);
  const { save, saving, error } = useTabSave(details.tenant.id);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await save({ [group]: Object.fromEntries(Object.entries(form).map(([k, v]) => [k, v.trim() === "" ? null : v.trim()])) });
  };

  return (
    <form className="card form-grid tab-form" onSubmit={submit}>
      <p className="span-2 muted">{note}</p>
      {fields.map((f) => (
        <label key={f.key} className="span-2">
          {f.label}
          <input value={form[f.key]} maxLength={f.maxLength ?? 200} onChange={(e) => setForm({ ...form, [f.key]: e.target.value })} />
          {f.hint && <small className="muted">{f.hint}</small>}
        </label>
      ))}
      <div className="span-2 tab-actions">
        {error && <p className="field-error" role="alert">{error}</p>}
        <button type="button" className="btn" disabled={!dirty || saving} onClick={() => setForm(initial)}>Discard</button>
        <button type="submit" className="btn btn--primary" disabled={!dirty || saving}>{saving ? "Saving…" : "Save"}</button>
      </div>
    </form>
  );
};

export const PaymentsTab = ({ details }: { details: TenantDetails }) => (
  <GroupForm details={details} group="payment" note="The store's own Razorpay account. The secret never comes here: it lives in the server environment as TENANT_SECRET_<reference>."
    fields={[
      { key: "provider", label: "Provider", hint: "razorpay (or fake in development)", maxLength: 20 },
      { key: "keyId", label: "Razorpay key id", maxLength: 64 },
      { key: "secretRef", label: "Secret reference", hint: "Letters, digits and _ only; the server reads TENANT_SECRET_<this>.", maxLength: 40 },
    ]} />
);

export const EmailTab = ({ details }: { details: TenantDetails }) => (
  <GroupForm details={details} group="mail" note="Links in verification and password-reset emails start with this address."
    fields={[{ key: "baseUrl", label: "Link base", hint: "e.g. https://shop.example.com" }]} />
);

export const LegalTab = ({ details }: { details: TenantDetails }) => (
  <GroupForm details={details} group="legal" note="Raising a version makes shoppers (Terms, Privacy) or store admins (admin agreement) accept again."
    fields={[
      { key: "termsVersion", label: "Terms version", maxLength: 32 },
      { key: "privacyVersion", label: "Privacy version", maxLength: 32 },
      { key: "adminAupVersion", label: "Admin agreement version", maxLength: 32 },
    ]} />
);
```

`MailSettings` has only `baseUrl` today; the spec's "sender details" is not a field yet, so the Email tab shows the link base only. Do not invent fields.

`tabs/ProfileTab.tsx`:

```tsx
import { FormEvent, useMemo, useState } from "react";
import { LuX } from "react-icons/lu";
import { StoreTypeKey, TenantDetails } from "../../../redux/api/platformAPI";
import { typeLabel } from "../storeList";
import { ACCENT, addDomain } from "../workspaceForms";
import { useUnsavedGuard } from "../../../utils/useUnsavedGuard";
import { useTabSave } from "./useTabSave";

const TYPES: StoreTypeKey[] = ["GENERAL", "FASHION", "GROCERY", "ELECTRONICS"];
const STOREFRONT: { key: string; label: string; type?: string }[] = [
  { key: "legalName", label: "Legal name" },
  { key: "address", label: "Registered address" },
  { key: "email", label: "Contact email", type: "email" },
  { key: "phone", label: "Contact phone", type: "tel" },
  { key: "grievanceOfficer", label: "Grievance officer" },
  { key: "grievanceEmail", label: "Grievance email", type: "email" },
  { key: "logoUrl", label: "Logo URL (https or uploads/ path)" },
];

const ProfileTab = ({ details }: { details: TenantDetails }) => {
  const t = details.tenant;
  const initial = useMemo(() => ({
    name: t.name,
    type: t.type,
    domains: t.domains,
    locale: t.locale ?? "",
    currency: t.currency ?? "",
    accent: t.theme[ACCENT] ?? "",
    otherTheme: Object.entries(t.theme).filter(([k]) => k !== ACCENT).map(([k, v]) => `${k}: ${v}`).join("\n"),
    storefront: Object.fromEntries(STOREFRONT.map((f) => [f.key, (t.storefront ?? {})[f.key] ?? ""])) as Record<string, string>,
  }), [t]);
  const [form, setForm] = useState(initial);
  const [newDomain, setNewDomain] = useState("");
  const { save, saving, error } = useTabSave(t.id);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  useUnsavedGuard(dirty);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const theme: Record<string, string> = {};
    for (const line of form.otherTheme.split("\n")) {
      const [k, ...v] = line.split(":");
      if (k.trim() && v.join(":").trim()) theme[k.trim()] = v.join(":").trim();
    }
    if (form.accent) theme[ACCENT] = form.accent;
    await save({
      name: form.name, type: form.type, domains: form.domains, locale: form.locale, currency: form.currency, theme,
      storefront: Object.fromEntries(Object.entries(form.storefront).map(([k, v]) => [k, v.trim() === "" ? null : v.trim()])),
    });
  };

  return (
    <form className="card form-grid tab-form" onSubmit={submit}>
      <label>
        Store name
        <input value={form.name} maxLength={80} required onChange={(e) => setForm({ ...form, name: e.target.value })} />
      </label>
      <label>
        Store type
        <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as StoreTypeKey })}>
          {TYPES.map((k) => <option key={k} value={k}>{typeLabel(k)}</option>)}
        </select>
        <small className="muted">A label only: changing it does not change any setting.</small>
      </label>

      <fieldset className="span-2">
        <legend>Domains</legend>
        <ul className="domain-chips">
          {form.domains.map((d) => (
            <li key={d}>
              {d}
              <button type="button" className="icon-btn" aria-label={`Remove ${d}`} disabled={form.domains.length === 1}
                      onClick={() => setForm({ ...form, domains: form.domains.filter((x) => x !== d) })}>
                <LuX aria-hidden />
              </button>
            </li>
          ))}
        </ul>
        <span className="input-with-unit">
          <input value={newDomain} placeholder="shop.example.com" aria-label="New domain" onChange={(e) => setNewDomain(e.target.value)} />
          <button type="button" className="btn" onClick={() => { setForm({ ...form, domains: addDomain(form.domains, newDomain) }); setNewDomain(""); }}>
            Add
          </button>
        </span>
        <small className="muted">A store needs at least one domain.</small>
      </fieldset>

      <label>
        Language (locale)
        <input value={form.locale} maxLength={16} placeholder="en-IN" onChange={(e) => setForm({ ...form, locale: e.target.value })} />
      </label>
      <label>
        Currency
        <input value={form.currency} maxLength={8} placeholder="INR" onChange={(e) => setForm({ ...form, currency: e.target.value.toUpperCase() })} />
      </label>
      <label>
        Accent colour
        <input type="color" value={form.accent || "#2f6fed"} onChange={(e) => setForm({ ...form, accent: e.target.value })} />
      </label>
      <details className="span-2">
        <summary>Advanced theme tokens</summary>
        <textarea rows={4} value={form.otherTheme} spellCheck={false} placeholder="--radius-md: 8px"
                  onChange={(e) => setForm({ ...form, otherTheme: e.target.value })} />
        <small className="muted">One token per line, as <code>--name: value</code>.</small>
      </details>

      <fieldset className="span-2 form-grid">
        <legend>Storefront and legal entity</legend>
        <p className="span-2 muted">The store owner can also edit these in Store settings.</p>
        {STOREFRONT.map((f) => (
          <label key={f.key} className={f.key === "address" || f.key === "logoUrl" ? "span-2" : undefined}>
            {f.label}
            <input type={f.type ?? "text"} maxLength={200} value={form.storefront[f.key]}
                   onChange={(e) => setForm({ ...form, storefront: { ...form.storefront, [f.key]: e.target.value } })} />
          </label>
        ))}
      </fieldset>

      <div className="span-2 tab-actions">
        {error && <p className="field-error" role="alert">{error}</p>}
        <button type="button" className="btn" disabled={!dirty || saving} onClick={() => setForm(initial)}>Discard</button>
        <button type="submit" className="btn btn--primary" disabled={!dirty || saving}>{saving ? "Saving…" : "Save"}</button>
      </div>
    </form>
  );
};

export default ProfileTab;
```

The colour picker's fallback `#2f6fed` only seeds the picker; nothing is saved unless the operator picks a colour (the `accent` value stays `""`).

Append to `_platform.scss`:

```scss
.tab-form {
  max-width: 48rem;
}

.tab-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  gap: 0.5rem;

  .field-error {
    margin-right: auto;
  }
}

.feature-row {
  border: 0;
  border-bottom: 1px solid var(--border);
  padding: 0 0 1rem;
  margin-bottom: 1rem;
}

.segmented {
  display: inline-flex;
  flex-wrap: wrap;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;

  &__opt {
    padding: 0.4rem 0.8rem;
    cursor: pointer;

    input {
      position: absolute;
      opacity: 0;
    }

    &:focus-within {
      outline: 2px solid var(--accent);
      outline-offset: -2px;
    }

    &--active {
      background: var(--accent);
      color: var(--accent-contrast, #fff);
    }
  }
}

.input-with-unit {
  display: flex;
  gap: 0.5rem;

  input {
    flex: 1;
    min-width: 0;
  }
}

.domain-chips {
  list-style: none;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin: 0 0 0.5rem;

  li {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.2rem 0.3rem 0.2rem 0.7rem;
    border: 1px solid var(--border);
    border-radius: 999px;
  }
}
```

- [ ] **Step 7: Check**

Run: `npx vitest run && npx tsc --noEmit -p . && npm run -s lint`
Expected: green.

- [ ] **Step 8: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(platform): profile, features, payments, email, legal and limits tabs replace the JSON editor

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Store settings in tabs

**Files:**
- Modify: `ecommerce-frontend/src/pages/admin/storeSettingsForm.ts`, `storeSettingsForm.test.ts`
- Modify: `ecommerce-frontend/src/pages/admin/settings.tsx`
- Modify: `ecommerce-frontend/src/redux/api/storeAPI.ts`
- Modify: `ecommerce-frontend/src/App.tsx`

**Interfaces:**
- Consumes: `Tabs`, `ConfirmDialog` (Task 4); `useUnsavedGuard` from `ecommerce-frontend/src/utils/useUnsavedGuard.ts` (Task 9); `typeLabel` from `ecommerce-frontend/src/utils/storeTypes.ts` (Task 6).
- Produces: `type SettingsTab = "shipping" | "orders" | "catalogue" | "invoice" | "region"`, `SETTINGS_TABS: { key: SettingsTab; label: string }[]`, `TAB_FIELDS: Record<SettingsTab, (keyof StoreSettingsForm)[]>`, `toTabPayload(form, tab): Partial<StoreSettings>`, `tabDirty(form, saved, tab): boolean`.
- `StoreSettingsResponse` gains `type: StoreTypeKey`; `saveStoreSettings` takes `Partial<StoreSettings>`.

- [ ] **Step 1: Write the failing tests**

Append to `storeSettingsForm.test.ts` (extend the import):

```ts
import { tabDirty, TAB_FIELDS, toTabPayload } from "./storeSettingsForm";

describe("settings tabs", () => {
  it("sends only the groups of the tab being saved", () => {
    const p = toTabPayload({ ...emptyForm, shippingFee: "49", legalName: "Acme" }, "shipping");
    expect(Object.keys(p)).toEqual(["commerce"]);
    expect(p.commerce?.shippingFee).toBe(49);
    expect(Object.keys(toTabPayload(emptyForm, "invoice")).sort()).toEqual(["invoice", "storefront"]);
    expect(Object.keys(toTabPayload(emptyForm, "region"))).toEqual(["regional"]);
  });

  it("knows which tab has unsaved changes", () => {
    const saved = { ...emptyForm, shippingFee: "49" };
    expect(tabDirty({ ...saved, shippingFee: "50" }, saved, "shipping")).toBe(true);
    expect(tabDirty({ ...saved, shippingFee: "50" }, saved, "orders")).toBe(false);
    expect(tabDirty({ ...saved, orderPrefix: "ACME" }, saved, "orders")).toBe(true);
  });

  it("puts every form field on exactly one tab", () => {
    const all = Object.values(TAB_FIELDS).flat();
    expect(all.sort()).toEqual(Object.keys(emptyForm).sort());
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/pages/admin/storeSettingsForm.test.ts`
Expected: FAIL (`toTabPayload` not exported).

- [ ] **Step 3: Implement**

Append to `storeSettingsForm.ts`:

```ts
export type SettingsTab = "shipping" | "orders" | "catalogue" | "invoice" | "region";

export const SETTINGS_TABS: { key: SettingsTab; label: string }[] = [
  { key: "shipping", label: "Shipping & tax" },
  { key: "orders", label: "Orders & coupons" },
  { key: "catalogue", label: "Catalogue" },
  { key: "invoice", label: "Invoice & contact" },
  { key: "region", label: "Region" },
];

export const TAB_FIELDS: Record<SettingsTab, (keyof StoreSettingsForm)[]> = {
  shipping: ["shippingFee", "freeShippingAbove", "defaultTaxRate"],
  orders: ["maxCouponAmount", "minimumAge", "stuckOrderDays", "orderPrefix"],
  catalogue: ["pageSize", "lowStockThreshold"],
  invoice: ["legalName", "address", "gstin", "state", "email", "phone", "grievanceOfficer", "grievanceEmail", "logoUrl"],
  region: ["timeZone"],
};

const TAB_GROUPS: Record<SettingsTab, (keyof StoreSettings)[]> = {
  shipping: ["commerce"],
  orders: ["commerce"],
  catalogue: ["catalog"],
  invoice: ["invoice", "storefront"],
  region: ["regional"],
};

/**
 * Only this tab's groups. Commerce spans two tabs, so it is sent whole from the form; the other tab can hold no
 * unsaved edits because switching tabs asks to save or discard first.
 */
export const toTabPayload = (f: StoreSettingsForm, tab: SettingsTab): Partial<StoreSettings> => {
  const all = toPayload(f);
  return Object.fromEntries(TAB_GROUPS[tab].map((g) => [g, all[g]])) as Partial<StoreSettings>;
};

export const tabDirty = (form: StoreSettingsForm, saved: StoreSettingsForm, tab: SettingsTab) =>
  TAB_FIELDS[tab].some((k) => form[k] !== saved[k]);
```

`storeAPI.ts`: change `saveStoreSettings: builder.mutation<StoreSettingsResponse, StoreSettings>` to `builder.mutation<StoreSettingsResponse, Partial<StoreSettings>>`, and add `type: StoreTypeKey;` to `StoreSettingsResponse` (`StoreTypeKey` is already declared in this file, Task 5).

- [ ] **Step 4: Run the tests**

Run: `npx vitest run src/pages/admin/storeSettingsForm.test.ts`
Expected: PASS.

- [ ] **Step 5: Rework the page into tabs**

`App.tsx`: change `<Route path="/admin/settings" element={<StoreSettings />} />` to `<Route path="/admin/settings/:tab?" element={<StoreSettings />} />`.

`settings.tsx` changes:
1. `const { tab: rawTab } = useParams(); const tab = (SETTINGS_TABS.some((t) => t.key === rawTab) ? rawTab : "shipping") as SettingsTab;`
2. `const saved = useMemo(() => (data ? fromSettings(data.settings) : emptyForm), [data]);` and `const dirty = tabDirty(form, saved, tab);` (replaces the JSON-compare `dirty`). `useUnsavedGuard(dirty);`
3. Submit sends `save(toTabPayload(form, tab))`; on success keep `loadTenantConfig()` and the toast.
4. Header: replace the `<header className="store-settings__head">` block with

```tsx
<header className="store-settings__head">
  <h1>Store settings</h1>
  <p>
    Your shop&apos;s own rules. Leave a field empty to use the platform default shown in it.
    {data && data.type !== "GENERAL" && <> {typeLabel(data.type)} store: starting values were set when the store was created.</>}
  </p>
</header>
```

(`typeLabel` from `../../utils/storeTypes`, Task 6.)
5. Under the header render the tabs with a leave check:

```tsx
const [pendingTab, setPendingTab] = useState<SettingsTab | null>(null);
const navigate = useNavigate();
// ...
<Tabs
  tabs={SETTINGS_TABS}
  active={tab}
  label="Store settings sections"
  hrefFor={(k) => `/admin/settings/${k}`}
  canLeave={(k) => {
    if (!dirty) return true;
    setPendingTab(k as SettingsTab); // stop the switch and ask first
    return false;
  }}
/>
```

Then:

```tsx
<ConfirmDialog
  open={pendingTab !== null}
  title="Discard unsaved changes?"
  body={<p>You changed this tab without saving. Discard the changes and switch tabs?</p>}
  confirmLabel="Discard changes"
  danger
  onCancel={() => setPendingTab(null)}
  onConfirm={() => {
    setForm(saved);
    navigate(`/admin/settings/${pendingTab}`);
    setPendingTab(null);
  }}
/>
```

6. Wrap the five `<section className="card form-grid">` blocks so only the active tab's section renders, inside `<div role="tabpanel" aria-labelledby={`tab-${tab}`}>`. Keep each section's fields and hints exactly as they are today; the "Coupons, sign-up and orders" section becomes the `orders` tab, the "Shipping and tax" section the `shipping` tab, etc. Drop each section's `<h4>` title (the tab names it).
7. Custom tag and reset: extend the `number` and `textField` helpers so that when `saved[key] !== ""` the label shows `<span className="tag">Custom</span>` and, under the input, `<button type="button" className="link-btn" onClick={() => setForm((f) => ({ ...f, [key]: "" }))}>Reset to default</button>`. Resetting only clears the field; Save sends `null`. For the Invoice tab fields there is no platform default to show, so they get the Custom tag but the button reads "Clear".
8. Keep the scroll container: the form keeps `className="store-settings__form"` (already `flex:1; min-height:0; overflow-y:auto` from the scroll fix); check with `grep -n "store-settings__form" -A6 ecommerce-frontend/src/styles/admin-styles/*.scss`.

Add to `_admin-ui.scss` if missing (`grep -n "\.tag\b\|link-btn" ecommerce-frontend/src/styles -r`):

```scss
.tag {
  display: inline-block;
  margin-left: 0.4rem;
  padding: 0 0.4rem;
  border-radius: 999px;
  font-size: 0.75em;
  background: var(--accent-soft, rgb(47 111 237 / 12%));
  color: var(--accent-text, var(--accent));
}

.link-btn {
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent-text, var(--accent));
  text-decoration: underline;
  cursor: pointer;
  justify-self: start;
}
```

- [ ] **Step 6: Check**

Run: `npx vitest run && npx tsc --noEmit -p . && npm run -s lint`
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(admin): Store settings in linkable tabs with custom tags and reset

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Docs and browser pass

**Files:**
- Modify: `docs/foundations/architecture.md` (ADR-030 and the tenant module row), `docs/foundations/contracts.md`, `docs/foundations/requirements.md`
- Modify: `docs/superpowers/specs/2026-11-05-multitenant-console-design.md` (§4.2 call budget, §4.1 response shape)
- Modify: `ecommerce-frontend/src/help/adminGuides.ts` (store-settings article)

- [ ] **Step 1: ADR-030**

In `docs/foundations/architecture.md`, add after ADR-029, in the same format as its neighbours:

```markdown
### ADR-030 Store types are presets applied once (2026-11-05)

**Context:** Stores of different kinds need different starting features and shop rules; hand-editing JSON per store was slow and error-prone.
**Decision:** `StoreType` (`GENERAL`, `FASHION`, `GROCERY`, `ELECTRONICS`) lives in code (`shared.tenant`). On create, its preset is written as the store's own features and `StoreSettings`; explicit request values win. Afterwards the type is a label: changing it never re-applies a preset. Null type reads as `GENERAL`. `marketing` is never part of a preset.
**Consequences:** Presets change only with a release and are reviewed like code. Existing stores need no migration. Owners see preset values as their own (Custom) and can reset them to the platform default. Editable types, plans and billing are out of scope.
```

Add `StoreType` and `TenantUsage` to the tenant module's row in the module table.

- [ ] **Step 2: Contracts, requirements, spec**

`contracts.md`:
- `POST /platform/tenants` and `PUT /platform/tenants/{id}`: request gains `type` (`GENERAL|FASHION|GROCERY|ELECTRONICS`, case-insensitive; unknown → 400 `type: unknown store type`); on create the preset is applied once; on update it is a label only. Every tenant view gains `type`.
- `GET /platform/tenants/{id}`: `{success, tenant, settings, usage:{products, uploadBytes}|null, defaults:{features:{…}, limits:{products, uploadBytes, requestsPerMinute}}}`.
- New row `GET /platform/store-types` (platform admin): `{success, types:[{key, label, description, features, settings}]}`.
- `GET/PUT /store/settings` response gains `type`; `PUT` may send any subset of groups (already true; restate).
- Call budgets: Store list 1; Onboard store 1 on first load (store types, cached an hour) and 1 per create; Store workspace 2 on Overview when opened from the list (store + recent audit), +1 on a deep link (the list, to resolve the slug), 1 per save or status move; Audit log 1; Store settings 1 on load, 1 per save.

`requirements.md`: add an FR line for the console and store types (one paragraph, same style as FR-marketing), marked `[x]` once Task 11's browser pass is done.

Spec §4.1/§4.2: change to the response shape above and the call budgets above (the onboarding page now makes 1 call; a deep link adds 1).

- [ ] **Step 3: Help centre**

In `adminGuides.ts`, the `store-settings` article: change step 1 to "Go to Store settings. The page has tabs: Shipping & tax, Orders & coupons, Catalogue, Invoice & contact and Region." Add a `p`: "A field you have set shows a Custom tag; Reset to default clears it so the platform default applies again. If your store was set up as a Fashion, Grocery or Electronics store, some fields start with values chosen for that kind of store." Keep the rest.

- [ ] **Step 4: Full test runs**

Run backend (outside the sandbox, background): `mvn -o verify > "$TMPDIR/verify.log" 2>&1; echo exit=$? >> "$TMPDIR/verify.log"` → expect `BUILD SUCCESS`.
Run frontend: `npx vitest run && npx tsc --noEmit -p . && npm run -s lint && npm run -s build` → expect green and a successful build.

- [ ] **Step 5: Browser pass**

Run the stack on an isolated database (set `MONGO_DB` as well as the port, see the local-run note in the project memory), sign in to `/platform` with the dev platform admin, and check at 1280px and 375px, light and dark, scrolling to the last control on every page:
- Store list: search by a second domain, each chip, sort, row click and Enter on a row. Count network calls on load: 1.
- Onboard store: choose Grocery, check the summary line, create with a first admin, land on Overview. Calls: 1 on load, 1 on create.
- Workspace from the list: Overview shows usage, owner rules, activity (2 calls). Every tab saves; a 400 (e.g. domain `bad host`) shows under the form. Status: Suspend → Activate; Offboard needs the slug typed; Restore.
- Deep link `/platform/stores/<slug>/limits` in a new tab works (+1 call); `/platform/stores/nope` shows "Store not found".
- Store settings as the new store's admin: Grocery note in the header, tabs, Custom tags on preset fields, Reset to default, switching tabs with unsaved edits asks first, reload with unsaved edits warns.
- The store admin sidebar and every other admin page look unchanged.

Fix what fails (with a test where the fix is logic), then report anything not checked.

- [ ] **Step 6: Commit**

```bash
git add docs ecommerce-frontend/src/help/adminGuides.ts
git commit -m "docs: ADR-030 store types, console contracts and help

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
