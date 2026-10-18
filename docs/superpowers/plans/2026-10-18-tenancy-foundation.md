# Multi-tenancy Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lay the tenancy foundation without changing what any current endpoint does: a `tenant` module with request-time tenant resolution, a tenant context, a JWT `tid` claim, a tenant-aware Mongo wrapper, an idempotent backfill mechanism, a build-time guard that can only shrink, and a two-tenant test harness. No collection is migrated yet.

**Architecture:** A servlet filter ahead of Spring Security maps the request host to a tenant (cached) and puts it in a `TenantContext`. Repositories will use a `TenantMongo` wrapper instead of `MongoTemplate`; it adds the tenant to every query and stamps it on every save. An ArchUnit test starts with an allowlist of today's repositories and fails when an allowlisted class stops using `MongoTemplate` or a new class starts, so each later migration must remove its entries. While only the `default` tenant exists every host resolves to it, so current deployments behave exactly as before.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.13 (ArchUnit 1.4.2 is already on the classpath), Spring Data MongoDB, Caffeine, JUnit 5 + AssertJ + MockMvc + Testcontainers `mongo:7.0`.

**Spec:** `docs/superpowers/specs/2026-10-18-multitenancy-design.md` (§4 request flow, §5 step 1 and 2, §8 review and assumptions). Plan 5. The per-entity migrations (spec §5 steps 3 to 5) get their own plans once product variants and the audit log are on `main`.

## Global Constraints

- **No behaviour change for existing endpoints.** Every existing test must pass unmodified (one exception: tests may gain nothing from this plan except the new harness). Existing hosts (`localhost`, `127.0.0.1`, anything else while only one tenant exists) resolve to the `default` tenant.
- Module boundary: `tenant` is a new Modulith module; everything other modules need (`TenantContext`, `TenantRef`, `TenantMongo`, `TenantOwned`, `BackfillTarget`, `TenantProperties`) lives in `com.sixpack.shared.tenant` (open module), so no module depends on `tenant`. `ModularityTests` must stay green.
- Tenancy is **fail-closed**: `TenantMongo` throws if there is no tenant in context. It never falls back to "all tenants".
- Do not touch the repositories, services or `IntegrationTest` base class that the product-variants, audit and admin-agreement sessions are editing. This plan adds files and edits only: `SecurityConfig` (one `permitAll` line), `TokenService`, `JwtConfig`, `application.yml`, `.env.example`, docs.
- A second real tenant must not be created before the identity migration (users are still global). Only tests create one.
- Backend tests: `cd backend && mvn -q -Dtest=<Class> test` (Docker running). Final gate: `mvn verify`.

## Review Focus

1. A request for tenant B must never be served with tenant A's context (and the context must be cleared after every request, including failures) (Tasks 1 and 4).
2. `TenantMongo` must make it impossible to read, update, delete or overwrite another tenant's document, including by reusing its id (Task 2).
3. A token issued for one tenant must be rejected on another tenant's host, and a pre-change token must keep working on the default tenant only (Task 5).
4. Unknown hosts: 404 once there are two tenants, default tenant while there is one; suspended tenant 403; health and root endpoints still work for any host (Tasks 3 and 4).
5. The guard test must fail for a new `MongoTemplate` user and for a stale allowlist entry (Task 6).

---

## File Structure

`backend/src/main/java/com/sixpack/`:

| File | Responsibility |
|---|---|
| `shared/tenant/TenantRef.java`, `TenantContext.java` (create) | Immutable reference to the current tenant and the thread-bound holder |
| `shared/tenant/TenantProperties.java` (create) | `app.tenant.*` settings |
| `shared/tenant/TenantOwned.java`, `BackfillTarget.java` (create) | Marker for tenant-owned documents; declares a collection to backfill |
| `shared/tenant/TenantMongo.java` (create) | Tenant-scoped data access |
| `tenant/domain/Tenant.java`, `TenantStatus.java`, `TenantRepository.java` (create) | Tenant aggregate and port |
| `tenant/infra/mongo/TenantDocument.java`, `MongoTenantRepository.java` (create) | `tenants` collection (global level) |
| `tenant/application/TenantRegistry.java`, `DefaultTenantBootstrap.java`, `TenantBackfill.java` (create) | Host lookup with cache, default tenant creation, backfill |
| `tenant/web/TenantResolutionFilter.java`, `TenantWebConfig.java`, `TenantConfigController.java` (create) | Request resolution and `GET /api/v1/tenant/config` |
| `shared/security/TenantClaimValidator.java` (create), `JwtConfig.java`, `TokenService.java`, `SecurityConfig.java` (modify) | `tid` claim |

Tests: `shared/tenant/TenantContextTest`, `it/TenantMongoIntegrationTest`, `it/TenantIntegrationTest` (harness), `it/TenantBootstrapIntegrationTest`, `it/TenantResolutionIntegrationTest`, `it/TenantTokenIntegrationTest`, `TenantIsolationArchitectureTest`.

---

### Task 1: Tenant context, properties and markers

**Files:**
- Create: `backend/src/main/java/com/sixpack/shared/tenant/TenantRef.java`, `TenantContext.java`, `TenantProperties.java`, `TenantOwned.java`, `BackfillTarget.java`
- Test: `backend/src/test/java/com/sixpack/shared/tenant/TenantContextTest.java`

**Interfaces:**
- Produces:
  - `record TenantRef(String id, String slug)`
  - `TenantContext`: `static Optional<TenantRef> current()`, `static TenantRef require()` (throws `IllegalStateException` "No tenant in context"), `static Scope open(TenantRef ref)` where `Scope` is an `AutoCloseable` (`close()` restores the previous value, never throws), `static <T> T callAs(TenantRef ref, Supplier<T> action)`, `static void runAs(TenantRef ref, Runnable action)`
  - `record TenantProperties(String defaultSlug, String defaultName, List<String> defaultDomains)` bound to `app.tenant`; defaults `default`, `6Pack`, `[localhost, 127.0.0.1]`; domains trimmed and lower-cased
  - `interface TenantOwned { String getTenantId(); void setTenantId(String tenantId); }`
  - `interface BackfillTarget { String collection(); }`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/shared/tenant/TenantContextTest.java`:

```java
package com.sixpack.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    private static final TenantRef A = new TenantRef("a-id", "a");
    private static final TenantRef B = new TenantRef("b-id", "b");

    @Test
    void emptyByDefaultAndRequireFailsClosed() {
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
    }

    @Test
    void scopesNestAndRestoreThePreviousTenant() {
        try (var outer = TenantContext.open(A)) {
            assertThat(TenantContext.require()).isEqualTo(A);
            try (var inner = TenantContext.open(B)) {
                assertThat(TenantContext.require()).isEqualTo(B);
            }
            assertThat(TenantContext.require()).isEqualTo(A);
        }
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void contextIsClearedEvenWhenTheActionFails() {
        assertThatThrownBy(() -> TenantContext.runAs(A, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void callAsReturnsTheActionsValueUnderTheGivenTenant() {
        assertThat(TenantContext.callAs(B, () -> TenantContext.require().slug())).isEqualTo("b");
    }

    @Test
    void contextDoesNotLeakAcrossThreads() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var scope = TenantContext.open(A)) {
                var other = CompletableFuture.supplyAsync(() -> TenantContext.current().isPresent(), executor).get();
                assertThat(other).isFalse();
                var asB = CompletableFuture.supplyAsync(
                        () -> TenantContext.callAs(B, () -> TenantContext.require().slug()), executor).get();
                assertThat(asB).isEqualTo("b");
                assertThat(TenantContext.require()).isEqualTo(A);
            }
        }
    }

    @Test
    void propertiesApplyDefaultsAndNormaliseDomains() {
        TenantProperties blank = new TenantProperties(null, " ", null);
        assertThat(blank.defaultSlug()).isEqualTo("default");
        assertThat(blank.defaultName()).isEqualTo("6Pack");
        assertThat(blank.defaultDomains()).containsExactly("localhost", "127.0.0.1");

        TenantProperties custom = new TenantProperties("main", "Main Store", List.of(" Shop.Example.COM ", "", "x.test"));
        assertThat(custom.defaultSlug()).isEqualTo("main");
        assertThat(custom.defaultDomains()).containsExactly("shop.example.com", "x.test");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=TenantContextTest test`
Expected: compilation FAIL (`TenantRef`, `TenantContext`, `TenantProperties` do not exist).

- [ ] **Step 3: Write minimal implementation**

`TenantRef.java`:

```java
package com.sixpack.shared.tenant;

/** The tenant a piece of work is running for. */
public record TenantRef(String id, String slug) {
}
```

`TenantContext.java`:

```java
package com.sixpack.shared.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The tenant the current thread is working for. Set by the request filter (or by {@link #runAs} for jobs and
 * tests) and always cleared afterwards. There is no ambient default: code that needs a tenant calls
 * {@link #require()} and fails if there is none.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantRef> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<TenantRef> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantRef require() {
        TenantRef ref = CURRENT.get();
        if (ref == null) {
            throw new IllegalStateException("No tenant in context");
        }
        return ref;
    }

    /** Binds {@code ref} to this thread until the returned scope is closed, then restores the previous value. */
    public static Scope open(TenantRef ref) {
        TenantRef previous = CURRENT.get();
        CURRENT.set(ref);
        return new Scope(previous);
    }

    public static <T> T callAs(TenantRef ref, Supplier<T> action) {
        try (Scope ignored = open(ref)) {
            return action.get();
        }
    }

    public static void runAs(TenantRef ref, Runnable action) {
        try (Scope ignored = open(ref)) {
            action.run();
        }
    }

    public static final class Scope implements AutoCloseable {
        private final TenantRef previous;

        private Scope(TenantRef previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
```

`TenantProperties.java`:

```java
package com.sixpack.shared.tenant;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultSlug    slug of the tenant that owns all data from before multi-tenancy
 * @param defaultName    display name used when that tenant is first created
 * @param defaultDomains hosts mapped to it when it is first created
 */
@ConfigurationProperties("app.tenant")
public record TenantProperties(String defaultSlug, String defaultName, List<String> defaultDomains) {

    public TenantProperties {
        defaultSlug = defaultSlug == null || defaultSlug.isBlank() ? "default" : defaultSlug.trim();
        defaultName = defaultName == null || defaultName.isBlank() ? "6Pack" : defaultName.trim();
        List<String> cleaned = defaultDomains == null ? List.of() : defaultDomains.stream()
                .map(d -> d == null ? "" : d.trim().toLowerCase()).filter(d -> !d.isEmpty()).toList();
        defaultDomains = cleaned.isEmpty() ? List.of("localhost", "127.0.0.1") : cleaned;
    }
}
```

`TenantOwned.java`:

```java
package com.sixpack.shared.tenant;

/** A stored document that belongs to exactly one tenant. {@link TenantMongo} stamps and checks the id. */
public interface TenantOwned {
    String getTenantId();

    void setTenantId(String tenantId);
}
```

`BackfillTarget.java`:

```java
package com.sixpack.shared.tenant;

/**
 * Declares a collection whose documents predate multi-tenancy. At startup every document in it without a
 * {@code tenantId} is assigned to the default tenant. Register one bean per migrated collection.
 */
public interface BackfillTarget {
    String collection();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=TenantContextTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(tenant): add tenant context, properties and ownership markers"
```

---

### Task 2: `TenantMongo`, the tenant-aware data access wrapper

**Files:**
- Create: `backend/src/main/java/com/sixpack/shared/tenant/TenantMongo.java`
- Test: `backend/src/test/java/com/sixpack/it/TenantMongoIntegrationTest.java` (create, with a test-only document)

**Interfaces:**
- Consumes: `TenantContext`, `TenantOwned`.
- Produces (all use `TenantContext.require()` and add `tenantId == current` to the query):
  - `<T> Optional<T> findById(String id, Class<T> type)`
  - `<T> Optional<T> findOne(Query query, Class<T> type)`; `<T> List<T> find(Query query, Class<T> type)`
  - `boolean exists(Query query, Class<?> type)`; `long count(Query query, Class<?> type)`
  - `<T> T save(T document)` — the document must implement `TenantOwned`; stamps the tenant when unset, throws `IllegalStateException` if it carries another tenant's id or if a document with the same `_id` already belongs to another tenant
  - `UpdateResult updateFirst(Query, UpdateDefinition, Class<?>)`, `updateMulti(...)`, `upsert(...)`; throws `IllegalArgumentException` if the update touches `tenantId`
  - `DeleteResult remove(Query query, Class<?> type)`
  - `<R> List<R> findDistinct(Query query, String field, Class<?> entity, Class<R> result)`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/TenantMongoIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.shared.tenant.TenantOwned;
import com.sixpack.shared.tenant.TenantRef;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class TenantMongoIntegrationTest extends IntegrationTest {

    @Document("tenant_probe")
    static class Probe implements TenantOwned {
        @Id
        String id;
        String tenantId;
        String name;
        String kind;

        Probe(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }

        @Override
        public String getTenantId() {
            return tenantId;
        }

        @Override
        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
    }

    private static final TenantRef A = new TenantRef("tenant-a", "a");
    private static final TenantRef B = new TenantRef("tenant-b", "b");

    @Autowired
    TenantMongo tenantMongo;

    @BeforeEach
    void cleanProbe() {
        mongo.getCollection("tenant_probe").deleteMany(new org.bson.Document());
    }

    private Probe saveAs(TenantRef tenant, String name, String kind) {
        return TenantContext.callAs(tenant, () -> tenantMongo.save(new Probe(name, kind)));
    }

    private static Query byName(String name) {
        return Query.query(Criteria.where("name").is(name));
    }

    @Test
    void failsClosedWithoutATenant() {
        assertThatThrownBy(() -> tenantMongo.find(new Query(), Probe.class)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tenantMongo.save(new Probe("x", "y"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void saveStampsTheCurrentTenantAndReadsAreScoped() {
        Probe a = saveAs(A, "shared-name", "x");
        Probe b = saveAs(B, "shared-name", "x");

        assertThat(a.getTenantId()).isEqualTo("tenant-a");
        assertThat(b.getTenantId()).isEqualTo("tenant-b");

        TenantContext.runAs(A, () -> {
            assertThat(tenantMongo.find(new Query(), Probe.class)).extracting(p -> p.id).containsExactly(a.id);
            assertThat(tenantMongo.findById(a.id, Probe.class)).isPresent();
            assertThat(tenantMongo.findById(b.id, Probe.class)).isEmpty();
            assertThat(tenantMongo.findOne(byName("shared-name"), Probe.class)).map(p -> p.id).contains(a.id);
            assertThat(tenantMongo.exists(byName("shared-name"), Probe.class)).isTrue();
            assertThat(tenantMongo.count(new Query(), Probe.class)).isEqualTo(1);
        });
        TenantContext.runAs(B, () -> assertThat(tenantMongo.findById(a.id, Probe.class)).isEmpty());
    }

    @Test
    void anotherTenantsDocumentCannotBeOverwrittenByReusingItsId() {
        Probe a = saveAs(A, "original", "x");

        Probe forged = new Probe("hijacked", "x");
        forged.id = a.id;
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> tenantMongo.save(forged)))
                .isInstanceOf(IllegalStateException.class);

        Probe wrongOwner = new Probe("wrong", "x");
        wrongOwner.setTenantId("tenant-a");
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> tenantMongo.save(wrongOwner)))
                .isInstanceOf(IllegalStateException.class);

        TenantContext.runAs(A, () -> assertThat(tenantMongo.findById(a.id, Probe.class))
                .map(p -> p.name).contains("original"));
    }

    @Test
    void ownersCanUpdateTheirOwnDocuments() {
        Probe a = saveAs(A, "mine", "x");
        TenantContext.runAs(A, () -> {
            a.name = "renamed";
            tenantMongo.save(a);
            assertThat(tenantMongo.findById(a.id, Probe.class)).map(p -> p.name).contains("renamed");
        });
    }

    @Test
    void updatesAndDeletesNeverReachAnotherTenant() {
        saveAs(A, "one", "old");
        saveAs(A, "two", "old");
        Probe b = saveAs(B, "one", "old");

        TenantContext.runAs(A, () -> {
            assertThat(tenantMongo.updateMulti(Query.query(Criteria.where("kind").is("old")),
                    new Update().set("kind", "new"), Probe.class).getModifiedCount()).isEqualTo(2);
            assertThat(tenantMongo.updateFirst(byName("one"), new Update().set("kind", "newer"), Probe.class)
                    .getModifiedCount()).isEqualTo(1);
            assertThat(tenantMongo.remove(byName("two"), Probe.class).getDeletedCount()).isEqualTo(1);
        });

        TenantContext.runAs(B, () -> {
            Probe untouched = tenantMongo.findById(b.id, Probe.class).orElseThrow();
            assertThat(untouched.kind).isEqualTo("old");
            assertThat(tenantMongo.remove(byName("one"), Probe.class).getDeletedCount()).isEqualTo(1);
        });
        TenantContext.runAs(A, () -> assertThat(tenantMongo.count(new Query(), Probe.class)).isEqualTo(1));
    }

    @Test
    void upsertCreatesTheDocumentInTheCurrentTenantOnly() {
        TenantContext.runAs(A, () -> tenantMongo.upsert(byName("counter"), new Update().inc("n", 1), Probe.class));
        TenantContext.runAs(A, () -> tenantMongo.upsert(byName("counter"), new Update().inc("n", 1), Probe.class));
        TenantContext.runAs(B, () -> tenantMongo.upsert(byName("counter"), new Update().inc("n", 5), Probe.class));

        assertThat(mongo.getCollection("tenant_probe").countDocuments()).isEqualTo(2);
        var docA = mongo.getCollection("tenant_probe").find(new org.bson.Document("tenantId", "tenant-a")).first();
        var docB = mongo.getCollection("tenant_probe").find(new org.bson.Document("tenantId", "tenant-b")).first();
        assertThat(docA.get("n")).isEqualTo(2);
        assertThat(docB.get("n")).isEqualTo(5);
    }

    @Test
    void distinctValuesAreScoped() {
        saveAs(A, "a1", "red");
        saveAs(A, "a2", "blue");
        saveAs(B, "b1", "green");

        TenantContext.runAs(A, () -> assertThat(tenantMongo.findDistinct(new Query(), "kind", Probe.class, String.class))
                .containsExactlyInAnyOrder("red", "blue"));
    }

    @Test
    void anUpdateCannotMoveADocumentToAnotherTenant() {
        saveAs(A, "mine", "x");
        TenantContext.runAs(A, () -> {
            assertThatThrownBy(() -> tenantMongo.updateFirst(byName("mine"), new Update().set("tenantId", "tenant-b"),
                    Probe.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tenantMongo.upsert(byName("mine"), new Update().setOnInsert("tenantId", "tenant-b"),
                    Probe.class)).isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(List.of(mongo.getCollection("tenant_probe").find().first().get("tenantId"))).containsExactly("tenant-a");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=TenantMongoIntegrationTest test`
Expected: compilation FAIL (`TenantMongo` does not exist).

- [ ] **Step 3: Write minimal implementation**

`TenantMongo.java`:

```java
package com.sixpack.shared.tenant;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Component;

/**
 * The only way tenant-owned data is read or written. Every query gets {@code tenantId == current tenant},
 * every saved document is stamped with it, and nothing can be moved to or overwritten from another tenant.
 * Fails closed: with no tenant in {@link TenantContext} it throws instead of widening the query.
 */
@Component
public class TenantMongo {

    static final String FIELD = "tenantId";

    private final MongoTemplate mongo;

    TenantMongo(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public <T> Optional<T> findById(String id, Class<T> type) {
        return findOne(Query.query(Criteria.where("id").is(id)), type);
    }

    public <T> Optional<T> findOne(Query query, Class<T> type) {
        return Optional.ofNullable(mongo.findOne(scoped(query), type));
    }

    public <T> List<T> find(Query query, Class<T> type) {
        return mongo.find(scoped(query), type);
    }

    public boolean exists(Query query, Class<?> type) {
        return mongo.exists(scoped(query), type);
    }

    public long count(Query query, Class<?> type) {
        return mongo.count(scoped(query), type);
    }

    public <T> T save(T document) {
        if (!(document instanceof TenantOwned owned)) {
            throw new IllegalArgumentException(document.getClass().getSimpleName() + " is not TenantOwned");
        }
        String tenantId = TenantContext.require().id();
        if (owned.getTenantId() == null) {
            owned.setTenantId(tenantId);
        } else if (!owned.getTenantId().equals(tenantId)) {
            throw new IllegalStateException("Document belongs to another tenant");
        }
        Object id = mongo.getConverter().getMappingContext().getRequiredPersistentEntity(document.getClass())
                .getIdentifierAccessor(document).getIdentifier();
        if (id != null && mongo.exists(Query.query(Criteria.where("id").is(id).and(FIELD).ne(tenantId)),
                document.getClass())) {
            throw new IllegalStateException("Document belongs to another tenant");
        }
        return mongo.save(document);
    }

    public UpdateResult updateFirst(Query query, UpdateDefinition update, Class<?> type) {
        rejectTenantChange(update);
        return mongo.updateFirst(scoped(query), update, type);
    }

    public UpdateResult updateMulti(Query query, UpdateDefinition update, Class<?> type) {
        rejectTenantChange(update);
        return mongo.updateMulti(scoped(query), update, type);
    }

    /** The tenant in the (scoped) query is copied onto a document the upsert creates. */
    public UpdateResult upsert(Query query, UpdateDefinition update, Class<?> type) {
        rejectTenantChange(update);
        return mongo.upsert(scoped(query), update, type);
    }

    public DeleteResult remove(Query query, Class<?> type) {
        return mongo.remove(scoped(query), type);
    }

    public <R> List<R> findDistinct(Query query, String field, Class<?> entity, Class<R> result) {
        return mongo.findDistinct(scoped(query), field, entity, result);
    }

    private static Query scoped(Query query) {
        return query.addCriteria(Criteria.where(FIELD).is(TenantContext.require().id()));
    }

    private static void rejectTenantChange(UpdateDefinition update) {
        for (Object operator : update.getUpdateObject().values()) {
            if (operator instanceof Document fields && fields.containsKey(FIELD)) {
                throw new IllegalArgumentException("An update must not change " + FIELD);
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=TenantMongoIntegrationTest,ModularityTests test`
Expected: PASS (8 tests plus the architecture test).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(tenant): TenantMongo, a fail-closed tenant-scoped data access wrapper"
```

---

### Task 3: The `tenant` module: tenants, host registry, default tenant and backfill

**Files:**
- Create: `backend/src/main/java/com/sixpack/tenant/domain/Tenant.java`, `TenantStatus.java`, `TenantRepository.java`
- Create: `backend/src/main/java/com/sixpack/tenant/infra/mongo/TenantDocument.java`, `MongoTenantRepository.java`
- Create: `backend/src/main/java/com/sixpack/tenant/application/TenantRegistry.java`, `DefaultTenantBootstrap.java`, `TenantBackfill.java`
- Modify: `backend/src/main/resources/application.yml`, `.env.example`
- Create (test support): `backend/src/test/java/com/sixpack/it/TenantIntegrationTest.java`
- Test: `backend/src/test/java/com/sixpack/it/TenantBootstrapIntegrationTest.java` (create)

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
  - `record Tenant(String id, String slug, String name, TenantStatus status, List<String> domains, Map<String,String> theme, String locale, String currency, Instant createdAt)`; `enum TenantStatus { ACTIVE, SUSPENDED }`
  - `TenantRepository`: `Optional<Tenant> findBySlug(String)`, `Optional<Tenant> findById(String)`, `Optional<Tenant> findByDomain(String)`, `List<Tenant> findAll()`, `Tenant save(Tenant)`
  - `TenantRegistry`: `Optional<Tenant> resolve(String host)` (exact host first, else the only tenant if exactly one exists, else empty; cached 60 s including misses), `Tenant byId(String id)` (throws if unknown), `void refresh()`
  - `DefaultTenantBootstrap` creates the default tenant once (never edits an existing one). `TenantBackfill.backfill() : long` assigns every document without `tenantId` in each `BackfillTarget` collection to the default tenant; runs at startup after the bootstrap and is safe to call again.
  - Test support `TenantIntegrationTest`: `createTenant(slug, name, domain)`, `host(String)` request post-processor, cleans up every non-default tenant after each test.

- [ ] **Step 1: Write the failing tests and the test harness**

Create `backend/src/test/java/com/sixpack/it/TenantIntegrationTest.java`:

```java
package com.sixpack.it;

import com.sixpack.tenant.application.TenantRegistry;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base for tests that need more than one tenant. Adds helpers in a subclass on purpose: the shared
 * {@link IntegrationTest} base is edited by several workstreams. The {@code default} tenant is created by the
 * application at startup and is never removed here.
 */
abstract class TenantIntegrationTest extends IntegrationTest {

    @Autowired
    protected TenantRepository tenantRepository;

    @Autowired
    protected TenantRegistry tenantRegistry;

    protected Tenant createTenant(String slug, String name, String domain) {
        Tenant tenant = tenantRepository.save(new Tenant(null, slug, name, TenantStatus.ACTIVE, List.of(domain),
                Map.of("--accent", "#123456"), "en-IN", "INR", null));
        tenantRegistry.refresh();
        return tenant;
    }

    protected Tenant suspend(Tenant tenant) {
        Tenant suspended = tenantRepository.save(new Tenant(tenant.id(), tenant.slug(), tenant.name(),
                TenantStatus.SUSPENDED, tenant.domains(), tenant.theme(), tenant.locale(), tenant.currency(),
                tenant.createdAt()));
        tenantRegistry.refresh();
        return suspended;
    }

    /** Makes the request look like it arrived on {@code host} (the port is ignored, as in production). */
    protected static RequestPostProcessor host(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }

    @AfterEach
    void removeExtraTenants() {
        mongo.getCollection("tenants").deleteMany(new Document("slug", new Document("$ne", "default")));
        tenantRegistry.refresh();
    }
}
```

Create `backend/src/test/java/com/sixpack/it/TenantBootstrapIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.shared.tenant.BackfillTarget;
import com.sixpack.tenant.application.DefaultTenantBootstrap;
import com.sixpack.tenant.application.TenantBackfill;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@Import(TenantBootstrapIntegrationTest.ProbeTargets.class)
class TenantBootstrapIntegrationTest extends TenantIntegrationTest {

    @TestConfiguration
    static class ProbeTargets {
        @Bean
        BackfillTarget probeTarget() {
            return () -> "backfill_probe";
        }
    }

    @Autowired
    DefaultTenantBootstrap bootstrap;

    @Autowired
    TenantBackfill backfill;

    @BeforeEach
    void cleanProbe() {
        mongo.getCollection("backfill_probe").deleteMany(new Document());
    }

    @Test
    void theDefaultTenantExistsOnceWithTheConfiguredDomains() {
        Tenant first = tenantRepository.findBySlug("default").orElseThrow();
        assertThat(first.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(first.name()).isEqualTo("6Pack");
        assertThat(first.domains()).containsExactly("localhost", "127.0.0.1");

        bootstrap.ensureDefault();
        bootstrap.ensureDefault();

        assertThat(tenantRepository.findAll().stream().filter(t -> t.slug().equals("default"))).hasSize(1);
        assertThat(tenantRepository.findBySlug("default").orElseThrow().id()).isEqualTo(first.id());
    }

    @Test
    void hostsResolveCaseInsensitivelyAndIgnoreThePort() {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        assertThat(tenantRegistry.resolve("LocalHost")).map(Tenant::id).contains(def.id());
        assertThat(tenantRegistry.resolve("localhost:8081")).map(Tenant::id).contains(def.id());
        assertThat(tenantRegistry.resolve("localhost.")).map(Tenant::id).contains(def.id());
    }

    @Test
    void whileOnlyTheDefaultTenantExistsEveryHostResolvesToIt() {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        assertThat(tenantRegistry.resolve("anything.example.org")).map(Tenant::id).contains(def.id());
    }

    @Test
    void aSecondTenantTurnsUnknownHostsIntoMisses() {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();

        assertThat(tenantRegistry.resolve("shop.acme.test")).map(Tenant::id).contains(acme.id());
        assertThat(tenantRegistry.resolve("localhost")).map(Tenant::id).contains(def.id());
        assertThat(tenantRegistry.resolve("anything.example.org")).isEmpty();
        assertThat(tenantRegistry.byId(acme.id()).name()).isEqualTo("Acme Shop");
    }

    @Test
    void backfillAssignsOnlyUnownedDocumentsToTheDefaultTenantAndIsRepeatable() {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        mongo.getCollection("backfill_probe").insertOne(new Document("name", "old-1"));
        mongo.getCollection("backfill_probe").insertOne(new Document("name", "old-2"));
        mongo.getCollection("backfill_probe").insertOne(new Document("name", "owned").append("tenantId", "someone-else"));

        assertThat(backfill.backfill()).isEqualTo(2);
        assertThat(backfill.backfill()).isZero();

        assertThat(mongo.getCollection("backfill_probe").countDocuments(new Document("tenantId", def.id()))).isEqualTo(2);
        assertThat(mongo.getCollection("backfill_probe").find(new Document("name", "owned")).first().get("tenantId"))
                .isEqualTo("someone-else");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=TenantBootstrapIntegrationTest test`
Expected: compilation FAIL (the `tenant` module does not exist).

- [ ] **Step 3: Write minimal implementation**

`tenant/domain/TenantStatus.java`:

```java
package com.sixpack.tenant.domain;

public enum TenantStatus {
    ACTIVE, SUSPENDED
}
```

`tenant/domain/Tenant.java`:

```java
package com.sixpack.tenant.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A store. {@code theme} maps CSS design-token names to values for the storefront (ADR-013).
 */
public record Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                     Map<String, String> theme, String locale, String currency, Instant createdAt) {

    public Tenant {
        status = status == null ? TenantStatus.ACTIVE : status;
        domains = domains == null ? List.of() : List.copyOf(domains);
        theme = theme == null ? Map.of() : Map.copyOf(theme);
    }
}
```

`tenant/domain/TenantRepository.java`:

```java
package com.sixpack.tenant.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for tenants. Tenants themselves are platform-global data. */
public interface TenantRepository {
    Optional<Tenant> findBySlug(String slug);

    Optional<Tenant> findById(String id);

    Optional<Tenant> findByDomain(String domain);

    List<Tenant> findAll();

    Tenant save(Tenant tenant);
}
```

`tenant/infra/mongo/TenantDocument.java`:

```java
package com.sixpack.tenant.infra.mongo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("tenants")
class TenantDocument {
    @Id
    String id;
    @Indexed(unique = true)
    String slug;
    String name;
    String status;
    /** Multikey unique index: a host belongs to at most one tenant. */
    @Indexed(unique = true, sparse = true)
    List<String> domains;
    Map<String, String> theme;
    String locale;
    String currency;
    @CreatedDate
    Instant createdAt;
}
```

`tenant/infra/mongo/MongoTenantRepository.java`:

```java
package com.sixpack.tenant.infra.mongo;

import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoTenantRepository implements TenantRepository {

    private final MongoTemplate mongo;

    MongoTenantRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return one(Query.query(Criteria.where("slug").is(slug)));
    }

    @Override
    public Optional<Tenant> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, TenantDocument.class)).map(MongoTenantRepository::toDomain);
    }

    @Override
    public Optional<Tenant> findByDomain(String domain) {
        return one(Query.query(Criteria.where("domains").is(domain)));
    }

    @Override
    public List<Tenant> findAll() {
        return mongo.findAll(TenantDocument.class).stream().map(MongoTenantRepository::toDomain).toList();
    }

    @Override
    public Tenant save(Tenant t) {
        TenantDocument doc = t.id() == null ? null : mongo.findById(t.id(), TenantDocument.class);
        if (doc == null) {
            doc = new TenantDocument();
        }
        doc.id = t.id();
        doc.slug = t.slug();
        doc.name = t.name();
        doc.status = t.status().name();
        doc.domains = t.domains();
        doc.theme = t.theme();
        doc.locale = t.locale();
        doc.currency = t.currency();
        return toDomain(mongo.save(doc));
    }

    private Optional<Tenant> one(Query query) {
        return Optional.ofNullable(mongo.findOne(query, TenantDocument.class)).map(MongoTenantRepository::toDomain);
    }

    private static Tenant toDomain(TenantDocument d) {
        return new Tenant(d.id, d.slug, d.name, d.status == null ? TenantStatus.ACTIVE : TenantStatus.valueOf(d.status),
                d.domains, d.theme, d.locale, d.currency, d.createdAt);
    }
}
```

`tenant/application/TenantRegistry.java`:

```java
package com.sixpack.tenant.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps a request host to its tenant. Results (including misses) are cached for a minute so junk hosts cannot
 * hammer the database; {@link #refresh()} clears the cache after tenants change.
 *
 * <p>While exactly one tenant exists, every host resolves to it, so a single-store deployment needs no
 * domain configuration. A second tenant switches the registry to strict, exact-host matching.
 */
@Component
public class TenantRegistry {

    private final TenantRepository tenants;
    private final Cache<String, Optional<Tenant>> byHost = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1_000).build();
    private final Cache<String, Tenant> byId = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1_000).build();

    TenantRegistry(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public Optional<Tenant> resolve(String host) {
        return byHost.get(normalise(host), this::lookup);
    }

    public Tenant byId(String id) {
        Tenant cached = byId.get(id, key -> tenants.findById(key).orElse(null));
        if (cached == null) {
            throw new IllegalStateException("Unknown tenant " + id);
        }
        return cached;
    }

    public void refresh() {
        byHost.invalidateAll();
        byId.invalidateAll();
    }

    private Optional<Tenant> lookup(String host) {
        Optional<Tenant> exact = tenants.findByDomain(host);
        if (exact.isPresent()) {
            return exact;
        }
        List<Tenant> all = tenants.findAll();
        return all.size() == 1 ? Optional.of(all.get(0)) : Optional.empty();
    }

    static String normalise(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) {
            h = h.substring(0, colon);
        }
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }
}
```

`tenant/application/DefaultTenantBootstrap.java`:

```java
package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Makes sure the tenant that owns all pre-tenancy data exists. Never edits or deletes an existing tenant. */
@Component
@Order(1)
public class DefaultTenantBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantBootstrap.class);

    private final TenantRepository tenants;
    private final TenantProperties props;
    private final TenantRegistry registry;

    DefaultTenantBootstrap(TenantRepository tenants, TenantProperties props, TenantRegistry registry) {
        this.tenants = tenants;
        this.props = props;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDefault();
    }

    public Tenant ensureDefault() {
        return tenants.findBySlug(props.defaultSlug()).orElseGet(() -> {
            Tenant created = tenants.save(new Tenant(null, props.defaultSlug(), props.defaultName(),
                    TenantStatus.ACTIVE, props.defaultDomains(), Map.of(), "en-IN", "INR", null));
            registry.refresh();
            log.info("Created default tenant '{}' for hosts {}", created.slug(), created.domains());
            return created;
        });
    }
}
```

`tenant/application/TenantBackfill.java`:

```java
package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.BackfillTarget;
import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Gives data that predates multi-tenancy to the default tenant. Only touches documents with no
 * {@code tenantId}, so it is safe to run on every start.
 */
@Component
@Order(2)
public class TenantBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantBackfill.class);

    private final MongoTemplate mongo;
    private final TenantRepository tenants;
    private final TenantProperties props;
    private final List<BackfillTarget> targets;

    TenantBackfill(MongoTemplate mongo, TenantRepository tenants, TenantProperties props,
                   List<BackfillTarget> targets) {
        this.mongo = mongo;
        this.tenants = tenants;
        this.props = props;
        this.targets = targets;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
    }

    /** @return how many documents were assigned */
    public long backfill() {
        if (targets.isEmpty()) {
            return 0;
        }
        Tenant owner = tenants.findBySlug(props.defaultSlug())
                .orElseThrow(() -> new IllegalStateException("Default tenant is missing"));
        long assigned = 0;
        for (BackfillTarget target : targets) {
            long modified = mongo.updateMulti(Query.query(Criteria.where("tenantId").exists(false)),
                    Update.update("tenantId", owner.id()), target.collection()).getModifiedCount();
            if (modified > 0) {
                log.info("Assigned {} document(s) in '{}' to tenant '{}'", modified, target.collection(), owner.slug());
            }
            assigned += modified;
        }
        return assigned;
    }
}
```

`application.yml`: under `app:` (next to `legal:`) add

```yaml
  tenant:
    default-slug: ${TENANT_DEFAULT_SLUG:default}
    default-name: ${TENANT_DEFAULT_NAME:6Pack}
    # Hosts that belong to the default tenant when it is first created. Not needed for a single-store setup
    # (with one tenant every host resolves to it).
    default-domains: ${TENANT_DEFAULT_DOMAINS:localhost,127.0.0.1}
```

`.env.example`: append

```
# --- tenancy (docs/superpowers/specs/2026-10-18-multitenancy-design.md) ---
# Only used the first time the app starts, to create the tenant that owns all existing data.
# With a single tenant every host resolves to it, so these can stay unset.
TENANT_DEFAULT_SLUG=default
TENANT_DEFAULT_NAME=6Pack
TENANT_DEFAULT_DOMAINS=localhost,127.0.0.1
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=TenantBootstrapIntegrationTest,CheckoutIntegrationTest,ModularityTests test`
Expected: PASS (new tests plus an existing integration test proving startup still works).

- [ ] **Step 5: Commit**

```bash
git add backend/src .env.example
git commit -m "feat(tenant): tenant module with host registry, default tenant bootstrap and backfill"
```

---

### Task 4: Resolve the tenant for every request, and expose the store config

**Files:**
- Create: `backend/src/main/java/com/sixpack/tenant/web/TenantResolutionFilter.java`, `TenantWebConfig.java`, `TenantConfigController.java`
- Modify: `backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java` (one `permitAll` line)
- Test: `backend/src/test/java/com/sixpack/it/TenantResolutionIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `TenantRegistry.resolve`, `TenantContext.open`.
- Produces: every request outside `/`, `/actuator/**`, `/api-docs/**`, `/swagger-ui/**`, `/error`, `/platform/**` runs inside a `TenantContext`; unknown host → 404 `{success:false, message:"Store not found"}`; suspended tenant → 403 `"This store is currently unavailable"`; the context is always cleared. `GET /api/v1/tenant/config` (public) returns `{success, tenant:{slug, name, theme, locale, currency}}`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/TenantResolutionIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import org.junit.jupiter.api.Test;

class TenantResolutionIntegrationTest extends TenantIntegrationTest {

    @Test
    void theStoreConfigDescribesTheTenantForTheRequestHost() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");

        mvc.perform(get("/api/v1/tenant/config").with(host("shop.acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("acme"))
                .andExpect(jsonPath("$.tenant.name").value("Acme Shop"))
                .andExpect(jsonPath("$.tenant.theme['--accent']").value("#123456"))
                .andExpect(jsonPath("$.tenant.currency").value("INR"))
                .andExpect(jsonPath("$.tenant.domains").doesNotExist());

        mvc.perform(get("/api/v1/tenant/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("default"));
    }

    @Test
    void aSingleTenantDeploymentAnswersOnAnyHost() throws Exception {
        mvc.perform(get("/api/v1/tenant/config").with(host("whatever.example.org")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("default"));
    }

    @Test
    void unknownHostsAreNotFoundOnceThereAreTwoTenants() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");

        mvc.perform(get("/api/v1/tenant/config").with(host("nope.example.org")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Store not found"));
        mvc.perform(get("/api/v1/product/latest").with(host("nope.example.org")))
                .andExpect(status().isNotFound());
    }

    @Test
    void suspendedTenantsAreUnavailable() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        suspend(acme);

        mvc.perform(get("/api/v1/product/latest").with(host("shop.acme.test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This store is currently unavailable"));
        mvc.perform(get("/api/v1/product/latest")).andExpect(status().isOk());
    }

    @Test
    void operationalEndpointsWorkForAnyHost() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");

        mvc.perform(get("/").with(host("nope.example.org"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").with(host("nope.example.org"))).andExpect(status().isOk());
    }

    @Test
    void theContextNeverOutlivesARequest() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");

        mvc.perform(get("/api/v1/tenant/config").with(host("shop.acme.test"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenant/config").with(host("nope.example.org"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tenant/config")).andExpect(status().isOk());

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void consecutiveRequestsForDifferentTenantsDoNotMix() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");
        createTenant("globex", "Globex", "shop.globex.test");

        for (int i = 0; i < 20; i++) {
            String slug = i % 2 == 0 ? "acme" : "globex";
            String host = i % 2 == 0 ? "shop.acme.test" : "shop.globex.test";
            mvc.perform(get("/api/v1/tenant/config").with(host(host)))
                    .andExpect(jsonPath("$.tenant.slug").value(slug));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=TenantResolutionIntegrationTest test`
Expected: FAIL (404 for `/api/v1/tenant/config`; unknown hosts are served).

- [ ] **Step 3: Write minimal implementation**

`tenant/web/TenantResolutionFilter.java`:

```java
package com.sixpack.tenant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.tenant.application.TenantRegistry;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs ahead of Spring Security. Resolves the request host to a tenant and binds it to the thread for the
 * rest of the request. The host is read after the forwarded-header filter has applied {@code X-Forwarded-Host}.
 */
class TenantResolutionFilter extends OncePerRequestFilter {

    private static final List<String> EXEMPT_PREFIXES =
            List.of("/actuator", "/api-docs", "/swagger-ui", "/error", "/platform");

    private final TenantRegistry registry;
    private final ObjectMapper mapper;

    TenantResolutionFilter(TenantRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/".equals(path) || EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Tenant> tenant = registry.resolve(request.getServerName());
        if (tenant.isEmpty()) {
            reject(response, HttpServletResponse.SC_NOT_FOUND, "Store not found");
            return;
        }
        if (tenant.get().status() == TenantStatus.SUSPENDED) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "This store is currently unavailable");
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.open(new TenantRef(tenant.get().id(), tenant.get().slug()))) {
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }
}
```

`tenant/web/TenantWebConfig.java`:

```java
package com.sixpack.tenant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.tenant.application.TenantRegistry;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TenantWebConfig {

    /** Just ahead of the Spring Security filter chain, and after the forwarded-header filter. */
    @Bean
    FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilter(TenantRegistry registry,
                                                                          ObjectMapper mapper) {
        FilterRegistrationBean<TenantResolutionFilter> registration =
                new FilterRegistrationBean<>(new TenantResolutionFilter(registry, mapper));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
```

`tenant/web/TenantConfigController.java`:

```java
package com.sixpack.tenant.web;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.tenant.application.TenantRegistry;
import com.sixpack.tenant.domain.Tenant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the storefront needs to render itself for the current domain. Public, and free of internal fields. */
@RestController
@RequestMapping("/api/v1/tenant")
class TenantConfigController {

    private final TenantRegistry registry;

    TenantConfigController(TenantRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/config")
    Map<String, Object> config() {
        Tenant tenant = registry.byId(TenantContext.require().id());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("slug", tenant.slug());
        view.put("name", tenant.name());
        view.put("theme", tenant.theme());
        view.put("locale", tenant.locale());
        view.put("currency", tenant.currency());
        return ApiResponse.of("tenant", view);
    }
}
```

`SecurityConfig.java`: directly after the line `.requestMatchers(GET, "/uploads/**").permitAll()` add

```java
                        .requestMatchers(GET, "/api/v1/tenant/config").permitAll()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=TenantResolutionIntegrationTest,AuthorizationIntegrationTest,CheckoutIntegrationTest,ModularityTests test`
Expected: PASS. If the actuator assertion fails because the test profile does not expose `/actuator/health`, assert `status().isNotFound()` is *not* returned by asking for `/` only, and note it in the ledger as a ruling.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(tenant): resolve the tenant per request and serve the store config"
```

---

### Task 5: A token is only valid for the tenant that issued it

**Files:**
- Create: `backend/src/main/java/com/sixpack/shared/security/TenantClaimValidator.java`
- Modify: `backend/src/main/java/com/sixpack/shared/security/TokenService.java`, `JwtConfig.java`
- Test: `backend/src/test/java/com/sixpack/it/TenantTokenIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `TenantContext`, `TenantProperties`.
- Produces: `TokenService.issue(userId, role)` adds a `tid` claim with the current tenant's id when one is in context. `TenantClaimValidator` rejects a token whose `tid` differs from the request's tenant; a token with no `tid` (issued before this change) is accepted only for the tenant whose slug equals `app.tenant.default-slug`; with no tenant in context (exempt paths) it does nothing.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/TenantTokenIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.shared.security.TokenService;
import com.sixpack.tenant.domain.Tenant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class TenantTokenIntegrationTest extends TenantIntegrationTest {

    @Autowired
    TokenService tokens;

    private JsonNode claims(String token) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    @Test
    void tokensCarryTheTenantTheyWereIssuedFor() throws Exception {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        String token = register("tid@test.dev");

        assertThat(claims(token).get("tid").asText()).isEqualTo(def.id());
    }

    @Test
    void aTokenIsRejectedOnAnotherTenantsHost() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        String token = register("cross@test.dev");

        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token)).with(host("shop.acme.test")))
                .andExpect(status().isUnauthorized());

        // A token minted on the second tenant works there and nowhere else.
        String acmeToken = body(mvc.perform(post("/api/v1/user/login").with(host("shop.acme.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "cross@test.dev", "password", "password123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
        assertThat(claims(acmeToken).get("tid").asText()).isEqualTo(acme.id());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(acmeToken)).with(host("shop.acme.test")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(acmeToken))).andExpect(status().isUnauthorized());
    }

    @Test
    void tokensIssuedBeforeTheChangeKeepWorkingOnTheDefaultTenantOnly() throws Exception {
        String userId = userId(register("legacy@test.dev"));
        // Issued outside any request, so it has no tid claim, like a token from before this change.
        String legacy = tokens.issue(userId, "USER");
        assertThat(claims(legacy).has("tid")).isFalse();

        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(legacy))).andExpect(status().isOk());

        createTenant("acme", "Acme Shop", "shop.acme.test");
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(legacy)).with(host("shop.acme.test")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(legacy))).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=TenantTokenIntegrationTest test`
Expected: FAIL (tokens have no `tid`; a token works on every host).

- [ ] **Step 3: Write minimal implementation**

`TenantClaimValidator.java`:

```java
package com.sixpack.shared.security;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.shared.tenant.TenantRef;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A token is valid only for the tenant it was issued for. Tokens from before tenancy carry no {@code tid} and
 * are honoured for the default tenant only, until they expire.
 */
class TenantClaimValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM = "tid";

    private static final OAuth2Error WRONG_TENANT =
            new OAuth2Error("invalid_token", "The token is not valid for this store", null);

    private final String defaultSlug;

    TenantClaimValidator(TenantProperties props) {
        this.defaultSlug = props.defaultSlug();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        TenantRef tenant = TenantContext.current().orElse(null);
        if (tenant == null) {
            return OAuth2TokenValidatorResult.success(); // paths that run without a tenant
        }
        String tid = jwt.getClaimAsString(CLAIM);
        boolean valid = tid == null ? tenant.slug().equals(defaultSlug) : tid.equals(tenant.id());
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(WRONG_TENANT);
    }
}
```

`TokenService.issue`: build the claims with the tenant when present:

```java
    public String issue(String userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(props.jwtTtl()))
                .claim(AuthUser.ROLE_CLAIM, role);
        TenantContext.current().ifPresent(t -> claims.claim(TenantClaimValidator.CLAIM, t.id()));
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
```

(import `com.sixpack.shared.tenant.TenantContext`.)

`JwtConfig.java`: take `TenantProperties` in the constructor and register the validator:

```java
    private final SecretKey key;
    private final TenantProperties tenantProps;

    JwtConfig(SecurityProperties props, TenantProperties tenantProps) {
        this.key = new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.tenantProps = tenantProps;
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), new TenantClaimValidator(tenantProps)));
        return decoder;
    }
```

(import `com.sixpack.shared.tenant.TenantProperties`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=TenantTokenIntegrationTest,AuthIntegrationTest,AuthorizationIntegrationTest,UserProfileIntegrationTest,UserStatusIntegrationTest test`
Expected: PASS (existing auth and user tests prove normal tokens still work on `localhost`).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(tenant): bind access tokens to the tenant that issued them"
```

---

### Task 6: The guard that can only shrink, and docs

**Files:**
- Test: `backend/src/test/java/com/sixpack/TenantIsolationArchitectureTest.java` (create)
- Modify: `docs/foundations/architecture.md`, `requirements.md`, `test-rollout.md`

**Interfaces:**
- Consumes: ArchUnit 1.4.2 (on the test classpath through Spring Modulith).
- Produces: a build-time rule: outside `com.sixpack.shared.tenant` and `com.sixpack.tenant`, no class may depend on `MongoTemplate`, except those listed in `NOT_YET_MIGRATED`; and every listed class must still depend on it.

- [ ] **Step 1: Write the test**

Create `backend/src/test/java/com/sixpack/TenantIsolationArchitectureTest.java`:

```java
package com.sixpack;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Tenant-owned data must only be reached through {@code TenantMongo}. This starts with today's repositories
 * allowlisted, and shrinks: migrating a repository to {@code TenantMongo} means deleting its line below. The
 * test also fails for a listed class that no longer uses {@code MongoTemplate}, so the list cannot go stale.
 */
class TenantIsolationArchitectureTest {

    /** Repositories still on raw MongoTemplate. Remove each as it moves to TenantMongo. */
    private static final Set<String> NOT_YET_MIGRATED = Set.of(
            "com.sixpack.product.infra.mongo.MongoProductRepository",
            "com.sixpack.product.infra.mongo.MongoCategoryRepository",
            "com.sixpack.order.infra.mongo.MongoOrderRepository",
            "com.sixpack.order.infra.mongo.MongoPaymentIntentRepository",
            "com.sixpack.coupon.infra.mongo.MongoCouponRepository",
            "com.sixpack.consent.infra.mongo.MongoConsentRepository",
            "com.sixpack.user.infra.mongo.MongoUserRepository",
            "com.sixpack.user.infra.mongo.MongoPasswordResetTokenRepository",
            "com.sixpack.dashboard.infra.mongo.MongoDashboardReadAdapter");

    private static Set<String> classesUsingMongoTemplate() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.sixpack").stream()
                .filter(c -> !c.getPackageName().startsWith("com.sixpack.shared.tenant"))
                .filter(c -> !c.getPackageName().startsWith("com.sixpack.tenant"))
                .filter(TenantIsolationArchitectureTest::usesMongoTemplate)
                .map(c -> c.getName().split("\\$")[0])
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean usesMongoTemplate(JavaClass c) {
        return c.getDirectDependenciesFromSelf().stream()
                .anyMatch(d -> d.getTargetClass().isAssignableTo(MongoTemplate.class));
    }

    @Test
    void noNewCodeTouchesMongoTemplateDirectly() {
        Set<String> offenders = new TreeSet<>(classesUsingMongoTemplate());
        offenders.removeAll(NOT_YET_MIGRATED);
        assertThat(offenders)
                .as("Use TenantMongo instead of MongoTemplate for tenant-owned data. Global data belongs in the "
                        + "tenant module.")
                .isEmpty();
    }

    @Test
    void theAllowlistOnlyContainsClassesThatStillNeedIt() {
        Set<String> stale = new TreeSet<>(NOT_YET_MIGRATED);
        stale.removeAll(classesUsingMongoTemplate());
        assertThat(stale)
                .as("These no longer use MongoTemplate: delete them from NOT_YET_MIGRATED")
                .isEmpty();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `cd backend && mvn -q -Dtest=TenantIsolationArchitectureTest test`
Expected: PASS. (If the allowlist is wrong the second test names the stale class; if a class not listed uses `MongoTemplate`, the first test names it. Fix the list from those messages.)

- [ ] **Step 3: Prove the guard bites**

Temporarily add `import org.springframework.data.mongodb.core.MongoTemplate;` and a field `private MongoTemplate probe;` to `RootController`, run the test, and confirm `noNewCodeTouchesMongoTemplateDirectly` FAILS naming `com.sixpack.shared.web.RootController`. Then temporarily delete the line for `MongoCouponRepository` from the allowlist, confirm the first test fails naming it, and restore both. Do not commit either probe.

- [ ] **Step 4: Docs**

- `architecture.md`: in the module table add a `tenant` row ("tenants, host resolution, default tenant, backfill; open helpers live in `shared.tenant`"). Add `ADR-020` dated 2026-10-18 above the newest entry: tenant resolved from the request host by a filter ahead of Spring Security; `TenantMongo` wrapper in the open `shared` module instead of a repository base class (repositories are plain `MongoTemplate` users, and a wrapper avoids a module cycle and is the single seam for a future per-tenant database); a guard test with a shrinking allowlist; single-tenant fallback (one tenant ⇒ every host resolves to it) so existing deployments need no configuration; JWT `tid` with a default-tenant-only grace for old tokens; users are global until the identity migration, so no second real tenant yet. Rejected: a Mongo-level interceptor that rewrites every query (invisible magic, and it would also catch global data), a database per tenant now (no dedicated-storage requirement), per-tenant signing keys (more key management for no stated need).
- `requirements.md`: add under non-functional requirements: "Tenancy: tenant-owned data is reachable only through `TenantMongo` (enforced by `TenantIsolationArchitectureTest`); a token is valid only for the tenant that issued it."
- `test-rollout.md`: list the new tests; rollout: no migration in this step. The `tenants` collection and the `default` tenant are created at startup; with one tenant every host resolves to it, so no configuration is required. Set `TENANT_DEFAULT_DOMAINS` before creating a second tenant.

- [ ] **Step 5: Full verification and commit**

```bash
cd backend && mvn verify
```

Expected: all green, including `ModularityTests`, `TenantIsolationArchitectureTest` and every pre-existing integration test.

```bash
git add backend/src docs
git commit -m "test(tenant): guard against raw MongoTemplate use outside the tenant wrapper; document ADR-020"
```

---

## Self-Review

- **Spec coverage (§5 steps 1 and 2):** tenant module, `TenantContext`, host resolution, `GET /tenant/config` → Tasks 1, 3, 4; JWT `tid` → Task 5; tenant-aware access, architecture test and the two-tenant leak harness → Tasks 2, 3 (`TenantIntegrationTest`), 6; backfill mechanism → Task 3; default tenant from today's config → Task 3. Deliberately not here: `PLATFORM_ADMIN` and the platform API (step 7), any collection migration (steps 3 to 5), per-tenant payment, legal and mail configuration (step 6), the SPA reading `/tenant/config` (step 7), production data migration (step 8).
- **Placeholder scan:** none. Task 4 step 4 names a fallback for the one assertion that depends on the test profile exposing `/actuator/health`.
- **Type consistency:** `TenantRef(id, slug)` is used by `TenantContext`, the filter, the validator and the tests. `Tenant` has 9 components, constructed with 9 arguments in `TenantIntegrationTest`, `DefaultTenantBootstrap` and `MongoTenantRepository`. `TenantRegistry.resolve/byId/refresh` match their uses. `TenantProperties.defaultSlug()` is used by the validator, bootstrap and backfill.
- **Review Focus coverage:** 1 → Task 1 `contextIsClearedEvenWhenTheActionFails`, `contextDoesNotLeakAcrossThreads`, Task 4 `theContextNeverOutlivesARequest` and `consecutiveRequestsForDifferentTenantsDoNotMix`; 2 → Task 2 `anotherTenantsDocumentCannotBeOverwrittenByReusingItsId`, `updatesAndDeletesNeverReachAnotherTenant`, `anUpdateCannotMoveADocumentToAnotherTenant`; 3 → Task 5; 4 → Task 3 `aSecondTenantTurnsUnknownHostsIntoMisses`, `whileOnlyTheDefaultTenantExistsEveryHostResolvesToIt`, Task 4 `unknownHostsAreNotFoundOnceThereAreTwoTenants`, `suspendedTenantsAreUnavailable`, `operationalEndpointsWorkForAnyHost`; 5 → Task 6 steps 2 and 3.
