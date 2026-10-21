# Analytics Sections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the shop Analytics page into seven tabs — Overview, Sales, Orders, Stock, Customers, Demand, Location — each backed by its own read endpoint with CSV export, plus a per-store "stuck order" setting and clean Indian state data.

**Architecture:** Each section is a vertical slice: a read port + Mongo adapter in `dashboard.infra.mongo`, pure calculations and a section record in `dashboard.domain`, a cached service in `dashboard.application`, one route in a new `AnalyticsController`, and one React tab component that only fetches when shown. Shared plumbing (query parsing, response window, CSV) is built once in Task 1.

**Tech Stack:** Java 21 / Spring Boot 3.5 / Spring Data MongoDB aggregations, Caffeine cache, JUnit 5 + MockMvc + Testcontainers; React 18 + TypeScript + RTK Query + Chart.js, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-20-analytics-sections-design.md`

## Global Constraints

- All work on branch `feat/analytics` in worktree `../6Pack-analytics` (rebased on `dev` 1e62573, which already has step 2's daily summary table, `DailySummaryJob` and ADR-024).
- Backend commands run from `backend/`: `mvn -q test -Dtest=<Class>` for one class, `mvn -q verify` for everything. Frontend commands run from `ecommerce-frontend/`: `npm test -- <file>`, `npm run lint`, `npm run build`.
- Every Mongo read goes through `TenantMongo` (it adds the tenant filter). Never inject `MongoTemplate` in main code.
- Every endpoint is under `/api/v1/dashboard/` and is admin-only like `/summary` (no security change needed: the existing `/api/v1/dashboard/**` rule covers it).
- Ranked lists: `limit` default **10**, max **50** (platform safety limit).
- Fixed analysis rules (constants, not settings): stock-cover window **30** days; trending and small-region minimum **3**; bought-together minimum **3** shared orders, top **10** pairs, orders with more than **20** distinct products skipped; order CSV cap **10 000** rows; order facts read cap **50 000**.
- Store setting added: `commerce.stuckOrderDays`, integer **1–60**, platform default **3**. Low-stock threshold reuses the existing product → store → platform (5) chain.
- No email, phone or street address in any CSV. Location CSV never contains 6-digit PIN codes.
- Money is shown with `formatMoney` (store currency), never a hard-coded ₹.
- Every admin view that can grow is its own scroll container (`.admin-main` hides overflow): `flex: 1; min-height: 0; overflow-y: auto`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A store with no orders, products or customers in the period** — every section returns zeros/empty lists and the UI shows empty states; no `NaN`, `Infinity` or division-by-zero (shares, AOV, rates, days left). Pinned by the `empty…` domain tests in Tasks 2, 3, 5, 6, 7, 8, 10.
2. **Legacy orders missing fields** (no `payment`, no `statusHistory`, no `shippingInfo`, `pinCode` null, items without `variantId`) — counted under "unknown"/"without history", never a 500. Pinned by the legacy-order IT cases in Tasks 3, 5, 10.
3. **Products deleted after they were ordered** — sales still show under the order's saved name, marked `removed`, category "uncategorised". Pinned in Tasks 3 and 8.
4. **A store whose time zone is not India** — weekday/hour buckets and "today" follow the store's zone. Pinned in Task 2 (`weekdayAndHourFollowTheStoreZone`).
5. **Stale numbers after a setting change** — changing `stuckOrderDays` or the low-stock threshold changes the next response (the setting is part of the cache key). Pinned in Tasks 5 and 6.

## Changes from the spec (decided while planning)

- **ADR number:** step 2 (merged into `dev` meanwhile) took ADR-024, so this work is **ADR-025**.
- **One read port per section** (`OverviewReads`, `SalesReads`, …) with its own small Mongo adapter, all sharing an extracted `OrderQueries` helper, instead of growing `DashboardReadPort` (already 66 lines) and its 315-line adapter. Same boundary, smaller files.
- **`orders` has no `{tenantId, createdAt}` index today** (the spec assumed one). Task 1 adds `{tenantId, createdAt}`, `{tenantId, status, createdAt}` and `{tenantId, user, createdAt}`. No `shippingInfo.state` index: grouping runs after the date match.
- **Overview/Sales CSV** are the series rows (`start, revenue, orders` [+ `previousRevenue`]); `Series.Point` has no units.
- **Sales breakdown** starts from `charged` (Σ order totals, what `/summary` calls gross) so that `net` equals Overview's net revenue.
- **Demand "stock cover"** is merged into the top-products rows (`stock`, `unitsPerDay`, `daysLeft`, `reorderSoon`) instead of a separate list.
- **Trending** ranks by the change in units (biggest rise / drop first), which avoids small bases dominating a % sort.
- **Checkout country offers only India today**, so the state field is always a dropdown; the free-text path stays for non-India countries.
- **The states reference endpoint** lives in `order.web` (`GET /api/v1/reference/indian-states`, public), next to the checkout it serves.
- **Roles are `admin` and `user` only** — "staff" in the spec means admin.

## File map

Backend (`backend/src/main/java/com/sixpack/…`):

| File | Responsibility | Task |
|---|---|---|
| `dashboard/application/StoreTime.java` | store zone + today for the new services (`SummaryService` keeps its own; step 2 just changed it) | 1 |
| `dashboard/domain/SectionQuery.java`, `Window.java`, `Section.java`, `Change.java` | shared query, response window, CSV contract, % helpers | 1 |
| `dashboard/web/SectionRequest.java`, `CsvWriter.java`, `AnalyticsController.java` | binding, CSV, routes | 1 (+ one route per section task) |
| `dashboard/infra/mongo/OrderQueries.java` | criteria / grouping helpers extracted from `MongoDashboardReadAdapter` | 1 |
| `dashboard/domain/{Overview,Sales,Orders,Stock,Customers,Demand,Location}Section.java` | section record + pure builders | 2,3,5,6,7,8,10 |
| `dashboard/domain/{Overview,Sales,Order,Stock,Customer,Demand,Location}Reads.java` | read port per section | same |
| `dashboard/infra/mongo/Mongo{…}Reads.java` | aggregations per section | same |
| `dashboard/application/{…}Service.java` | cached assembly per section | same |
| `dashboard/domain/Percentiles.java`, `StockCover.java`, `Trending.java`, `Buckets.java`, `RegionLevel.java` | pure maths | 5,6,8,7,10 |
| `shared/geo/IndianStates.java` | 36 states/UTs, aliases, `normalise` | 9 |
| `shared/tenant/StoreSettings.java`, `StoreConfig.java`, `tenant/application/StoreDefaults.java`, `StoreConfigService.java`, `StoreSettingsService.java` | `stuckOrderDays` | 4 |
| `order/web/OrderController.java`, `order/web/ReferenceController.java`, `order/domain/Invoice.java`, `shared/security/SecurityConfig.java` | state validation + reference list | 11 |
| `order/infra/mongo/OrderDocument.java` | new indexes | 1 |

Frontend (`ecommerce-frontend/src/…`):

| File | Responsibility | Task |
|---|---|---|
| `types/analytics.ts` | section response types + `SectionParams` | 2 (+ each section) |
| `components/admin/analytics/sections.ts` (+ `.test.ts`) | tab list, URL tab, CSV path, labels, heat level | 2 |
| `components/admin/analytics/Shared.tsx` | `Kpi`, `ChangeBadge`, `CsvButton`, `SectionState` | 2 |
| `components/admin/analytics/{Overview,Sales,Orders,Stock,Customers,Demand,Location}Tab.tsx` | one tab each | 2,3,5,6,7,8,10 |
| `pages/admin/analytics.tsx` | shell: filters + tabs | 2 |
| `redux/api/dashboardAPI.ts` | one query hook per section | each section |
| `styles/admin-styles/_analytics.scss` | tab body, heatmap, tables | 2 |
| `redux/api/referenceAPI.ts`, `utils/indianStates.ts` (+ test), `pages/shipping.tsx`, `pages/admin/settings.tsx` | state dropdowns | 11 |
| `redux/api/storeAPI.ts`, `pages/admin/storeSettingsForm.ts` (+ test), `pages/admin/settings.tsx` | stuck-order setting | 4 |

Tests (`backend/src/test/java/com/sixpack/…`): `dashboard/domain/*Test.java` (pure), `dashboard/web/CsvWriterTest.java`, `it/AnalyticsSeed.java` (shared seeding helper), `it/{Overview,Sales,Orders,Stock,Customers,Demand,Location}SectionIntegrationTest.java`, `it/OrderIndexesIntegrationTest.java`, `shared/geo/IndianStatesTest.java`, `it/IndianStateCheckoutIntegrationTest.java`.

Build order is the task order. Between Task 2 and the task that brings each tab, a few old charts are simply gone from the page — the branch is unreleased, so no interim placeholders.

---

### Task 1: Shared section plumbing

**Files:**
- Create: `backend/src/main/java/com/sixpack/dashboard/application/StoreTime.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/domain/SectionQuery.java`, `Window.java`, `Section.java`, `Change.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/web/SectionRequest.java`, `CsvWriter.java`, `AnalyticsController.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/OrderQueries.java`
- Modify: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoDashboardReadAdapter.java` (delegate helpers to `OrderQueries`)
- Modify: `backend/src/main/java/com/sixpack/order/infra/mongo/OrderDocument.java` (indexes)
- Modify: `backend/src/test/java/com/sixpack/it/IntegrationTest.java` (clear caches between tests)
- Test: `backend/src/test/java/com/sixpack/dashboard/domain/SectionQueryTest.java`, `backend/src/test/java/com/sixpack/dashboard/web/CsvWriterTest.java`, `backend/src/test/java/com/sixpack/it/OrderIndexesIntegrationTest.java`

**Interfaces:**
- Produces:
  - `StoreTime#zone(): ZoneId`, `StoreTime#today(): LocalDate`
  - `record SectionQuery(Scope scope, Period period, Granularity granularity, Compare compare, Basis basis, int limit)` with `comparePeriod(): Period` (null for NONE), `itemScoped(): boolean`, constants `DEFAULT_LIMIT = 10`, `MAX_LIMIT = 50`; granularity never null after construction.
  - `record Window(LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo, String timezone, String granularity)` with `static Window of(SectionQuery q, ZoneId zone)`.
  - `interface Section { Window window(); List<String> csvHeader(); List<List<Object>> csvRows(); }`
  - `Change.percent(double current, Double previous): Long` (null when previous is null), `Change.share(double part, double whole): double` (percent, one decimal, 0 when whole is 0), `Change.round2(double): double`.
  - `SectionRequest(LocalDate from, LocalDate to, String compare, String granularity, String basis, String category, Integer limit, String format)` with `toQuery(LocalDate today): SectionQuery`, `csv(): boolean`, `static <E extends Enum<E>> E parse(Class<E>, String, E fallback)`.
  - `CsvWriter.write(List<String> header, List<List<Object>> rows): String`
  - `AnalyticsController#respond(String name, SectionRequest req, Function<SectionQuery, ? extends Section> load): ResponseEntity<?>` (private; each section adds a route that calls it).
  - `OrderQueries` (package-private `@Component` in `dashboard.infra.mongo`): `mongo()`, `criteria(Dataset, Scope, Instant, Instant)`, `itemCriteria(Scope)`, `productIdsInCategory(String)`, `salesBy(Scope, Instant, Instant, Criteria extra, Object key): List<AggregationOperation>`; statics `ORDERS`, `PRODUCTS`, `USERS`, `localDay(String, ZoneId)`, `orZero(String)`, `stage(String, Object)`, `number(Object)`, `orders(Document)`, `id(String)`, `collection(Dataset)`, `hex(Object)`.

- [ ] **Step 1: Write the failing domain test for `SectionQuery` and `Window`**

`backend/src/test/java/com/sixpack/dashboard/domain/SectionQueryTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SectionQueryTest {

    private static final Scope ALL = new Scope(null, null, null);
    private static final Period OCT = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

    @Test
    void picksAGranularityFromTheLengthWhenNoneIsGiven() {
        SectionQuery q = new SectionQuery(ALL, OCT, null, Compare.PREVIOUS, Basis.NET, 10);
        assertThat(q.granularity()).isEqualTo(Granularity.DAY);
    }

    @Test
    void limitMustBeBetweenOneAndFifty() {
        assertThatThrownBy(() -> new SectionQuery(ALL, OCT, null, Compare.NONE, Basis.NET, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("limit: must be between 1 and 50");
        assertThatThrownBy(() -> new SectionQuery(ALL, OCT, null, Compare.NONE, Basis.NET, 51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooManyPointsFailsBeforeAnyQuery() {
        Period twoYears = Period.of(LocalDate.of(2024, 11, 1), LocalDate.of(2026, 10, 31));
        assertThatThrownBy(() -> new SectionQuery(ALL, twoYears, Granularity.DAY, Compare.NONE, Basis.NET, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Too many points");
    }

    @Test
    void itemScopedWhenNarrowedToAProductOrCategory() {
        assertThat(new SectionQuery(ALL, OCT, null, Compare.NONE, Basis.NET, 10).itemScoped()).isFalse();
        assertThat(new SectionQuery(new Scope(null, "shoes", null), OCT, null, Compare.NONE, Basis.NET, 10)
                .itemScoped()).isTrue();
    }

    @Test
    void windowCarriesTheComparisonPeriodAndZone() {
        Window w = Window.of(new SectionQuery(ALL, OCT, null, Compare.PREVIOUS, Basis.NET, 10),
                ZoneId.of("Asia/Kolkata"));
        assertThat(w.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(w.compareFrom()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(w.compareTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(w.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(w.granularity()).isEqualTo("day");
    }

    @Test
    void windowWithoutComparison() {
        Window w = Window.of(new SectionQuery(ALL, OCT, null, Compare.NONE, Basis.NET, 10), ZoneId.of("UTC"));
        assertThat(w.compareFrom()).isNull();
        assertThat(w.compareTo()).isNull();
    }

    @Test
    void changeIsNullWithoutAComparisonAndShareNeverDividesByZero() {
        assertThat(Change.percent(150, null)).isNull();
        assertThat(Change.percent(150, 100.0)).isEqualTo(50L);
        assertThat(Change.share(1, 3)).isEqualTo(33.3);
        assertThat(Change.share(5, 0)).isZero();
        assertThat(Change.round2(1.0 / 3)).isEqualTo(0.33);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=SectionQueryTest`
Expected: compilation failure — `SectionQuery`, `Window`, `Change` do not exist.

- [ ] **Step 3: Write `SectionQuery`, `Window`, `Section`, `Change`**

`backend/src/main/java/com/sixpack/dashboard/domain/SectionQuery.java`:

```java
package com.sixpack.dashboard.domain;

/**
 * What every analytics section is asked for: a scope, a period of store-local days, how to bucket it, what to
 * compare with, which revenue basis, and how long ranked lists may be.
 *
 * @param granularity null picks one from the length of the period
 */
public record SectionQuery(Scope scope, Period period, Granularity granularity, Compare compare, Basis basis,
                           int limit) {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;

    public SectionQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit: must be between 1 and " + MAX_LIMIT);
        }
        granularity = granularity == null ? Granularity.auto(period) : granularity;
        granularity.buckets(period); // fails fast on too many points, before any query runs
    }

    /** @return the period to compare with, or null when the comparison is off */
    public Period comparePeriod() {
        return compare.apply(period);
    }

    /** Narrowed to a product or category: only the matching order items count. */
    public boolean itemScoped() {
        return scope.productId() != null || scope.category() != null;
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/Window.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/** The days a section covers, the days it is compared with (null when off), the zone and the bucket size. */
public record Window(LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo, String timezone,
                     String granularity) {

    public static Window of(SectionQuery q, ZoneId zone) {
        Period other = q.comparePeriod();
        return new Window(q.period().from(), q.period().to(), other == null ? null : other.from(),
                other == null ? null : other.to(), zone.getId(), q.granularity().name().toLowerCase(Locale.ROOT));
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/Section.java`:

```java
package com.sixpack.dashboard.domain;

import java.util.List;

/** One analytics tab's answer. Its main table can also be exported as CSV. */
public interface Section {

    Window window();

    List<String> csvHeader();

    /** Cells are numbers, strings, dates/instants or null; {@code CsvWriter} formats them. */
    List<List<Object>> csvRows();
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/Change.java`:

```java
package com.sixpack.dashboard.domain;

/** Change and share figures shared by the sections. */
public final class Change {

    private Change() {
    }

    /** Whole-number percent change, or null when there is nothing to compare with. */
    public static Long percent(double current, Double previous) {
        return previous == null ? null : MonthBuckets.percentChange(current, previous);
    }

    /** {@code part} as a percent of {@code whole}, one decimal; 0 when the whole is 0. */
    public static double share(double part, double whole) {
        return whole == 0 ? 0 : Math.round(part / whole * 1000) / 10.0;
    }

    public static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=SectionQueryTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Write the failing `CsvWriter` test**

`backend/src/test/java/com/sixpack/dashboard/web/CsvWriterTest.java`:

```java
package com.sixpack.dashboard.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void writesAHeaderThenOneLinePerRow() {
        String csv = CsvWriter.write(List.of("start", "revenue"),
                List.of(List.of(LocalDate.of(2026, 10, 1), 1500.0), List.of(LocalDate.of(2026, 10, 2), 0.5)));
        assertThat(csv).isEqualTo("start,revenue\r\n2026-10-01,1500\r\n2026-10-02,0.5\r\n");
    }

    @Test
    void quotesCommasQuotesAndLineBreaks() {
        String csv = CsvWriter.write(List.of("name"), List.of(List.of("Shoe, \"red\""), List.of("two\nlines")));
        assertThat(csv).isEqualTo("name\r\n\"Shoe, \"\"red\"\"\"\r\n\"two\nlines\"\r\n");
    }

    @Test
    void neutralisesSpreadsheetFormulasButNotNegativeNumbers() {
        String csv = CsvWriter.write(List.of("a", "b", "c", "d", "e"),
                List.of(List.of("=SUM(A1)", "+1", "@cmd", "-x", -5)));
        assertThat(csv).isEqualTo("a,b,c,d,e\r\n'=SUM(A1),'+1,'@cmd,'-x,-5\r\n");
    }

    @Test
    void nullsAreEmptyAndInstantsAreIso() {
        String csv = CsvWriter.write(List.of("a", "b"),
                List.of(Arrays.asList(null, Instant.parse("2026-10-05T10:00:00Z"))));
        assertThat(csv).isEqualTo("a,b\r\n,2026-10-05T10:00:00Z\r\n");
    }

    @Test
    void wholeNumbersHaveNoTrailingZeros() {
        assertThat(CsvWriter.write(List.of("n"), List.of(List.of(3L), List.of(2.50)))).isEqualTo("n\r\n3\r\n2.5\r\n");
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=CsvWriterTest`
Expected: compilation failure — `CsvWriter` does not exist.

- [ ] **Step 7: Write `CsvWriter`**

`backend/src/main/java/com/sixpack/dashboard/web/CsvWriter.java`:

```java
package com.sixpack.dashboard.web;

import java.math.BigDecimal;
import java.util.List;

/** RFC 4180 CSV for section exports. Text that a spreadsheet would run as a formula is prefixed with a quote. */
final class CsvWriter {

    private CsvWriter() {
    }

    static String write(List<String> header, List<List<Object>> rows) {
        StringBuilder out = new StringBuilder();
        line(out, List.copyOf(header));
        for (List<Object> row : rows) {
            line(out, row);
        }
        return out.toString();
    }

    private static void line(StringBuilder out, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
```

- [ ] **Step 8: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=CsvWriterTest`
Expected: PASS (5 tests).

- [ ] **Step 9: Add `StoreTime`**

`SummaryService` was just reworked by step 2 (it now reads the daily summary table) and keeps its own `zone()`/`today()`; leave it alone. The new services use this small component instead:

`backend/src/main/java/com/sixpack/dashboard/application/StoreTime.java`:

```java
package com.sixpack.dashboard.application;

import com.sixpack.shared.tenant.StoreConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** The store's own time zone (else the platform default) and today's date in it. */
@Component
public class StoreTime {

    private final StoreConfig store;
    private final Clock clock;

    @Autowired
    public StoreTime(StoreConfig store) {
        this(store, Clock.systemUTC());
    }

    StoreTime(StoreConfig store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ZoneId zone() {
        return ZoneId.of(store.effective().timeZone());
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }
}
```

- [ ] **Step 10: Extract `OrderQueries` from `MongoDashboardReadAdapter`**

Create `backend/src/main/java/com/sixpack/dashboard/infra/mongo/OrderQueries.java` by **moving** (not copying) these members out of `MongoDashboardReadAdapter`: `criteria(...)`, `itemCriteria(...)`, `productIdsInCategory(...)`, `localDay(...)`, `orZero(...)`, `stage(...)`, `id(...)`, `collection(...)`, `number(...)`. Make the statics package-private `static`, the instance ones package-private instance methods. Then add `salesBy`, `orders`, `hex` and the collection constants:

```java
package com.sixpack.dashboard.infra.mongo;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Building blocks the dashboard's Mongo adapters share: scope criteria, item narrowing, local-day keys and number
 * reads. Works on collection and field names only, so no other module's document classes are touched.
 */
@Component
class OrderQueries {

    static final String ORDERS = "orders";
    static final String PRODUCTS = "products";
    static final String USERS = "users";
    static final String CREATED_AT = "createdAt";

    private final TenantMongo mongo;

    OrderQueries(TenantMongo mongo) {
        this.mongo = mongo;
    }

    TenantMongo mongo() {
        return mongo;
    }

    /**
     * Orders placed in [from, to) and in scope, matched further by {@code extra} (may be null), grouped by
     * {@code key}. Each group has {@code orders} (a count, or a set of ids when narrowed to items — read it with
     * {@link #orders(Document)}), {@code sales} and {@code units}; whole-order groups also have {@code discount}
     * and {@code tax}. Narrowed to a product or category, only the matching items count.
     */
    List<AggregationOperation> salesBy(Scope scope, Instant from, Instant to, Criteria extra, Object key) {
        boolean itemScoped = scope.productId() != null || scope.category() != null;
        List<AggregationOperation> agg = new ArrayList<>();
        agg.add(Aggregation.match(criteria(Dataset.ORDERS, scope, from, to)));
        if (extra != null) {
            agg.add(Aggregation.match(extra));
        }
        if (itemScoped) {
            agg.add(stage("$unwind", "$orderItems"));
            agg.add(Aggregation.match(itemCriteria(scope)));
            agg.add(stage("$group", new Document("_id", key)
                    .append("orders", new Document("$addToSet", "$_id"))
                    .append("sales", new Document("$sum", new Document("$multiply", List.of(
                            orZero("$orderItems.price"), orZero("$orderItems.quantity")))))
                    .append("units", new Document("$sum", orZero("$orderItems.quantity")))));
        } else {
            agg.add(stage("$group", new Document("_id", key)
                    .append("orders", new Document("$sum", 1))
                    .append("sales", new Document("$sum", "$total"))
                    .append("discount", new Document("$sum", "$discount"))
                    .append("tax", new Document("$sum", "$tax"))
                    .append("units", new Document("$sum", new Document("$sum", "$orderItems.quantity")))));
        }
        return agg;
    }

    /** The {@code orders} field of a {@link #salesBy} group: a count, or the size of the set of order ids. */
    static long orders(Document group) {
        Object orders = group.get("orders");
        return orders instanceof List<?> ids ? ids.size() : (long) number(orders);
    }

    /** An id as the hex string the API uses, whatever type Mongo stored it as. */
    static String hex(Object id) {
        return id == null ? null : id.toString();
    }

    // criteria, itemCriteria, productIdsInCategory, localDay, orZero, stage, id, collection, number:
    // moved here unchanged from MongoDashboardReadAdapter.
}
```

In `MongoDashboardReadAdapter`: replace the `TenantMongo mongo` field with `OrderQueries q` (constructor `MongoDashboardReadAdapter(OrderQueries q)`), use `q.mongo()` wherever `mongo` was used, call the moved helpers through `q.` / `OrderQueries.`, and rewrite `dailyOrders` on top of `salesBy`:

```java
    @Override
    public Map<LocalDate, DayTotals> dailyOrders(Scope scope, Instant from, Instant to, ZoneId zone) {
        boolean itemScoped = scope.productId() != null || scope.category() != null;
        Map<LocalDate, DayTotals> out = new LinkedHashMap<>();
        for (Document d : q.mongo().aggregate(q.salesBy(scope, from, to, null,
                OrderQueries.localDay("$createdAt", zone)), OrderQueries.ORDERS, Document.class)) {
            out.put(LocalDate.parse(d.getString("_id")), new DayTotals(OrderQueries.orders(d),
                    OrderQueries.number(d.get("sales")), OrderQueries.number(d.get("units")),
                    itemScoped ? null : OrderQueries.number(d.get("discount")),
                    itemScoped ? null : OrderQueries.number(d.get("tax"))));
        }
        return out;
    }
```

- [ ] **Step 11: Run the existing dashboard tests to prove the refactor changed nothing**

Run: `cd backend && mvn -q test -Dtest='DashboardSummaryIntegrationTest,DailySummaryIntegrationTest,VariantSalesIntegrationTest,KpisTest,GranularityTest,PeriodTest,MonthBucketsTest'`
Expected: PASS (same count as before the change). If a listed class does not exist, `mvn` reports "No tests matching" for it only — drop it from the pattern.

- [ ] **Step 12: Clear caches and the daily summary between integration tests**

Section tests seed different data under the same query, so a cached answer — or a `daily_sales` row written by step 2's `DailySummaryJob` from an earlier test's orders — would leak into the next test. In `IntegrationTest.java` add a field:

```java
    @Autowired
    protected org.springframework.cache.CacheManager cacheManager;
```

and in the existing `cleanDatabase()` keep its loop as it is, add `"daily_sales", "daily_sales_state"` to the end of its collection array, and add after the loop:

```java
        // TenantCache.clear() clears the whole delegate, so no tenant context is needed here
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
```

(`DailySummaryIntegrationTest` already deletes those two collections itself; deleting them earlier is harmless.)

- [ ] **Step 13: Write the failing index test**

`backend/src/test/java/com/sixpack/it/OrderIndexesIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/** The analytics sections read orders by date, by status and by customer; each needs its index. */
class OrderIndexesIntegrationTest extends IntegrationTest {

    @Test
    void ordersHaveTheAnalyticsIndexes() {
        List<String> names = new ArrayList<>();
        for (Document index : mongo.getCollection("orders").listIndexes()) {
            names.add(index.getString("name"));
        }
        assertThat(names).contains("tenant_created", "tenant_status_created", "tenant_user_created");
    }
}
```

Run: `cd backend && mvn -q test -Dtest=OrderIndexesIntegrationTest`
Expected: FAIL — the names are missing.

- [ ] **Step 14: Add the indexes**

In `OrderDocument.java` replace the single `@CompoundIndex` with:

```java
@CompoundIndexes({
        @CompoundIndex(name = "tenant_order_number", def = "{'tenantId': 1, 'orderNumber': 1}", unique = true,
                partialFilter = "{'orderNumber': {$type: 'string'}}"),
        @CompoundIndex(name = "tenant_created", def = "{'tenantId': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "tenant_status_created", def = "{'tenantId': 1, 'status': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "tenant_user_created", def = "{'tenantId': 1, 'user': 1, 'createdAt': 1}")
})
```

(import `org.springframework.data.mongodb.core.index.CompoundIndexes`). `auto-index-creation: true` in `application.yml` creates them at start-up.

Run: `cd backend && mvn -q test -Dtest=OrderIndexesIntegrationTest`
Expected: PASS.

- [ ] **Step 15: Add `SectionRequest` and the empty `AnalyticsController`**

`backend/src/main/java/com/sixpack/dashboard/web/SectionRequest.java`:

```java
package com.sixpack.dashboard.web;

import com.sixpack.dashboard.domain.Basis;
import com.sixpack.dashboard.domain.Compare;
import com.sixpack.dashboard.domain.Granularity;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.dashboard.domain.SectionQuery;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;

/** Query parameters every section accepts; same rules as {@code /summary}. */
record SectionRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                      @Size(max = 10) String compare, @Size(max = 10) String granularity,
                      @Size(max = 10) String basis, @Size(max = 50) String category, Integer limit,
                      @Size(max = 10) String format) {

    static final int DEFAULT_DAYS = 30;

    /** @throws IllegalArgumentException on a bad value; the controller turns it into a 400 */
    SectionQuery toQuery(LocalDate today) {
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        String cat = category == null || category.isBlank() ? null : category.trim().toLowerCase(Locale.ROOT);
        return new SectionQuery(new Scope(null, cat, null), Period.of(start, end),
                parse(Granularity.class, granularity, null), parse(Compare.class, compare, Compare.PREVIOUS),
                parse(Basis.class, basis, Basis.NET), limit == null ? SectionQuery.DEFAULT_LIMIT : limit);
    }

    boolean csv() {
        if (format == null || format.isBlank() || format.equalsIgnoreCase("json")) {
            return false;
        }
        if (format.equalsIgnoreCase("csv")) {
            return true;
        }
        throw new IllegalArgumentException("format: unknown value");
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(type.getSimpleName().toLowerCase(Locale.ROOT) + ": unknown value");
        }
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/web/AnalyticsController.java`:

```java
package com.sixpack.dashboard.web;

import com.sixpack.dashboard.application.StoreTime;
import com.sixpack.dashboard.domain.Section;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.web.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One route per analytics tab. Each answers JSON, or its main table as CSV with {@code ?format=csv}. */
@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
class AnalyticsController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final StoreTime time;

    AnalyticsController(StoreTime time) {
        this.time = time;
    }

    private ResponseEntity<?> respond(String name, SectionRequest req, Function<SectionQuery, ? extends Section> load) {
        boolean csv;
        Section section;
        try {
            csv = req.csv();
            section = load.apply(req.toQuery(time.today()));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (!csv) {
            return ResponseEntity.ok(ApiResponse.of("section", section));
        }
        String file = name + "-" + section.window().from() + "_" + section.window().to() + ".csv";
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(CsvWriter.write(section.csvHeader(), section.csvRows()));
    }
}
```

(The unused-method warning goes away in Task 2 when the first route calls `respond`.)

- [ ] **Step 16: Run all backend tests**

Run: `cd backend && mvn -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 17: Commit**

```bash
git add backend/src
git commit -m "feat(analytics): shared section query, CSV writer and order indexes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 2: Overview section and the tabbed page shell

**Files:**
- Create: `backend/src/main/java/com/sixpack/dashboard/domain/OverviewReads.java`, `OverviewSection.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoOverviewReads.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/application/OverviewService.java`
- Modify: `backend/src/main/java/com/sixpack/dashboard/web/AnalyticsController.java` (route)
- Create: `backend/src/test/java/com/sixpack/it/AnalyticsSeed.java` (used by every section IT)
- Test: `backend/src/test/java/com/sixpack/dashboard/domain/OverviewSectionTest.java`, `backend/src/test/java/com/sixpack/it/OverviewSectionIntegrationTest.java`
- Create: `ecommerce-frontend/src/types/analytics.ts`, `ecommerce-frontend/src/components/admin/analytics/sections.ts`, `sections.test.ts`, `Shared.tsx`, `OverviewTab.tsx`, `ecommerce-frontend/src/styles/admin-styles/_analytics.scss`
- Modify: `ecommerce-frontend/src/pages/admin/analytics.tsx`, `ecommerce-frontend/src/redux/api/dashboardAPI.ts`, `ecommerce-frontend/src/styles/app.scss`

**Interfaces:**
- Consumes: Task 1's `SectionQuery`, `Window`, `Section`, `OrderQueries.salesBy/orders/number`, `StoreTime`, `AnalyticsController#respond`; existing `SummaryService#summary(Scope, Period, Granularity, Compare, Basis)` returning `Summary(…, Kpis current, Kpis previous, Kpis.Change changePercent, List<Series.Point> series)`.
- Produces:
  - `interface OverviewReads { List<WeekHourCell> weekHour(Scope, Instant from, Instant to, ZoneId zone); record WeekHourCell(int weekday /*1 = Monday*/, int hour, long orders, double sales) }`
  - `record OverviewSection(Window window, String basis, Kpis current, Kpis previous, Kpis.Change change, List<Series.Point> series, List<WeekdayTotals> byWeekday, long[] byHour, long[][] heat) implements Section` with `static of(...)`.
  - `OverviewService#section(SectionQuery): OverviewSection`
  - `GET /api/v1/dashboard/overview`
  - `AnalyticsSeed` test helper (below), reused by Tasks 3, 5–8, 10.
  - Frontend: `SectionParams`, `SectionResponse<T>`, `SectionName`, `SectionWindow` in `types/analytics.ts`; `TABS`, `tabFrom`, `csvPath`, `fileNameFrom`, `bucketLabel`, `heatLevel`, `COMPARE_LABEL`, `WEEKDAYS`, `HOURS` in `sections.ts`; `Kpi`, `ChangeBadge`, `CsvButton`, `SectionState` in `Shared.tsx`; `useGetOverviewQuery`.

- [ ] **Step 1: Write the failing domain test**

`backend/src/test/java/com/sixpack/dashboard/domain/OverviewSectionTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.OverviewReads.WeekHourCell;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverviewSectionTest {

    private static final Window W = new Window(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null,
            "Asia/Kolkata", "day");
    private static final Kpis NONE = new Kpis(0, 0, 0, 0, 0.0, 0.0, 0.0);

    @Test
    void foldsCellsIntoWeekdaysHoursAndAHeatGrid() {
        OverviewSection s = OverviewSection.of(W, "net", NONE, null, null, List.of(), List.of(
                new WeekHourCell(1, 15, 2, 800), new WeekHourCell(1, 9, 1, 100), new WeekHourCell(7, 15, 3, 300)));
        assertThat(s.byWeekday()).hasSize(7);
        assertThat(s.byWeekday().get(0).orders()).isEqualTo(3);
        assertThat(s.byWeekday().get(0).revenue()).isEqualTo(900.0);
        assertThat(s.byWeekday().get(6).weekday()).isEqualTo(7);
        assertThat(s.byHour()[15]).isEqualTo(5);
        assertThat(s.heat()[0][9]).isEqualTo(1);
        assertThat(s.heat()[6][15]).isEqualTo(3);
    }

    @Test
    void emptyStoreHasSevenZeroDaysAndTwentyFourZeroHours() {
        OverviewSection s = OverviewSection.of(W, "net", NONE, null, null, List.of(), List.of());
        assertThat(s.byWeekday()).allMatch(d -> d.orders() == 0 && d.revenue() == 0);
        assertThat(s.byHour()).hasSize(24).containsOnly(0L);
        assertThat(s.heat()).hasNumberOfRows(7);
    }

    @Test
    void csvIsTheTrend() {
        OverviewSection s = OverviewSection.of(W, "net", NONE, null, null,
                List.of(new Series.Point(LocalDate.of(2026, 10, 1), 1500, 2)), List.of());
        assertThat(s.csvHeader()).containsExactly("start", "revenue", "orders");
        assertThat(s.csvRows()).containsExactly(List.of(LocalDate.of(2026, 10, 1), 1500.0, 2L));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=OverviewSectionTest`
Expected: compilation failure — `OverviewReads`, `OverviewSection` missing.

- [ ] **Step 3: Write the port and the section**

`backend/src/main/java/com/sixpack/dashboard/domain/OverviewReads.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Read side of the Overview tab. */
public interface OverviewReads {

    /** @param weekday ISO day of week in the store's zone, 1 = Monday */
    record WeekHourCell(int weekday, int hour, long orders, double sales) {
    }

    /** Orders placed in [from, to) by store-local weekday and hour; narrowed to items like the daily trend. */
    List<WeekHourCell> weekHour(Scope scope, Instant from, Instant to, ZoneId zone);
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/OverviewSection.java`:

```java
package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.OverviewReads.WeekHourCell;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The period's headline numbers and trend (the same as {@code /summary}) plus when orders come in.
 *
 * @param byHour orders per store-local hour, index 0..23
 * @param heat   orders per weekday (row 0 = Monday) and hour
 */
public record OverviewSection(@JsonUnwrapped Window window, String basis, Kpis current, Kpis previous,
                              Kpis.Change change, List<Series.Point> series, List<WeekdayTotals> byWeekday,
                              long[] byHour, long[][] heat) implements Section {

    /** @param revenue sales before refunds */
    public record WeekdayTotals(int weekday, long orders, double revenue) {
    }

    public static OverviewSection of(Window window, String basis, Kpis current, Kpis previous, Kpis.Change change,
                                     List<Series.Point> series, List<WeekHourCell> cells) {
        long[] orders = new long[7];
        double[] revenue = new double[7];
        long[] byHour = new long[24];
        long[][] heat = new long[7][24];
        for (WeekHourCell c : cells) {
            int day = c.weekday() - 1;
            orders[day] += c.orders();
            revenue[day] += c.sales();
            byHour[c.hour()] += c.orders();
            heat[day][c.hour()] += c.orders();
        }
        List<WeekdayTotals> byWeekday = IntStream.range(0, 7)
                .mapToObj(i -> new WeekdayTotals(i + 1, orders[i], revenue[i])).toList();
        return new OverviewSection(window, basis, current, previous, change, series, byWeekday, byHour, heat);
    }

    @Override
    public List<String> csvHeader() {
        return List.of("start", "revenue", "orders");
    }

    @Override
    public List<List<Object>> csvRows() {
        return series.stream().map(p -> List.<Object>of(p.start(), p.revenue(), p.orders())).toList();
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=OverviewSectionTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Create the shared seeding helper for section integration tests**

`backend/src/test/java/com/sixpack/it/AnalyticsSeed.java`:

```java
package com.sixpack.it;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Raw documents for analytics tests, written straight to Mongo in the stored shape the dashboard reads. Builders
 * return a {@link Document}; change any field with {@code append} (it replaces) before saving.
 */
final class AnalyticsSeed {

    private final MongoTemplate mongo;
    private final String tenant;

    AnalyticsSeed(MongoTemplate mongo, String tenant) {
        this.mongo = mongo;
        this.tenant = tenant;
    }

    static String newId() {
        return new ObjectId().toHexString();
    }

    static Date date(String iso) {
        return Date.from(Instant.parse(iso));
    }

    /** A paid, Processing order by user "u1" with no tax, discount or shipping. */
    Document order(String at, double total, Document... items) {
        return new Document("_id", new ObjectId()).append("tenantId", tenant).append("user", "u1")
                .append("status", "Processing").append("total", total).append("discount", 0.0).append("tax", 0.0)
                .append("shippingCharges", 0.0).append("orderItems", List.of(items))
                .append("payment", new Document("status", "PAID")).append("createdAt", date(at));
    }

    ObjectId save(Document order) {
        mongo.getCollection("orders").insertOne(order);
        return order.getObjectId("_id");
    }

    static Document item(String productId, double price, int quantity) {
        return new Document("productId", new ObjectId(productId)).append("name", "Product " + productId.substring(20))
                .append("price", price).append("quantity", quantity);
    }

    static Document item(String productId, String variantId, String label, double price, int quantity) {
        return item(productId, price, quantity).append("variantId", variantId).append("variantLabel", label);
    }

    static Document history(String status, String at) {
        return new Document("status", status).append("at", date(at));
    }

    static Document shipTo(String state, String country, Long pinCode) {
        return new Document("address", "1 Main Road").append("city", "City").append("state", state)
                .append("country", country).append("pinCode", pinCode);
    }

    static Document refund(double amount, String status, String at) {
        return new Document("amount", amount).append("status", status).append("at", date(at));
    }

    /** An ACTIVE product without variants. */
    Document product(String id, String name, String category, double price, int stock) {
        return new Document("_id", new ObjectId(id)).append("tenantId", tenant).append("name", name)
                .append("category", category).append("price", price).append("stock", stock)
                .append("status", "ACTIVE").append("createdAt", new Date());
    }

    static Document variant(String variantId, String sku, String value, double price, int stock) {
        return new Document("variantId", variantId).append("sku", sku)
                .append("options", List.of(new Document("name", "Size").append("value", value)))
                .append("price", price).append("stock", stock).append("status", "ACTIVE");
    }

    void saveProduct(Document product) {
        mongo.getCollection("products").insertOne(product);
    }

    /** A shopper account; returns its id as the hex string orders store in {@code user}. */
    String customer(String name, String createdAt) {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(new Document("_id", id).append("tenantId", tenant)
                .append("name", name).append("email", name.toLowerCase().replace(' ', '.') + "@shop.test")
                .append("role", "user").append("createdAt", date(createdAt)));
        return id.toHexString();
    }
}
```

- [ ] **Step 6: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/OverviewSectionIntegrationTest.java`:

```java
package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class OverviewSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private String admin;

    @BeforeEach
    void seedOrders() throws Exception {
        admin = adminToken();
        AnalyticsSeed seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.save(seed.order("2026-10-05T20:00:00Z", 1000, item(SHOE, 500, 2))); // Tue 6 Oct 01:30 in India
        seed.save(seed.order("2026-10-05T10:00:00Z", 500, item(SHOE, 500, 1)));  // Mon 5 Oct 15:30 in India
        AnalyticsSeed other = new AnalyticsSeed(mongo, "another-shop");
        other.save(other.order("2026-10-05T10:00:00Z", 9999, item(SHOE, 9999, 1)));
    }

    private MockHttpServletRequestBuilder overview() {
        return get("/api/v1/dashboard/overview").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void headlineNumbersAndWindowForTheRange() throws Exception {
        mvc.perform(overview())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.from").value("2026-10-01"))
                .andExpect(jsonPath("$.section.compareFrom").value("2026-08-31"))
                .andExpect(jsonPath("$.section.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.section.current.orders").value(2))
                .andExpect(jsonPath("$.section.current.revenue").value(1500.0))
                .andExpect(jsonPath("$.section.series.length()").value(31));
    }

    @Test
    void ordersByWeekdayAndHourInTheStoreZone() throws Exception {
        mvc.perform(overview())
                .andExpect(jsonPath("$.section.byWeekday[0].orders").value(1))
                .andExpect(jsonPath("$.section.byWeekday[0].revenue").value(500.0))
                .andExpect(jsonPath("$.section.byWeekday[1].orders").value(1))
                .andExpect(jsonPath("$.section.byHour[15]").value(1))
                .andExpect(jsonPath("$.section.byHour[1]").value(1))
                .andExpect(jsonPath("$.section.heat[1][1]").value(1))
                .andExpect(jsonPath("$.section.heat[0][15]").value(1));
    }

    @Test
    void weekdayAndHourFollowTheStoreZone() throws Exception {
        try {
            storeTimeZone("UTC");
            mvc.perform(overview())
                    .andExpect(jsonPath("$.section.timezone").value("UTC"))
                    .andExpect(jsonPath("$.section.byWeekday[0].orders").value(2))
                    .andExpect(jsonPath("$.section.byHour[20]").value(1))
                    .andExpect(jsonPath("$.section.byHour[10]").value(1));
        } finally {
            storeTimeZone(null);
        }
    }

    @Test
    void exportsTheTrendAsCsv() throws Exception {
        mvc.perform(overview().param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        containsString("filename=\"overview-2026-10-01_2026-10-31.csv\"")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "start,revenue,orders\r\n2026-10-01,0,0\r\n")));
    }

    @Test
    void rejectsBadParameters() throws Exception {
        mvc.perform(overview().param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(overview().param("format", "xml")).andExpect(status().isBadRequest());
        mvc.perform(overview().param("compare", "sometimes")).andExpect(status().isBadRequest());
    }

    @Test
    void customersCannotReadIt() throws Exception {
        String user = register("shopper@test.dev");
        mvc.perform(get("/api/v1/dashboard/overview").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    private void storeTimeZone(String zone) throws Exception {
        Map<String, Object> regional = new HashMap<>();
        regional.put("timeZone", zone);
        mvc.perform(put("/api/v1/store/settings").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("regional", regional))))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=OverviewSectionIntegrationTest`
Expected: FAIL — 404 (or 500 "No static resource") on `/api/v1/dashboard/overview`.

- [ ] **Step 8: Write the adapter, the service and the route**

`backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoOverviewReads.java`:

```java
package com.sixpack.dashboard.infra.mongo;

import com.sixpack.dashboard.domain.OverviewReads;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.stereotype.Component;

@Component
class MongoOverviewReads implements OverviewReads {

    private final OrderQueries q;

    MongoOverviewReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<WeekHourCell> weekHour(Scope scope, Instant from, Instant to, ZoneId zone) {
        Document at = new Document("date", "$createdAt").append("timezone", zone.getId());
        Document key = new Document("d", new Document("$isoDayOfWeek", at)).append("h", new Document("$hour", at));
        List<WeekHourCell> out = new ArrayList<>();
        for (Document d : q.mongo().aggregate(q.salesBy(scope, from, to, null, key), OrderQueries.ORDERS,
                Document.class)) {
            Document id = d.get("_id", Document.class);
            out.add(new WeekHourCell(id.getInteger("d"), id.getInteger("h"), OrderQueries.orders(d),
                    OrderQueries.number(d.get("sales"))));
        }
        return out;
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/application/OverviewService.java`:

```java
package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.OverviewReads;
import com.sixpack.dashboard.domain.OverviewSection;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Window;
import java.time.ZoneId;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Overview tab: the summary for the period plus when in the week orders come in. */
@Service
public class OverviewService {

    private final SummaryService summaries;
    private final OverviewReads read;
    private final StoreTime time;

    OverviewService(SummaryService summaries, OverviewReads read, StoreTime time) {
        this.summaries = summaries;
        this.read = read;
        this.time = time;
    }

    /** Part of every cache key, so a changed store zone is never answered from the cache. */
    public String cacheTag() {
        return time.zone().getId();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'overview:' + #root.target.cacheTag() + #q")
    public OverviewSection section(SectionQuery q) {
        ZoneId zone = time.zone();
        SummaryService.Summary s = summaries.summary(q.scope(), q.period(), q.granularity(), q.compare(), q.basis());
        return OverviewSection.of(Window.of(q, zone), s.basis(), s.current(), s.previous(), s.changePercent(),
                s.series(), read.weekHour(q.scope(), q.period().start(zone), q.period().end(zone), zone));
    }
}
```

In `AnalyticsController` add the field, constructor parameter and route (imports `OverviewService`, `jakarta.validation.Valid`, `GetMapping`):

```java
    private final OverviewService overview;

    AnalyticsController(StoreTime time, OverviewService overview) {
        this.time = time;
        this.overview = overview;
    }

    @GetMapping("/overview")
    ResponseEntity<?> overview(@Valid SectionRequest req) {
        return respond("overview", req, overview::section);
    }
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='OverviewSectionIntegrationTest,OverviewSectionTest'`
Expected: PASS. If `$.section.from` is missing, `@JsonUnwrapped` on the record component was not applied: put `@JsonUnwrapped` on an explicit accessor (`@JsonUnwrapped public Window window() { return window; }`) — do the same in every later section.

- [ ] **Step 10: Commit the backend**

```bash
git add backend/src
git commit -m "feat(analytics): overview section with weekday and hour breakdown

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 11: Write the failing frontend helper test**

`ecommerce-frontend/src/components/admin/analytics/sections.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { bucketLabel, csvPath, fileNameFrom, heatLevel, tabFrom } from "./sections";

describe("analytics sections", () => {
  it("reads the tab from the URL and falls back to overview", () => {
    expect(tabFrom("sales")).toBe("sales");
    expect(tabFrom(null)).toBe("overview");
    expect(tabFrom("nope")).toBe("overview");
  });

  it("builds a CSV path without empty parameters", () => {
    expect(csvPath("sales", { from: "2026-10-01", to: "2026-10-31", compare: "previous", category: "", limit: undefined }))
      .toBe("/api/v1/dashboard/sales?from=2026-10-01&to=2026-10-31&compare=previous&format=csv");
  });

  it("takes the file name from Content-Disposition", () => {
    expect(fileNameFrom('attachment; filename="overview-2026-10-01_2026-10-31.csv"', "x.csv"))
      .toBe("overview-2026-10-01_2026-10-31.csv");
    expect(fileNameFrom(null, "x.csv")).toBe("x.csv");
  });

  it("scales heat into five levels and never divides by zero", () => {
    expect(heatLevel(0, 10)).toBe(0);
    expect(heatLevel(1, 10)).toBe(1);
    expect(heatLevel(10, 10)).toBe(4);
    expect(heatLevel(5, 0)).toBe(0);
  });

  it("labels buckets without shifting the date", () => {
    expect(bucketLabel("2026-10-05", "day")).toBe("5 Oct");
    expect(bucketLabel("2026-10-01", "month")).toBe("Oct 2026");
  });
});
```

Run: `cd ecommerce-frontend && npm test -- src/components/admin/analytics/sections.test.ts`
Expected: FAIL — cannot resolve `./sections`.

- [ ] **Step 12: Write the types and helpers**

`ecommerce-frontend/src/types/analytics.ts`:

```ts
import { SummaryChange, SummaryKpis } from "./api-types";

export type SectionName = "overview" | "sales" | "orders" | "stock" | "customers" | "demand" | "location";

export type SectionParams = {
  from: string;
  to: string;
  compare: "previous" | "year" | "none";
  basis: "net" | "gross";
  category?: string;
  limit?: number;
};

export type SectionWindow = {
  from: string;
  to: string;
  compareFrom?: string | null;
  compareTo?: string | null;
  timezone: string;
  granularity: "day" | "week" | "month";
};

export type SectionResponse<T> = { success: boolean; section: T };

export type SeriesPoint = { start: string; revenue: number; orders: number };

export type OverviewSection = SectionWindow & {
  basis: "net" | "gross";
  current: SummaryKpis;
  previous?: SummaryKpis | null;
  change?: SummaryChange | null;
  series: SeriesPoint[];
  byWeekday: { weekday: number; orders: number; revenue: number }[];
  byHour: number[];
  heat: number[][];
};
```

`ecommerce-frontend/src/components/admin/analytics/sections.ts`:

```ts
import { SectionName } from "../../../types/analytics";

export const TABS: { id: SectionName; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "sales", label: "Sales" },
  { id: "orders", label: "Orders" },
  { id: "stock", label: "Stock" },
  { id: "customers", label: "Customers" },
  { id: "demand", label: "Demand" },
  { id: "location", label: "Location" },
];

export const tabFrom = (value: string | null): SectionName => TABS.find((t) => t.id === value)?.id ?? "overview";

export const COMPARE_LABEL = { previous: "vs previous period", year: "vs last year", none: "" } as const;

export const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
export const HOURS = Array.from({ length: 24 }, (_, h) => h);

const MONTH_SHORT = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "5 Oct" for days and weeks, "Oct 2026" for months; the date string is read as is, never shifted by time zone. */
export const bucketLabel = (start: string, granularity: "day" | "week" | "month") => {
  const [y, m, d] = start.split("-").map(Number);
  return granularity === "month" ? `${MONTH_SHORT[m - 1]} ${y}` : `${d} ${MONTH_SHORT[m - 1]}`;
};

/** 0 for nothing, else 1..4 relative to the busiest cell. */
export const heatLevel = (value: number, max: number) =>
  max <= 0 || value <= 0 ? 0 : Math.max(1, Math.ceil((value / max) * 4));

export const csvPath = (section: SectionName, params: Record<string, string | number | undefined | null>) => {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") q.set(k, String(v));
  });
  q.set("format", "csv");
  return `/api/v1/dashboard/${section}?${q.toString()}`;
};

export const fileNameFrom = (disposition: string | null, fallback: string) =>
  disposition?.match(/filename="?([^";]+)"?/)?.[1] ?? fallback;
```

Run: `cd ecommerce-frontend && npm test -- src/components/admin/analytics/sections.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 13: Write the shared components**

`ecommerce-frontend/src/components/admin/analytics/Shared.tsx`:

```tsx
import { useState } from "react";
import toast from "react-hot-toast";
import { LuDownload, LuTrendingDown, LuTrendingUp } from "react-icons/lu";
import { getToken } from "../../../redux/api/baseQuery";
import { server } from "../../../redux/config";
import { SectionName } from "../../../types/analytics";
import { csvPath, fileNameFrom } from "./sections";

export const ChangeBadge = ({ change, against }: { change?: number | null; against: string }) =>
  change == null ? null : (
    <small className={change >= 0 ? "green" : "red"}>
      {change >= 0 ? <LuTrendingUp /> : <LuTrendingDown />} {change > 0 ? "+" : ""}
      {change}% <em>{against}</em>
    </small>
  );

export const Kpi = ({ label, value, change, against = "" }: {
  label: string; value: string; change?: number | null; against?: string;
}) => (
  <article className="kpi">
    <span>{label}</span>
    <strong>{value}</strong>
    <ChangeBadge change={change} against={against} />
  </article>
);

export const SectionState = ({ loading }: { loading?: boolean }) => (
  <p className="data-table__empty" role="status">
    {loading ? "Loading…" : "Could not load this section. Please retry."}
  </p>
);

export const CsvButton = ({ section, params }: {
  section: SectionName; params: Record<string, string | number | undefined | null>;
}) => {
  const [busy, setBusy] = useState(false);
  const download = async () => {
    setBusy(true);
    try {
      const res = await fetch(`${server}${csvPath(section, params)}`, {
        headers: { Authorization: `Bearer ${getToken() ?? ""}` },
      });
      if (!res.ok) throw new Error(String(res.status));
      const url = URL.createObjectURL(await res.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = fileNameFrom(res.headers.get("Content-Disposition"), `${section}.csv`);
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      toast.error("Could not export this section");
    } finally {
      setBusy(false);
    }
  };
  return (
    <button type="button" className="btn" onClick={download} disabled={busy}>
      <LuDownload aria-hidden /> {busy ? "Exporting…" : "Export CSV"}
    </button>
  );
};
```

(If the CORS config does not expose `Content-Disposition`, the fallback name is used — acceptable.)

- [ ] **Step 14: Add the query hook**

In `ecommerce-frontend/src/redux/api/dashboardAPI.ts` import `OverviewSection, SectionParams, SectionResponse` from `../../types/analytics`, add the endpoint and export the hook:

```ts
    getOverview: builder.query<SectionResponse<OverviewSection>, SectionParams>({
      query: (params) => ({ url: "overview", params: clean(params) }),
    }),
```

```ts
  useGetOverviewQuery,
```

- [ ] **Step 15: Write the Overview tab**

`ecommerce-frontend/src/components/admin/analytics/OverviewTab.tsx` — the KPI row, note and revenue chart are the ones currently in `analytics.tsx`, moved here; the low-stock card (the `<div className="card chart-card chart-card--wide low-stock">` block and its `useLowStockQuery` / `alerts` lines) moves here unchanged too and is deleted again in Task 6:

```tsx
import { useGetOverviewQuery } from "../../../redux/api/dashboardAPI";
import { SectionParams } from "../../../types/analytics";
import { formatMoney } from "../../../utils/features";
import { BarChart } from "../Charts";
import { chartColors as c } from "../chartTheme";
import { COMPARE_LABEL, HOURS, WEEKDAYS, bucketLabel, heatLevel } from "./sections";
import { CsvButton, Kpi, SectionState } from "./Shared";

const OverviewTab = ({ params }: { params: SectionParams }) => {
  const { data, isLoading, isError } = useGetOverviewQuery(params);
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const now = s.current;
  const against = COMPARE_LABEL[params.compare];
  const maxHeat = Math.max(0, ...s.heat.flat());

  return (
    <>
      <div className="analytics__actions"><CsvButton section="overview" params={params} /></div>
      <section className="kpi-row">
        <Kpi label={s.basis === "net" ? "Net revenue" : "Gross revenue"} value={formatMoney(now.revenue)}
             change={s.change?.revenue} against={against} />
        <Kpi label="Orders" value={String(now.orders)} change={s.change?.orders} against={against} />
        <Kpi label="Average order value" value={formatMoney(now.averageOrderValue)}
             change={s.change?.averageOrderValue} against={against} />
        <Kpi label="Units sold" value={String(now.units)} change={s.change?.units} against={against} />
      </section>
      <p className="analytics__note">
        {now.refunds != null && <>Refunds {formatMoney(now.refunds)} · </>}
        {now.discount != null && <>Discounts {formatMoney(now.discount)} · </>}
        {now.tax != null && <>Tax collected {formatMoney(now.tax)} · </>}
        {params.category && <>Revenue counts only items in this category · </>}
        Days in {s.timezone}
      </p>
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide">
          <h4>Revenue & orders by {s.granularity}</h4>
          <BarChart data_1={s.series.map((p) => p.revenue)} data_2={s.series.map((p) => p.orders)}
                    title_1="Revenue" title_2="Orders" dualAxis bgColor_1={c.series[0]} bgColor_2={c.series[1]}
                    labels={s.series.map((p) => bucketLabel(p.start, s.granularity))} />
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Sales by weekday (before refunds)</h4>
          <BarChart data_1={s.byWeekday.map((d) => d.revenue)} data_2={s.byWeekday.map((d) => d.orders)}
                    title_1="Sales" title_2="Orders" dualAxis bgColor_1={c.series[0]} bgColor_2={c.series[1]}
                    labels={WEEKDAYS} />
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>When orders come in ({s.timezone})</h4>
          <div className="heatmap" role="table" aria-label="Orders by weekday and hour">
            <div role="row" className="heatmap__row heatmap__head">
              <span role="columnheader" />
              {HOURS.map((h) => <span role="columnheader" key={h}>{h % 3 === 0 ? h : ""}</span>)}
            </div>
            {s.heat.map((row, d) => (
              <div role="row" className="heatmap__row" key={WEEKDAYS[d]}>
                <span role="rowheader">{WEEKDAYS[d]}</span>
                {row.map((n, h) => (
                  <span role="cell" key={h} className={`heatmap__cell l${heatLevel(n, maxHeat)}`}
                        title={`${WEEKDAYS[d]} ${h}:00 – ${n} orders`} aria-label={`${n} orders`} />
                ))}
              </div>
            ))}
          </div>
        </div>
        {/* low-stock card moved from analytics.tsx, unchanged (removed in Task 6) */}
      </section>
    </>
  );
};

export default OverviewTab;
```

(Replace the comment line with the moved low-stock JSX and add its imports — `useLowStockQuery`, `transformImage` — when you move it.)

- [ ] **Step 16: Turn `analytics.tsx` into the shell**

Keep the filter derivations (`category`, `preset`, `range`, `compare`, `basis`, `setParam`, `useCategoriesQuery`) and the `<header className="analytics__toolbar">…</header>` block exactly as they are. Delete everything else from the component body: the `Widget`, `MONTH_SHORT`, `bucketLabel` helpers, the `useGetSummaryQuery` / `useGetDashboardStatsQuery` / `useGetPieChartQuery` / `useGetBarChartQuery` / `useGetLineChartQuery` calls, the loader/error branches, the KPI row, the note, and the whole `analytics__grid` section (gender ratio and age charts are dropped by decision; the other charts come back in later tabs). Then render:

```tsx
  const tab = tabFrom(params.get("tab"));
  const section: SectionParams = { from: range.from, to: range.to, compare: compare ?? "previous",
                                   basis: basis ?? "net", category: category || undefined };

  return (
    <AdminLayout>
      <div className="analytics">
        {/* the existing <header className="analytics__toolbar"> block, unchanged */}
        <nav className="tabs" role="tablist" aria-label="Analytics sections">
          {TABS.map((t) => (
            <button key={t.id} type="button" role="tab" aria-selected={tab === t.id}
                    className={tab === t.id ? "is-active" : ""}
                    onClick={() => setParam({ tab: t.id === "overview" ? "" : t.id })}>
              {t.label}
            </button>
          ))}
        </nav>
        <div className="analytics__body" role="tabpanel">
          {tab === "overview" && <OverviewTab params={section} />}
        </div>
      </div>
    </AdminLayout>
  );
```

Imports to keep/add: `useSearchParams`, `AdminLayout`, `useCategoriesQuery`, `PRESETS`, `Preset`, `presetRange`, `SummaryParams`, `SectionParams` (from `../../types/analytics`), `TABS`, `tabFrom` (from `../../components/admin/analytics/sections`), `OverviewTab`. Remove imports that are now unused (`npm run lint` lists them).

- [ ] **Step 17: Add the styles**

`ecommerce-frontend/src/styles/admin-styles/_analytics.scss` (`.analytics` is already the page's scroll container, so the body does not scroll on its own):

```scss
.analytics__body {
  display: flex;
  flex-direction: column;
  gap: $spacing-lg;
}

.analytics__actions {
  display: flex;
  justify-content: flex-end;
  gap: $spacing-sm;
}

.heatmap {
  display: grid;
  gap: 2px;
  overflow-x: auto;
  font-size: 0.75rem;

  &__row {
    display: grid;
    grid-template-columns: 2.5rem repeat(24, minmax(0.875rem, 1fr));
    gap: 2px;
    align-items: center;
  }

  &__head span {
    color: $color-text-secondary;
    text-align: center;
  }

  &__cell {
    height: 1.125rem;
    border-radius: 2px;
    background: var(--border);

    &.l1 { background: color-mix(in srgb, var(--accent) 25%, var(--border)); }
    &.l2 { background: color-mix(in srgb, var(--accent) 50%, var(--border)); }
    &.l3 { background: color-mix(in srgb, var(--accent) 75%, var(--border)); }
    &.l4 { background: var(--accent); }
  }
}
```

In `ecommerce-frontend/src/styles/app.scss`, after `@import "admin-styles/admin-ui";` add `@import "admin-styles/analytics";`.

- [ ] **Step 18: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all tests pass, no lint warnings, build succeeds.

- [ ] **Step 19: Commit the frontend**

```bash
git add ecommerce-frontend/src
git commit -m "feat(analytics): tabbed analytics page with the overview tab

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: Sales section

**Files:**
- Create: `backend/src/main/java/com/sixpack/dashboard/domain/SalesReads.java`, `SalesSection.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoSalesReads.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/application/SalesService.java`
- Modify: `backend/src/main/java/com/sixpack/dashboard/web/AnalyticsController.java`
- Test: `backend/src/test/java/com/sixpack/dashboard/domain/SalesSectionTest.java`, `backend/src/test/java/com/sixpack/it/SalesSectionIntegrationTest.java`
- Create: `ecommerce-frontend/src/components/admin/analytics/SalesTab.tsx`
- Modify: `ecommerce-frontend/src/types/analytics.ts`, `ecommerce-frontend/src/redux/api/dashboardAPI.ts`, `ecommerce-frontend/src/components/admin/analytics/sections.ts` (+ test), `ecommerce-frontend/src/pages/admin/analytics.tsx`

**Interfaces:**
- Consumes: Task 1 plumbing; `DashboardReadPort#dailyOrders/dailyRefunds`; `Series.of(days, refunds, period, granularity, basis)`; `AnalyticsSeed`.
- Produces:
  - `interface SalesReads` with `record ProductRevenue(String productId, double revenue, long units)`, `record Breakdown(long orders, double total, double discount, double tax, double shipping, long couponOrders)`, `record MethodTotals(String method, long orders, double revenue)`; methods `byProduct(Scope, Instant, Instant)`, `categories(Collection<String> productIds): Map<String,String>`, `breakdown(Scope, Instant, Instant)`, `byPaymentMethod(Scope, Instant, Instant)`.
  - `record SalesSection(Window window, String basis, List<Series.Point> series, List<Series.Point> previousSeries, List<CategoryRow> byCategory, Breakdown breakdown, List<MethodRow> byPaymentMethod)`; `SalesSection.UNCATEGORISED = "uncategorised"`; statics `byCategory`, `breakdown`, `methods`.
  - `SalesService#section(SectionQuery)`; `GET /api/v1/dashboard/sales`.
  - Frontend: `SalesSection` type, `useGetSalesQuery`, `alignPrevious(current: SeriesPoint[], previous?: SeriesPoint[] | null): number[]` in `sections.ts`.

- [ ] **Step 1: Write the failing domain test**

`backend/src/test/java/com/sixpack/dashboard/domain/SalesSectionTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.SalesReads.MethodTotals;
import com.sixpack.dashboard.domain.SalesReads.ProductRevenue;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SalesSectionTest {

    private static final Map<String, String> CATEGORIES = Map.of("shoe", "footwear", "bag", "bags");

    @Test
    void revenueByCategoryWithShareAndChange() {
        List<SalesSection.CategoryRow> rows = SalesSection.byCategory(
                List.of(new ProductRevenue("shoe", 1000, 2), new ProductRevenue("bag", 300, 1),
                        new ProductRevenue("gone", 200, 1)),
                List.of(new ProductRevenue("shoe", 500, 2)), CATEGORIES);
        assertThat(rows).extracting(SalesSection.CategoryRow::category)
                .containsExactly("footwear", "bags", "uncategorised");
        assertThat(rows.get(0).share()).isEqualTo(66.7);
        assertThat(rows.get(0).change()).isEqualTo(100L);
        assertThat(rows.get(1).change()).isEqualTo(100L); // nothing before counts as +100%, like /summary
        assertThat(rows.get(2).units()).isEqualTo(1);
    }

    @Test
    void noComparisonMeansNoChange() {
        List<SalesSection.CategoryRow> rows = SalesSection.byCategory(
                List.of(new ProductRevenue("shoe", 10, 1)), null, CATEGORIES);
        assertThat(rows.get(0).change()).isNull();
    }

    @Test
    void breakdownNetIsChargedMinusRefunds() {
        SalesSection.Breakdown b = SalesSection.breakdown(new SalesReads.Breakdown(3, 1550, 100, 50, 100, 1), 200);
        assertThat(b.charged()).isEqualTo(1550);
        assertThat(b.net()).isEqualTo(1350);
        assertThat(b.couponOrderShare()).isEqualTo(33.3);
    }

    @Test
    void emptyPeriodHasNoRowsAndNoDivisionByZero() {
        assertThat(SalesSection.byCategory(List.of(), List.of(), CATEGORIES)).isEmpty();
        assertThat(SalesSection.breakdown(new SalesReads.Breakdown(0, 0, 0, 0, 0, 0), 0).couponOrderShare()).isZero();
        assertThat(SalesSection.methods(List.of())).isEmpty();
    }

    @Test
    void paymentMethodsByOrdersThenName() {
        assertThat(SalesSection.methods(List.of(new MethodTotals("upi", 1, 1050), new MethodTotals("card", 1, 300),
                new MethodTotals("unknown", 1, 200))))
                .extracting(SalesSection.MethodRow::method).containsExactly("card", "unknown", "upi");
    }

    @Test
    void csvRowsPutTheComparisonBesideEachBucket() {
        Window w = new Window(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), null, null, "UTC", "day");
        SalesSection s = new SalesSection(w, "net",
                List.of(new Series.Point(LocalDate.of(2026, 10, 1), 10, 1), new Series.Point(LocalDate.of(2026, 10, 2), 20, 2)),
                List.of(new Series.Point(LocalDate.of(2026, 9, 29), 5, 1)), List.of(), null, null);
        assertThat(s.csvHeader()).containsExactly("start", "revenue", "orders", "previousRevenue");
        assertThat(s.csvRows()).containsExactly(Arrays.asList(LocalDate.of(2026, 10, 1), 10.0, 1L, 5.0),
                Arrays.asList(LocalDate.of(2026, 10, 2), 20.0, 2L, null));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=SalesSectionTest`
Expected: compilation failure — `SalesReads`, `SalesSection` missing.

- [ ] **Step 3: Write the port and the section**

`backend/src/main/java/com/sixpack/dashboard/domain/SalesReads.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read side of the Sales tab. All periods are [from, to). */
public interface SalesReads {

    record ProductRevenue(String productId, double revenue, long units) {
    }

    /** Whole orders only: these cannot be split by item. */
    record Breakdown(long orders, double total, double discount, double tax, double shipping, long couponOrders) {
    }

    /** @param method how the customer paid, "unknown" when the provider did not say */
    record MethodTotals(String method, long orders, double revenue) {
    }

    /** Item revenue (price × quantity) per product; narrowed to items when the scope is. */
    List<ProductRevenue> byProduct(Scope scope, Instant from, Instant to);

    /** Category of each product that still exists; deleted products are missing from the map. */
    Map<String, String> categories(Collection<String> productIds);

    Breakdown breakdown(Scope scope, Instant from, Instant to);

    List<MethodTotals> byPaymentMethod(Scope scope, Instant from, Instant to);
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/SalesSection.java`:

```java
package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.SalesReads.MethodTotals;
import com.sixpack.dashboard.domain.SalesReads.ProductRevenue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the money comes from. {@code breakdown} and {@code byPaymentMethod} are null when the view is narrowed to a
 * category, since they belong to whole orders.
 *
 * @param previousSeries the comparison period's trend, null when the comparison is off
 */
public record SalesSection(@JsonUnwrapped Window window, String basis, List<Series.Point> series,
                           List<Series.Point> previousSeries, List<CategoryRow> byCategory, Breakdown breakdown,
                           List<MethodRow> byPaymentMethod) implements Section {

    public static final String UNCATEGORISED = "uncategorised";

    public record CategoryRow(String category, double revenue, long units, double share, Long change) {
    }

    /** @param charged what customers paid (order totals) before refunds; {@code net} = charged − refunds */
    public record Breakdown(double charged, double discount, double tax, double shipping, double refunds, double net,
                            long couponOrders, double couponOrderShare) {
    }

    public record MethodRow(String method, long orders, double revenue, double share) {
    }

    /** @param before null when the comparison is off */
    public static List<CategoryRow> byCategory(List<ProductRevenue> now, List<ProductRevenue> before,
                                               Map<String, String> categories) {
        Map<String, double[]> current = totals(now, categories);
        Map<String, double[]> previous = before == null ? null : totals(before, categories);
        double all = current.values().stream().mapToDouble(v -> v[0]).sum();
        return current.entrySet().stream()
                .map(e -> new CategoryRow(e.getKey(), e.getValue()[0], (long) e.getValue()[1],
                        Change.share(e.getValue()[0], all), previous == null ? null
                        : Change.percent(e.getValue()[0], previous.getOrDefault(e.getKey(), new double[2])[0])))
                .sorted(Comparator.comparingDouble(CategoryRow::revenue).reversed()
                        .thenComparing(CategoryRow::category))
                .toList();
    }

    public static Breakdown breakdown(SalesReads.Breakdown b, double refunds) {
        return new Breakdown(b.total(), b.discount(), b.tax(), b.shipping(), refunds, b.total() - refunds,
                b.couponOrders(), Change.share(b.couponOrders(), b.orders()));
    }

    public static List<MethodRow> methods(List<MethodTotals> rows) {
        long all = rows.stream().mapToLong(MethodTotals::orders).sum();
        return rows.stream().map(m -> new MethodRow(m.method(), m.orders(), m.revenue(), Change.share(m.orders(), all)))
                .sorted(Comparator.comparingLong(MethodRow::orders).reversed().thenComparing(MethodRow::method))
                .toList();
    }

    private static Map<String, double[]> totals(List<ProductRevenue> rows, Map<String, String> categories) {
        Map<String, double[]> out = new HashMap<>();
        for (ProductRevenue r : rows) {
            double[] t = out.computeIfAbsent(categories.getOrDefault(r.productId(), UNCATEGORISED),
                    k -> new double[2]);
            t[0] += r.revenue();
            t[1] += r.units();
        }
        return out;
    }

    @Override
    public List<String> csvHeader() {
        return List.of("start", "revenue", "orders", "previousRevenue");
    }

    @Override
    public List<List<Object>> csvRows() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            Series.Point p = series.get(i);
            Double before = previousSeries != null && i < previousSeries.size() ? previousSeries.get(i).revenue() : null;
            rows.add(Arrays.asList(p.start(), p.revenue(), p.orders(), before));
        }
        return rows;
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=SalesSectionTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/SalesSectionIntegrationTest.java`:

```java
package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static com.sixpack.it.AnalyticsSeed.refund;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SalesSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private static final String BAG = AnalyticsSeed.newId();
    private static final String GONE = AnalyticsSeed.newId(); // ordered, then deleted
    private String admin;

    @BeforeEach
    void seed() throws Exception {
        admin = adminToken();
        AnalyticsSeed seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.saveProduct(seed.product(SHOE, "Shoe", "footwear", 500, 10));
        seed.saveProduct(seed.product(BAG, "Bag", "bags", 300, 10));
        seed.save(seed.order("2026-10-05T10:00:00Z", 1050, item(SHOE, 500, 2))
                .append("discount", 100.0).append("tax", 50.0).append("shippingCharges", 100.0)
                .append("couponCode", "DIWALI10")
                .append("payment", new Document("status", "PAID").append("method", "upi")
                        .append("refunds", List.of(refund(200, "processed", "2026-10-07T06:00:00Z")))));
        seed.save(seed.order("2026-10-06T10:00:00Z", 300, item(BAG, 300, 1))
                .append("payment", new Document("status", "PAID").append("method", "card")));
        seed.save(seed.order("2026-10-07T10:00:00Z", 200, item(GONE, 200, 1))
                .append("payment", null)); // legacy order without payment details
        seed.save(seed.order("2026-09-15T10:00:00Z", 500, item(SHOE, 250, 2))
                .append("payment", new Document("status", "PAID").append("method", "upi")));
        AnalyticsSeed other = new AnalyticsSeed(mongo, "another-shop");
        other.save(other.order("2026-10-05T10:00:00Z", 9999, item(SHOE, 9999, 1)));
    }

    private MockHttpServletRequestBuilder sales() {
        return get("/api/v1/dashboard/sales").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void breakdownOfWhatWasChargedAndKept() throws Exception {
        mvc.perform(sales())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.breakdown.charged").value(1550.0))
                .andExpect(jsonPath("$.section.breakdown.discount").value(100.0))
                .andExpect(jsonPath("$.section.breakdown.tax").value(50.0))
                .andExpect(jsonPath("$.section.breakdown.shipping").value(100.0))
                .andExpect(jsonPath("$.section.breakdown.refunds").value(200.0))
                .andExpect(jsonPath("$.section.breakdown.net").value(1350.0))
                .andExpect(jsonPath("$.section.breakdown.couponOrders").value(1))
                .andExpect(jsonPath("$.section.breakdown.couponOrderShare").value(33.3));
    }

    @Test
    void netAgreesWithTheOverview() throws Exception {
        mvc.perform(get("/api/v1/dashboard/overview").header("Authorization", bearer(admin))
                        .param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(jsonPath("$.section.current.revenue").value(1350.0));
    }

    @Test
    void revenueByCategoryIncludingDeletedProducts() throws Exception {
        mvc.perform(sales())
                .andExpect(jsonPath("$.section.byCategory[0].category").value("footwear"))
                .andExpect(jsonPath("$.section.byCategory[0].revenue").value(1000.0))
                .andExpect(jsonPath("$.section.byCategory[0].share").value(66.7))
                .andExpect(jsonPath("$.section.byCategory[0].change").value(100))
                .andExpect(jsonPath("$.section.byCategory[1].category").value("bags"))
                .andExpect(jsonPath("$.section.byCategory[2].category").value("uncategorised"))
                .andExpect(jsonPath("$.section.byCategory[2].revenue").value(200.0));
    }

    @Test
    void paymentMethodsWithLegacyOrdersAsUnknown() throws Exception {
        mvc.perform(sales())
                .andExpect(jsonPath("$.section.byPaymentMethod.length()").value(3))
                .andExpect(jsonPath("$.section.byPaymentMethod[0].method").value("card"))
                .andExpect(jsonPath("$.section.byPaymentMethod[1].method").value("unknown"))
                .andExpect(jsonPath("$.section.byPaymentMethod[2].share").value(33.3));
    }

    @Test
    void comparisonTrendIsReturnedBesideTheCurrentOne() throws Exception {
        mvc.perform(sales())
                .andExpect(jsonPath("$.section.series.length()").value(31))
                .andExpect(jsonPath("$.section.previousSeries.length()").value(31))
                .andExpect(jsonPath("$.section.previousSeries[15].revenue").value(500.0));
        mvc.perform(sales().param("compare", "none"))
                .andExpect(jsonPath("$.section.previousSeries").doesNotExist())
                .andExpect(jsonPath("$.section.byCategory[0].change").doesNotExist());
    }

    @Test
    void categoryFilterKeepsOnlyItemFigures() throws Exception {
        mvc.perform(sales().param("category", "footwear"))
                .andExpect(jsonPath("$.section.breakdown").doesNotExist())
                .andExpect(jsonPath("$.section.byPaymentMethod").doesNotExist())
                .andExpect(jsonPath("$.section.byCategory.length()").value(1))
                .andExpect(jsonPath("$.section.byCategory[0].share").value(100.0));
    }

    @Test
    void exportsTheTrendWithTheComparison() throws Exception {
        mvc.perform(sales().param("format", "csv"))
                .andExpect(content().string(startsWith("start,revenue,orders,previousRevenue\r\n")));
    }
}
```

(Sep 15 is index 15 of the comparison period 31 Aug – 30 Sep.)

- [ ] **Step 6: Run it to verify it fails**

Run: `cd backend && mvn -q test -Dtest=SalesSectionIntegrationTest`
Expected: FAIL — `/api/v1/dashboard/sales` not found.

- [ ] **Step 7: Write the adapter, service and route**

`backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoSalesReads.java`:

```java
package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.SalesReads;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoSalesReads implements SalesReads {

    private final OrderQueries q;

    MongoSalesReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<ProductRevenue> byProduct(Scope scope, Instant from, Instant to) {
        List<AggregationOperation> agg = new ArrayList<>();
        agg.add(match(q.criteria(Dataset.ORDERS, scope, from, to)));
        agg.add(OrderQueries.stage("$unwind", "$orderItems"));
        if (scope.productId() != null || scope.category() != null) {
            agg.add(match(q.itemCriteria(scope)));
        }
        agg.add(OrderQueries.stage("$group", new Document("_id", "$orderItems.productId")
                .append("revenue", new Document("$sum", new Document("$multiply", List.of(
                        OrderQueries.orZero("$orderItems.price"), OrderQueries.orZero("$orderItems.quantity")))))
                .append("units", new Document("$sum", OrderQueries.orZero("$orderItems.quantity")))));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> new ProductRevenue(OrderQueries.hex(d.get("_id")), OrderQueries.number(d.get("revenue")),
                        (long) OrderQueries.number(d.get("units"))))
                .toList();
    }

    @Override
    public Map<String, String> categories(Collection<String> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where("_id").in(productIds.stream().map(OrderQueries::id).toList()));
        query.fields().include("category");
        Map<String, String> out = new HashMap<>();
        for (Document d : q.mongo().find(query, Document.class, OrderQueries.PRODUCTS)) {
            if (d.getString("category") != null) {
                out.put(OrderQueries.hex(d.get("_id")), d.getString("category"));
            }
        }
        return out;
    }

    @Override
    public Breakdown breakdown(Scope scope, Instant from, Instant to) {
        Document hasCoupon = new Document("$cond", List.of(new Document("$gt", List.of(
                new Document("$strLenCP", new Document("$ifNull", List.of("$couponCode", ""))), 0)), 1, 0));
        List<AggregationOperation> agg = List.of(match(q.criteria(Dataset.ORDERS, scope, from, to)),
                OrderQueries.stage("$group", new Document("_id", null)
                        .append("orders", new Document("$sum", 1))
                        .append("total", new Document("$sum", "$total"))
                        .append("discount", new Document("$sum", "$discount"))
                        .append("tax", new Document("$sum", "$tax"))
                        .append("shipping", new Document("$sum", "$shippingCharges"))
                        .append("coupons", new Document("$sum", hasCoupon))));
        Document d = q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getUniqueMappedResult();
        if (d == null) {
            return new Breakdown(0, 0, 0, 0, 0, 0);
        }
        return new Breakdown((long) OrderQueries.number(d.get("orders")), OrderQueries.number(d.get("total")),
                OrderQueries.number(d.get("discount")), OrderQueries.number(d.get("tax")),
                OrderQueries.number(d.get("shipping")), (long) OrderQueries.number(d.get("coupons")));
    }

    @Override
    public List<MethodTotals> byPaymentMethod(Scope scope, Instant from, Instant to) {
        List<AggregationOperation> agg = List.of(match(q.criteria(Dataset.ORDERS, scope, from, to)),
                OrderQueries.stage("$group", new Document("_id",
                        new Document("$ifNull", List.of("$payment.method", "unknown")))
                        .append("orders", new Document("$sum", 1))
                        .append("revenue", new Document("$sum", "$total"))));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> new MethodTotals(String.valueOf(d.get("_id")), (long) OrderQueries.number(d.get("orders")),
                        OrderQueries.number(d.get("revenue"))))
                .toList();
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/application/SalesService.java`:

```java
package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.SalesReads;
import com.sixpack.dashboard.domain.SalesReads.ProductRevenue;
import com.sixpack.dashboard.domain.SalesSection;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Series;
import com.sixpack.dashboard.domain.Window;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Sales tab. */
@Service
public class SalesService {

    private final DashboardReadPort orders;
    private final SalesReads sales;
    private final StoreTime time;

    SalesService(DashboardReadPort orders, SalesReads sales, StoreTime time) {
        this.orders = orders;
        this.sales = sales;
        this.time = time;
    }

    public String cacheTag() {
        return time.zone().getId();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'sales:' + #root.target.cacheTag() + #q")
    public SalesSection section(SectionQuery q) {
        ZoneId zone = time.zone();
        Period p = q.period();
        Instant from = p.start(zone);
        Instant to = p.end(zone);
        Map<LocalDate, Double> refunds = q.itemScoped() ? null : orders.dailyRefunds(q.scope(), from, to, zone);
        List<Series.Point> series = Series.of(orders.dailyOrders(q.scope(), from, to, zone), refunds, p,
                q.granularity(), q.basis());
        List<ProductRevenue> now = sales.byProduct(q.scope(), from, to);

        Period other = q.comparePeriod();
        List<Series.Point> previousSeries = null;
        List<ProductRevenue> before = null;
        if (other != null) {
            Instant of = other.start(zone);
            Instant ot = other.end(zone);
            previousSeries = Series.of(orders.dailyOrders(q.scope(), of, ot, zone),
                    q.itemScoped() ? null : orders.dailyRefunds(q.scope(), of, ot, zone), other, q.granularity(),
                    q.basis());
            before = sales.byProduct(q.scope(), of, ot);
        }
        Set<String> ids = new HashSet<>();
        now.forEach(r -> ids.add(r.productId()));
        if (before != null) {
            before.forEach(r -> ids.add(r.productId()));
        }
        double refunded = refunds == null ? 0 : refunds.values().stream().mapToDouble(Double::doubleValue).sum();
        return new SalesSection(Window.of(q, zone), q.basis().name().toLowerCase(Locale.ROOT), series,
                previousSeries, SalesSection.byCategory(now, before, sales.categories(ids)),
                q.itemScoped() ? null : SalesSection.breakdown(sales.breakdown(q.scope(), from, to), refunded),
                q.itemScoped() ? null : SalesSection.methods(sales.byPaymentMethod(q.scope(), from, to)));
    }
}
```

In `AnalyticsController` add `SalesService sales` to the fields and constructor, and:

```java
    @GetMapping("/sales")
    ResponseEntity<?> sales(@Valid SectionRequest req) {
        return respond("sales", req, sales::section);
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='SalesSectionIntegrationTest,SalesSectionTest'`
Expected: PASS.

- [ ] **Step 9: Commit the backend**

```bash
git add backend/src
git commit -m "feat(analytics): sales section by category, payment method and breakdown

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 10: Write the failing frontend test for `alignPrevious`**

Append to `ecommerce-frontend/src/components/admin/analytics/sections.test.ts` (and add `alignPrevious` to its import):

```ts
describe("alignPrevious", () => {
  const point = (start: string, revenue: number) => ({ start, revenue, orders: 0 });

  it("lines the comparison up by position and pads with zeros", () => {
    expect(alignPrevious([point("2026-10-01", 1), point("2026-10-02", 2)], [point("2026-09-29", 5)])).toEqual([5, 0]);
  });

  it("is empty when there is no comparison", () => {
    expect(alignPrevious([point("2026-10-01", 1)], null)).toEqual([]);
  });
});
```

Run: `cd ecommerce-frontend && npm test -- src/components/admin/analytics/sections.test.ts`
Expected: FAIL — `alignPrevious` is not exported.

- [ ] **Step 11: Add `alignPrevious`, the type, the hook and the tab**

In `sections.ts`:

```ts
import { SectionName, SeriesPoint } from "../../../types/analytics";

/** The comparison period's revenue at the same position as each current bucket. */
export const alignPrevious = (current: SeriesPoint[], previous?: SeriesPoint[] | null): number[] =>
  previous ? current.map((_, i) => previous[i]?.revenue ?? 0) : [];
```

In `types/analytics.ts`:

```ts
export type SalesSection = SectionWindow & {
  basis: "net" | "gross";
  series: SeriesPoint[];
  previousSeries?: SeriesPoint[] | null;
  byCategory: { category: string; revenue: number; units: number; share: number; change?: number | null }[];
  breakdown?: {
    charged: number; discount: number; tax: number; shipping: number; refunds: number; net: number;
    couponOrders: number; couponOrderShare: number;
  } | null;
  byPaymentMethod?: { method: string; orders: number; revenue: number; share: number }[] | null;
};
```

In `dashboardAPI.ts` add `getSales` (same shape as `getOverview`, url `"sales"`, type `SectionResponse<SalesSection>`) and export `useGetSalesQuery`.

`ecommerce-frontend/src/components/admin/analytics/SalesTab.tsx`:

```tsx
import { useGetSalesQuery } from "../../../redux/api/dashboardAPI";
import { SectionParams } from "../../../types/analytics";
import { formatMoney } from "../../../utils/features";
import { BarChart, DoughnutChart } from "../Charts";
import { chartColors as c } from "../chartTheme";
import { COMPARE_LABEL, alignPrevious, bucketLabel } from "./sections";
import { ChangeBadge, CsvButton, SectionState } from "./Shared";

const SalesTab = ({ params }: { params: SectionParams }) => {
  const { data, isLoading, isError } = useGetSalesQuery(params);
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const against = COMPARE_LABEL[params.compare];
  const previous = alignPrevious(s.series, s.previousSeries);
  const b = s.breakdown;

  return (
    <>
      <div className="analytics__actions"><CsvButton section="sales" params={params} /></div>
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide">
          <h4>{s.basis === "net" ? "Net revenue" : "Gross revenue"} by {s.granularity}</h4>
          <BarChart data_1={s.series.map((p) => p.revenue)} data_2={previous}
                    title_1="This period" title_2={s.previousSeries ? "Comparison period" : ""}
                    bgColor_1={c.series[0]} bgColor_2={c.series[2]}
                    labels={s.series.map((p) => bucketLabel(p.start, s.granularity))} />
        </div>
        <div className="card chart-card">
          <h4>Revenue by category</h4>
          <ul className="category-bars">
            {s.byCategory.map((row) => (
              <li key={row.category}>
                <span>{row.category}</span>
                <div><i style={{ width: `${row.share}%` }} /></div>
                <b>{formatMoney(row.revenue)}</b>
                <ChangeBadge change={row.change} against={against} />
              </li>
            ))}
            {s.byCategory.length === 0 && <li>No sales in this period</li>}
          </ul>
        </div>
        {b && (
          <div className="card chart-card">
            <h4>Where the money went</h4>
            <table className="mini-table">
              <tbody>
                <tr><th scope="row">Charged to customers</th><td>{formatMoney(b.charged)}</td></tr>
                <tr><th scope="row">…of which tax</th><td>{formatMoney(b.tax)}</td></tr>
                <tr><th scope="row">…of which shipping</th><td>{formatMoney(b.shipping)}</td></tr>
                <tr><th scope="row">Discounts given</th><td>{formatMoney(b.discount)}</td></tr>
                <tr><th scope="row">Refunds</th><td>{formatMoney(b.refunds)}</td></tr>
                <tr><th scope="row">Net revenue</th><td><b>{formatMoney(b.net)}</b></td></tr>
                <tr><th scope="row">Orders with a coupon</th><td>{b.couponOrders} ({b.couponOrderShare}%)</td></tr>
              </tbody>
            </table>
          </div>
        )}
        {s.byPaymentMethod && s.byPaymentMethod.length > 0 && (
          <div className="card chart-card">
            <h4>Payment methods</h4>
            <DoughnutChart labels={s.byPaymentMethod.map((m) => m.method.toUpperCase())}
                           data={s.byPaymentMethod.map((m) => m.orders)}
                           backgroundColor={s.byPaymentMethod.map((_, i) => c.series[i % c.series.length])}
                           cutout="72%" />
          </div>
        )}
        {!b && <p className="analytics__note">Breakdown and payment methods cover whole orders, so they are hidden while a category is selected.</p>}
      </section>
    </>
  );
};

export default SalesTab;
```

In `analytics.tsx` add `{tab === "sales" && <SalesTab params={section} />}` inside `analytics__body` and import `SalesTab`.

- [ ] **Step 12: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all pass.

- [ ] **Step 13: Commit the frontend**

```bash
git add ecommerce-frontend/src
git commit -m "feat(analytics): sales tab

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: `stuckOrderDays` store setting

**Files:**
- Modify: `backend/src/main/java/com/sixpack/shared/tenant/StoreSettings.java` (`Commerce`)
- Modify: `backend/src/main/java/com/sixpack/shared/tenant/StoreConfig.java` (`Effective`)
- Modify: `backend/src/main/java/com/sixpack/tenant/application/StoreDefaults.java`, `StoreConfigService.java`, `StoreSettingsService.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/sixpack/it/StoreSettingsIntegrationTest.java`
- Modify: `ecommerce-frontend/src/redux/api/storeAPI.ts`, `ecommerce-frontend/src/pages/admin/storeSettingsForm.ts`, `storeSettingsForm.test.ts`, `ecommerce-frontend/src/pages/admin/settings.tsx`

**Interfaces:**
- Produces: `StoreSettings.Commerce#stuckOrderDays(): Integer` (null = platform default), `StoreConfig.Effective#stuckOrderDays(): int`, `StoreDefaults#stuckOrderDays()` (default 3, valid 1–60), audit field `commerce.stuckOrderDays`, request/response field `commerce.stuckOrderDays` on `/api/v1/store/settings`.

- [ ] **Step 1: Write the failing integration tests**

Add to `StoreSettingsIntegrationTest`:

```java
    @Test
    void stuckOrderDaysFollowsTheStoreWithAPlatformDefaultOfThree() throws Exception {
        getSettings(acme, adminA).andExpect(jsonPath("$.effective.stuckOrderDays").value(3))
                .andExpect(jsonPath("$.defaults.stuckOrderDays").value(3));
        saveSettings(acme, adminA, Map.of("commerce", Map.of("stuckOrderDays", 7))).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.commerce.stuckOrderDays").value(7))
                .andExpect(jsonPath("$.effective.stuckOrderDays").value(7));
        getSettings(globex, adminB).andExpect(jsonPath("$.effective.stuckOrderDays").value(3));
    }

    @Test
    void stuckOrderDaysMustBeBetweenOneAndSixty() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("stuckOrderDays", 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("commerce.stuckOrderDays: must be between 1 and 60"));
        saveSettings(acme, adminA, Map.of("commerce", Map.of("stuckOrderDays", 61))).andExpect(status().isBadRequest());
    }
```

And in `aChangeIsRecordedInTheStoresOwnAuditLog` — leave it; the audit diff is generic over `FIELDS`, and the next step adds the field there.

Run: `cd backend && mvn -q test -Dtest=StoreSettingsIntegrationTest`
Expected: the two new tests FAIL (`stuckOrderDays` missing from the JSON).

- [ ] **Step 2: Add the field end to end**

`StoreSettings.Commerce` — add the component last and update `NONE`:

```java
     * @param stuckOrderDays    an order still Processing after this many days is flagged on Analytics → Orders
     */
    public record Commerce(Double shippingFee, Double freeShippingAbove, Double defaultTaxRate, Double maxCouponAmount,
                           Integer minimumAge, Integer stuckOrderDays) {
        public static final Commerce NONE = new Commerce(null, null, null, null, null, null);
    }
```

`StoreConfig.Effective` — add `int stuckOrderDays` as the last component.

`StoreDefaults` — add `Integer stuckOrderDays` as the last component and normalise it in the compact constructor:

```java
        stuckOrderDays = stuckOrderDays == null || stuckOrderDays < 1 || stuckOrderDays > 60 ? 3 : stuckOrderDays;
```

`StoreConfigService.resolve` — pass `or(c.stuckOrderDays(), d.stuckOrderDays())` as the last `Effective` argument.

`StoreSettingsService`:
- in `validCommerce`: `range("commerce.stuckOrderDays", c.stuckOrderDays(), 1, 60);`
- in `FIELDS`: add `"commerce.stuckOrderDays"` after `"commerce.minimumAge"`
- in `auditView`: `v.put("commerce.stuckOrderDays", s.commerce().stuckOrderDays());` after the minimum-age line

`application.yml` under `app.store.defaults`, after `minimum-age`:

```yaml
      # An order still Processing after this many days is flagged as stuck on Analytics → Orders.
      stuck-order-days: ${STORE_STUCK_ORDER_DAYS:3}
```

Then fix every other constructor call the compiler reports (`mvn -q compile` lists them; pass `null` for the new argument in tests that build `Commerce` directly).

- [ ] **Step 3: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest=StoreSettingsIntegrationTest`
Expected: PASS (all, including the two new ones).

- [ ] **Step 4: Update the frontend form test first**

In `storeSettingsForm.test.ts` change the two expected `commerce` objects to include `stuckOrderDays: null`, and add:

```ts
  it("round-trips the stuck-order days", () => {
    const payload = toPayload({ ...emptyForm, stuckOrderDays: "7" });
    expect(payload.commerce.stuckOrderDays).toBe(7);
  });
```

Run: `cd ecommerce-frontend && npm test -- src/pages/admin/storeSettingsForm.test.ts`
Expected: FAIL — `stuckOrderDays` missing.

- [ ] **Step 5: Add the field to the form**

- `storeAPI.ts`: `commerce` gets `stuckOrderDays: Maybe<number>`; `EffectiveStoreSettings` gets `stuckOrderDays: number`.
- `storeSettingsForm.ts`: `emptyForm` gets `stuckOrderDays: ""`; `toPayload` commerce gets `stuckOrderDays: num(f.stuckOrderDays)`; `fromSettings` gets `stuckOrderDays: show(s.commerce.stuckOrderDays)`.
- `settings.tsx`: in the "Coupons and sign-up" section, rename the heading to "Coupons, sign-up and orders" and add after the minimum-age input:

```tsx
              {number("stuckOrderDays", "Flag orders still processing after (days)", `Default: ${dflt?.stuckOrderDays}`, { min: 1, max: 60 })}
```

- [ ] **Step 6: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src ecommerce-frontend/src
git commit -m "feat(store): per-store days before an order counts as stuck

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Orders section

**Files:**
- Create: `backend/src/main/java/com/sixpack/dashboard/domain/Percentiles.java`, `OrderReads.java`, `OrdersSection.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoOrderReads.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/application/OrdersService.java`
- Modify: `backend/src/main/java/com/sixpack/dashboard/web/AnalyticsController.java`
- Test: `backend/src/test/java/com/sixpack/dashboard/domain/PercentilesTest.java`, `OrdersSectionTest.java`, `backend/src/test/java/com/sixpack/it/OrdersSectionIntegrationTest.java`
- Create: `ecommerce-frontend/src/components/admin/analytics/OrdersTab.tsx`
- Modify: `ecommerce-frontend/src/types/analytics.ts`, `redux/api/dashboardAPI.ts`, `components/admin/analytics/sections.ts` (+ test), `pages/admin/analytics.tsx`

**Interfaces:**
- Consumes: Task 1 plumbing, Task 4 `StoreConfig.Effective#stuckOrderDays()`, `DashboardReadPort#dailyOrders/dailyRefunds`, `Series.of`.
- Produces:
  - `Percentiles.of(List<Double> values, double p): Double` (nearest rank; null when empty)
  - `interface OrderReads` with `record OrderFacts(String id, String orderNumber, Instant placedAt, String status, int items, double total, double discount, String paymentMethod, String couponCode, Instant shippedAt, Instant deliveredAt, boolean refunded)`; `orders(Scope, Instant, Instant, int max)` newest first; `stuck(Scope, Instant placedBefore, int limit)` oldest first; `stuckCount(Scope, Instant placedBefore)`.
  - `record OrdersSection(Window window, List<Series.Point> series, List<Series.Point> previousSeries, Map<String,Long> byStatus, Fulfilment fulfilment, List<StuckOrder> stuck, long stuckCount, int stuckAfterDays, Size size, Refunds refunds, boolean truncated, List<OrderFacts> rows /*JSON-ignored*/)` with nested `Leg`, `TimePoint`, `Fulfilment`, `StuckOrder`, `CouponUse`, `Size`, `Refunds`; statics `byStatus`, `fulfilment`, `size`, `refunds`, `stuck`, `hours`; constants `CSV_MAX_ROWS = 10_000`, `FACTS_MAX = 50_000`.
  - `OrdersService#section(SectionQuery)`; `GET /api/v1/dashboard/orders`.
  - Frontend: `OrdersSection` type, `useGetOrdersSectionQuery`, `formatHours(h?: number | null): string`, and `alignPrevious` gains an optional `pick` argument.

- [ ] **Step 1: Write the failing `Percentiles` test**

`backend/src/test/java/com/sixpack/dashboard/domain/PercentilesTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PercentilesTest {

    @Test
    void nearestRank() {
        List<Double> v = List.of(48.0, 24.0, 72.0, 12.0);
        assertThat(Percentiles.of(v, 50)).isEqualTo(24.0);
        assertThat(Percentiles.of(v, 90)).isEqualTo(72.0);
        assertThat(Percentiles.of(List.of(5.0), 90)).isEqualTo(5.0);
    }

    @Test
    void nothingToMeasureIsNull() {
        assertThat(Percentiles.of(List.of(), 50)).isNull();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=PercentilesTest` — Expected: compilation failure.

- [ ] **Step 2: Write `Percentiles`**

```java
package com.sixpack.dashboard.domain;

import java.util.List;

/** Percentiles by nearest rank: always one of the measured values, never an interpolation. */
public final class Percentiles {

    private Percentiles() {
    }

    /** @param p percent in (0, 100]; @return null when there are no values */
    public static Double of(List<Double> values, double p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p / 100 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }
}
```

Run: `cd backend && mvn -q test -Dtest=PercentilesTest` — Expected: PASS.

- [ ] **Step 3: Write the failing `OrdersSection` test**

`backend/src/test/java/com/sixpack/dashboard/domain/OrdersSectionTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.OrderReads.OrderFacts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrdersSectionTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Period OCT = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

    private static OrderFacts order(String status, int items, String placed, String shipped, String delivered,
                                    String coupon, double discount, boolean refunded) {
        return new OrderFacts("id-" + placed, "ORD-" + placed, Instant.parse(placed), status, items, 100, discount,
                "upi", coupon, shipped == null ? null : Instant.parse(shipped),
                delivered == null ? null : Instant.parse(delivered), refunded);
    }

    private static final List<OrderFacts> FACTS = List.of(
            order("Delivered", 3, "2026-10-02T04:30:00Z", "2026-10-03T04:30:00Z", "2026-10-05T04:30:00Z",
                    "diwali10", 50, false),
            order("Shipped", 1, "2026-10-04T04:30:00Z", "2026-10-06T04:30:00Z", null, null, 0, true),
            order("Delivered", 1, "2026-10-06T04:30:00Z", null, null, null, 0, false),
            order("Processing", 6, "2026-10-20T04:30:00Z", null, null, "DIWALI10", 60, false));

    @Test
    void countsByStatusWithEveryKnownStatusPresent() {
        assertThat(OrdersSection.byStatus(FACTS)).containsEntry("processing", 1L).containsEntry("shipped", 1L)
                .containsEntry("delivered", 2L);
        assertThat(OrdersSection.byStatus(List.of())).containsEntry("processing", 0L).hasSize(3);
    }

    @Test
    void fulfilmentTimesFromStatusHistory() {
        OrdersSection.Fulfilment f = OrdersSection.fulfilment(FACTS, OCT, Granularity.WEEK, IST);
        assertThat(f.placedToShipped().medianHours()).isEqualTo(24.0);
        assertThat(f.placedToShipped().p90Hours()).isEqualTo(48.0);
        assertThat(f.placedToShipped().count()).isEqualTo(2);
        assertThat(f.shippedToDelivered().medianHours()).isEqualTo(48.0);
        assertThat(f.placedToDelivered().medianHours()).isEqualTo(72.0);
        assertThat(f.withoutHistory()).isEqualTo(1);
        assertThat(f.deliveryTrend()).hasSize(Granularity.WEEK.buckets(OCT).size());
        assertThat(f.deliveryTrend().get(0).medianHours()).isEqualTo(72.0); // week of 28 Sep holds 2 Oct
        assertThat(f.deliveryTrend().get(1).medianHours()).isNull();
    }

    @Test
    void orderSizeBandsAndCouponsIgnoringCase() {
        OrdersSection.Size s = OrdersSection.size(FACTS, 10);
        assertThat(s.averageItems()).isEqualTo(2.75);
        assertThat(s.bands()).containsExactly(java.util.Map.entry("1", 2L), java.util.Map.entry("2-3", 1L),
                java.util.Map.entry("4-5", 0L), java.util.Map.entry("6+", 1L));
        assertThat(s.topCoupons()).containsExactly(new OrdersSection.CouponUse("DIWALI10", 2, 110));
    }

    @Test
    void refundRateIsRefundedOrdersOverOrders() {
        OrdersSection.Refunds r = OrdersSection.refunds(FACTS, 100);
        assertThat(r.orders()).isEqualTo(1);
        assertThat(r.amount()).isEqualTo(100);
        assertThat(r.rate()).isEqualTo(25.0);
    }

    @Test
    void stuckOrdersCarryTheirAgeInDays() {
        List<OrdersSection.StuckOrder> stuck = OrdersSection.stuck(List.of(FACTS.get(3)),
                Instant.parse("2026-10-30T04:30:00Z"));
        assertThat(stuck.get(0).ageDays()).isEqualTo(10);
    }

    @Test
    void emptyPeriodHasNoNaN() {
        assertThat(OrdersSection.size(List.of(), 10).averageItems()).isZero();
        assertThat(OrdersSection.refunds(List.of(), 0).rate()).isZero();
        OrdersSection.Fulfilment f = OrdersSection.fulfilment(List.of(), OCT, Granularity.DAY, IST);
        assertThat(f.placedToDelivered().medianHours()).isNull();
        assertThat(f.placedToDelivered().count()).isZero();
    }

    @Test
    void hoursAreRoundedToOneDecimal() {
        assertThat(OrdersSection.hours(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T01:20:00Z")))
                .isEqualTo(1.3);
    }
}
```

Run: `cd backend && mvn -q test -Dtest=OrdersSectionTest` — Expected: compilation failure.

- [ ] **Step 4: Write `OrderReads` and `OrdersSection`**

`backend/src/main/java/com/sixpack/dashboard/domain/OrderReads.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.util.List;

/** Read side of the Orders tab. */
public interface OrderReads {

    /**
     * One order, flattened for analysis.
     *
     * @param shippedAt   first time it was marked Shipped; null when never, or when it has no status history
     * @param deliveredAt first time it was marked Delivered; same rule
     * @param refunded    at least one refund that did not fail
     */
    record OrderFacts(String id, String orderNumber, Instant placedAt, String status, int items, double total,
                      double discount, String paymentMethod, String couponCode, Instant shippedAt,
                      Instant deliveredAt, boolean refunded) {
    }

    /** Orders placed in [from, to), newest first, at most {@code max}. */
    List<OrderFacts> orders(Scope scope, Instant from, Instant to, int max);

    /** Orders still Processing that were placed before {@code placedBefore}, oldest first. */
    List<OrderFacts> stuck(Scope scope, Instant placedBefore, int limit);

    long stuckCount(Scope scope, Instant placedBefore);
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/OrdersSection.java`:

```java
package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.OrderReads.OrderFacts;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Are orders flowing and getting delivered? {@code refunds} is null when narrowed to a category. {@code truncated}
 * means the period held more than {@link #FACTS_MAX} orders and the figures cover the newest ones.
 */
public record OrdersSection(@JsonUnwrapped Window window, List<Series.Point> series, List<Series.Point> previousSeries,
                            Map<String, Long> byStatus, Fulfilment fulfilment, List<StuckOrder> stuck,
                            long stuckCount, int stuckAfterDays, Size size, Refunds refunds, boolean truncated,
                            @JsonIgnore List<OrderFacts> rows) implements Section {

    public static final int CSV_MAX_ROWS = 10_000;
    public static final int FACTS_MAX = 50_000;

    public record Leg(Double medianHours, Double p90Hours, long count) {
    }

    public record TimePoint(LocalDate start, Double medianHours) {
    }

    /** @param withoutHistory shipped or delivered orders with no status history, left out of the timings */
    public record Fulfilment(Leg placedToShipped, Leg shippedToDelivered, Leg placedToDelivered, long withoutHistory,
                             List<TimePoint> deliveryTrend) {
    }

    public record StuckOrder(String id, String orderNumber, Instant placedAt, long ageDays, double total) {
    }

    public record CouponUse(String code, long orders, double discount) {
    }

    public record Size(double averageItems, Map<String, Long> bands, List<CouponUse> topCoupons) {
    }

    /** @param rate refunded orders as a percent of orders placed */
    public record Refunds(long orders, double amount, double rate) {
    }

    public static Map<String, Long> byStatus(List<OrderFacts> facts) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("processing", 0L);
        out.put("shipped", 0L);
        out.put("delivered", 0L);
        for (OrderFacts f : facts) {
            String key = f.status() == null ? "unknown" : f.status().toLowerCase(Locale.ROOT);
            out.merge(key, 1L, Long::sum);
        }
        return out;
    }

    public static Fulfilment fulfilment(List<OrderFacts> facts, Period period, Granularity g, ZoneId zone) {
        Leg toShip = leg(facts, f -> f.shippedAt() != null, f -> hours(f.placedAt(), f.shippedAt()));
        Leg shipToDeliver = leg(facts, f -> f.shippedAt() != null && f.deliveredAt() != null,
                f -> hours(f.shippedAt(), f.deliveredAt()));
        Leg toDeliver = leg(facts, f -> f.deliveredAt() != null, f -> hours(f.placedAt(), f.deliveredAt()));
        long withoutHistory = facts.stream().filter(f -> f.shippedAt() == null && f.deliveredAt() == null
                && f.status() != null && !f.status().equalsIgnoreCase("processing")).count();
        Map<LocalDate, List<Double>> byBucket = new LinkedHashMap<>();
        for (LocalDate b : g.buckets(period)) {
            byBucket.put(b, new ArrayList<>());
        }
        for (OrderFacts f : facts) {
            if (f.deliveredAt() != null) {
                List<Double> bucket = byBucket.get(g.bucketStart(f.placedAt().atZone(zone).toLocalDate()));
                if (bucket != null) {
                    bucket.add(hours(f.placedAt(), f.deliveredAt()));
                }
            }
        }
        List<TimePoint> trend = byBucket.entrySet().stream()
                .map(e -> new TimePoint(e.getKey(), Percentiles.of(e.getValue(), 50))).toList();
        return new Fulfilment(toShip, shipToDeliver, toDeliver, withoutHistory, trend);
    }

    public static Size size(List<OrderFacts> facts, int limit) {
        Map<String, Long> bands = new LinkedHashMap<>();
        bands.put("1", 0L);
        bands.put("2-3", 0L);
        bands.put("4-5", 0L);
        bands.put("6+", 0L);
        long items = 0;
        Map<String, CouponUse> coupons = new LinkedHashMap<>();
        for (OrderFacts f : facts) {
            items += f.items();
            String band = f.items() <= 1 ? "1" : f.items() <= 3 ? "2-3" : f.items() <= 5 ? "4-5" : "6+";
            bands.merge(band, 1L, Long::sum);
            if (f.couponCode() != null && !f.couponCode().isBlank()) {
                String code = f.couponCode().trim().toUpperCase(Locale.ROOT);
                CouponUse was = coupons.getOrDefault(code, new CouponUse(code, 0, 0));
                coupons.put(code, new CouponUse(code, was.orders() + 1, was.discount() + f.discount()));
            }
        }
        List<CouponUse> top = coupons.values().stream()
                .sorted(Comparator.comparingLong(CouponUse::orders).reversed().thenComparing(CouponUse::code))
                .limit(limit).toList();
        return new Size(facts.isEmpty() ? 0 : Change.round2((double) items / facts.size()), bands, top);
    }

    public static Refunds refunds(List<OrderFacts> facts, double amount) {
        long refunded = facts.stream().filter(OrderFacts::refunded).count();
        return new Refunds(refunded, amount, Change.share(refunded, facts.size()));
    }

    public static List<StuckOrder> stuck(List<OrderFacts> facts, Instant now) {
        return facts.stream().map(f -> new StuckOrder(f.id(), f.orderNumber(), f.placedAt(),
                Duration.between(f.placedAt(), now).toDays(), f.total())).toList();
    }

    /** Hours between two instants, one decimal. */
    public static double hours(Instant from, Instant to) {
        return Math.round(Duration.between(from, to).toMinutes() / 6.0) / 10.0;
    }

    private static Leg leg(List<OrderFacts> facts, Predicate<OrderFacts> has, Function<OrderFacts, Double> hours) {
        List<Double> values = facts.stream().filter(has).map(hours).toList();
        return new Leg(Percentiles.of(values, 50), Percentiles.of(values, 90), values.size());
    }

    @Override
    public List<String> csvHeader() {
        return List.of("orderNumber", "placedAt", "status", "items", "total", "discount", "paymentMethod",
                "hoursToShip", "hoursToDeliver");
    }

    @Override
    public List<List<Object>> csvRows() {
        List<List<Object>> out = new ArrayList<>();
        for (OrderFacts f : rows.subList(0, Math.min(rows.size(), CSV_MAX_ROWS))) {
            out.add(Arrays.asList(f.orderNumber() != null ? f.orderNumber() : f.id(), f.placedAt(), f.status(),
                    f.items(), f.total(), f.discount(), f.paymentMethod(),
                    f.shippedAt() == null ? null : hours(f.placedAt(), f.shippedAt()),
                    f.deliveredAt() == null ? null : hours(f.placedAt(), f.deliveredAt())));
        }
        if (rows.size() > CSV_MAX_ROWS || truncated) {
            out.add(Arrays.asList("(first " + Math.min(rows.size(), CSV_MAX_ROWS)
                    + " orders only; narrow the date range for the rest)"));
        }
        return out;
    }
}
```

- [ ] **Step 5: Run the domain tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='OrdersSectionTest,PercentilesTest'`
Expected: PASS.

- [ ] **Step 6: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/OrdersSectionIntegrationTest.java`:

```java
package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.history;
import static com.sixpack.it.AnalyticsSeed.item;
import static com.sixpack.it.AnalyticsSeed.refund;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class OrdersSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private static final String BAG = AnalyticsSeed.newId();
    private String admin;
    private AnalyticsSeed seed;
    private String ancient;

    @BeforeEach
    void seedOrders() throws Exception {
        admin = adminToken();
        seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.saveProduct(seed.product(SHOE, "Shoe", "footwear", 500, 10));
        seed.saveProduct(seed.product(BAG, "Bag", "bags", 100, 10));
        seed.save(seed.order("2026-10-02T04:30:00Z", 950, item(SHOE, 500, 1), item(BAG, 100, 2))
                .append("orderNumber", "ORD-1").append("status", "Delivered").append("discount", 50.0)
                .append("couponCode", "diwali10")
                .append("statusHistory", List.of(history("Processing", "2026-10-02T04:30:00Z"),
                        history("Shipped", "2026-10-03T04:30:00Z"), history("Delivered", "2026-10-05T04:30:00Z"))));
        seed.save(seed.order("2026-10-04T04:30:00Z", 500, item(SHOE, 500, 1))
                .append("orderNumber", "ORD-2").append("status", "Shipped")
                .append("statusHistory", List.of(history("Shipped", "2026-10-06T04:30:00Z")))
                .append("payment", new Document("status", "PAID")
                        .append("refunds", List.of(refund(100, "processed", "2026-10-08T06:00:00Z")))));
        seed.save(seed.order("2026-10-06T04:30:00Z", 300, item(BAG, 300, 1))
                .append("orderNumber", "ORD-3").append("status", "Delivered").append("payment", null)); // legacy
        seed.save(seed.order("2026-10-20T04:30:00Z", 600, item(BAG, 100, 6))
                .append("orderNumber", "ORD-4").append("couponCode", "DIWALI10").append("discount", 60.0));
        ancient = seed.save(seed.order("2020-01-01T04:30:00Z", 100, item(BAG, 100, 1))
                .append("orderNumber", "ORD-0")).toHexString();
    }

    private MockHttpServletRequestBuilder orders() {
        return get("/api/v1/dashboard/orders").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31").param("compare", "none");
    }

    @Test
    void statusFulfilmentSizeAndRefunds() throws Exception {
        mvc.perform(orders())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.byStatus.processing").value(1))
                .andExpect(jsonPath("$.section.byStatus.shipped").value(1))
                .andExpect(jsonPath("$.section.byStatus.delivered").value(2))
                .andExpect(jsonPath("$.section.fulfilment.placedToShipped.medianHours").value(24.0))
                .andExpect(jsonPath("$.section.fulfilment.placedToShipped.p90Hours").value(48.0))
                .andExpect(jsonPath("$.section.fulfilment.shippedToDelivered.medianHours").value(48.0))
                .andExpect(jsonPath("$.section.fulfilment.placedToDelivered.medianHours").value(72.0))
                .andExpect(jsonPath("$.section.fulfilment.withoutHistory").value(1))
                .andExpect(jsonPath("$.section.size.averageItems").value(2.75))
                .andExpect(jsonPath("$.section.size.bands['6+']").value(1))
                .andExpect(jsonPath("$.section.size.topCoupons[0].code").value("DIWALI10"))
                .andExpect(jsonPath("$.section.size.topCoupons[0].orders").value(2))
                .andExpect(jsonPath("$.section.refunds.orders").value(1))
                .andExpect(jsonPath("$.section.refunds.amount").value(100.0))
                .andExpect(jsonPath("$.section.refunds.rate").value(25.0))
                .andExpect(jsonPath("$.section.truncated").value(false))
                .andExpect(jsonPath("$.section.rows").doesNotExist());
    }

    @Test
    void stuckOrdersFollowTheStoreSetting() throws Exception {
        String recent = seed.save(seed.order(Instant.now().minus(Duration.ofDays(10)).toString(), 100,
                item(BAG, 100, 1))).toHexString();
        mvc.perform(orders())
                .andExpect(jsonPath("$.section.stuckAfterDays").value(3))
                .andExpect(jsonPath("$.section.stuck[0].id").value(ancient))
                .andExpect(jsonPath("$.section.stuck[*].id", hasItem(recent)));
        try {
            stuckOrderDays(60);
            mvc.perform(orders())
                    .andExpect(jsonPath("$.section.stuckAfterDays").value(60))
                    .andExpect(jsonPath("$.section.stuck[*].id", not(hasItem(recent))));
        } finally {
            stuckOrderDays(null);
        }
    }

    @Test
    void categoryFilterHidesRefunds() throws Exception {
        mvc.perform(orders().param("category", "footwear"))
                .andExpect(jsonPath("$.section.refunds").doesNotExist())
                .andExpect(jsonPath("$.section.byStatus.delivered").value(1));
    }

    @Test
    void exportsOneRowPerOrderNewestFirst() throws Exception {
        mvc.perform(orders().param("format", "csv"))
                .andExpect(content().string(startsWith(
                        "orderNumber,placedAt,status,items,total,discount,paymentMethod,hoursToShip,hoursToDeliver\r\n"
                                + "ORD-4,2026-10-20T04:30:00Z,Processing,6,600,60,,,\r\n")));
    }

    private void stuckOrderDays(Integer days) throws Exception {
        Map<String, Object> commerce = new HashMap<>();
        commerce.put("stuckOrderDays", days);
        mvc.perform(put("/api/v1/store/settings").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("commerce", commerce))))
                .andExpect(status().isOk());
    }
}
```

Run: `cd backend && mvn -q test -Dtest=OrdersSectionIntegrationTest`
Expected: FAIL — route not found.

- [ ] **Step 7: Write the adapter, service and route**

`backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoOrderReads.java`:

```java
package com.sixpack.dashboard.infra.mongo;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.OrderReads;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoOrderReads implements OrderReads {

    private static final String PROCESSING = "Processing";

    private final OrderQueries q;

    MongoOrderReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<OrderFacts> orders(Scope scope, Instant from, Instant to, int max) {
        Query query = Query.query(q.criteria(Dataset.ORDERS, scope, from, to))
                .with(Sort.by(Sort.Direction.DESC, OrderQueries.CREATED_AT)).limit(max);
        return find(query);
    }

    @Override
    public List<OrderFacts> stuck(Scope scope, Instant placedBefore, int limit) {
        Query query = Query.query(stuckCriteria(scope, placedBefore))
                .with(Sort.by(Sort.Direction.ASC, OrderQueries.CREATED_AT)).limit(limit);
        return find(query);
    }

    @Override
    public long stuckCount(Scope scope, Instant placedBefore) {
        return q.mongo().count(Query.query(stuckCriteria(scope, placedBefore)), OrderQueries.ORDERS);
    }

    private Criteria stuckCriteria(Scope scope, Instant placedBefore) {
        return q.criteria(Dataset.ORDERS, scope, null, placedBefore).and("status").is(PROCESSING);
    }

    private List<OrderFacts> find(Query query) {
        query.fields().include("orderNumber", "createdAt", "status", "orderItems.quantity", "total", "discount",
                "payment.method", "payment.refunds.status", "couponCode", "statusHistory");
        return q.mongo().find(query, Document.class, OrderQueries.ORDERS).stream().map(MongoOrderReads::facts)
                .toList();
    }

    private static OrderFacts facts(Document d) {
        int items = d.getList("orderItems", Document.class, List.of()).stream()
                .mapToInt(i -> (int) OrderQueries.number(i.get("quantity"))).sum();
        Document payment = d.get("payment", Document.class);
        boolean refunded = payment != null && payment.getList("refunds", Document.class, List.of()).stream()
                .anyMatch(r -> !"failed".equalsIgnoreCase(r.getString("status")));
        Date placed = d.getDate("createdAt");
        return new OrderFacts(OrderQueries.hex(d.get("_id")), d.getString("orderNumber"),
                placed == null ? null : placed.toInstant(), d.getString("status"), items,
                OrderQueries.number(d.get("total")), OrderQueries.number(d.get("discount")),
                payment == null ? null : payment.getString("method"), d.getString("couponCode"),
                firstAt(d, "Shipped"), firstAt(d, "Delivered"), refunded);
    }

    private static Instant firstAt(Document d, String status) {
        for (Document e : d.getList("statusHistory", Document.class, List.of())) {
            if (status.equalsIgnoreCase(e.getString("status")) && e.getDate("at") != null) {
                return e.getDate("at").toInstant();
            }
        }
        return null;
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/application/OrdersService.java`:

```java
package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.Basis;
import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.OrderReads;
import com.sixpack.dashboard.domain.OrderReads.OrderFacts;
import com.sixpack.dashboard.domain.OrdersSection;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Series;
import com.sixpack.dashboard.domain.Window;
import com.sixpack.shared.tenant.StoreConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Orders tab. */
@Service
public class OrdersService {

    private final DashboardReadPort orders;
    private final OrderReads read;
    private final StoreTime time;
    private final StoreConfig store;
    private final Clock clock;

    @Autowired
    OrdersService(DashboardReadPort orders, OrderReads read, StoreTime time, StoreConfig store) {
        this(orders, read, time, store, Clock.systemUTC());
    }

    OrdersService(DashboardReadPort orders, OrderReads read, StoreTime time, StoreConfig store, Clock clock) {
        this.orders = orders;
        this.read = read;
        this.time = time;
        this.store = store;
        this.clock = clock;
    }

    /** Zone and stuck-order days: a change to either is never answered from the cache. */
    public String cacheTag() {
        return time.zone().getId() + ":" + store.effective().stuckOrderDays();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'orders:' + #root.target.cacheTag() + #q")
    public OrdersSection section(SectionQuery q) {
        ZoneId zone = time.zone();
        Period p = q.period();
        Instant from = p.start(zone);
        Instant to = p.end(zone);
        List<Series.Point> series = Series.of(orders.dailyOrders(q.scope(), from, to, zone), null, p,
                q.granularity(), Basis.GROSS);
        Period other = q.comparePeriod();
        List<Series.Point> previous = other == null ? null : Series.of(orders.dailyOrders(q.scope(),
                other.start(zone), other.end(zone), zone), null, other, q.granularity(), Basis.GROSS);

        List<OrderFacts> facts = read.orders(q.scope(), from, to, OrdersSection.FACTS_MAX + 1);
        boolean truncated = facts.size() > OrdersSection.FACTS_MAX;
        if (truncated) {
            facts = facts.subList(0, OrdersSection.FACTS_MAX);
        }
        int stuckDays = store.effective().stuckOrderDays();
        Instant now = clock.instant();
        Instant placedBefore = now.minus(Duration.ofDays(stuckDays));
        OrdersSection.Refunds refunds = q.itemScoped() ? null : OrdersSection.refunds(facts,
                orders.dailyRefunds(q.scope(), from, to, zone).values().stream().mapToDouble(Double::doubleValue)
                        .sum());
        return new OrdersSection(Window.of(q, zone), series, previous, OrdersSection.byStatus(facts),
                OrdersSection.fulfilment(facts, p, q.granularity(), zone),
                OrdersSection.stuck(read.stuck(q.scope(), placedBefore, q.limit()), now),
                read.stuckCount(q.scope(), placedBefore), stuckDays, OrdersSection.size(facts, q.limit()), refunds,
                truncated, facts);
    }
}
```

In `AnalyticsController` add `OrdersService ordersSection` (field + constructor) and:

```java
    @GetMapping("/orders")
    ResponseEntity<?> orders(@Valid SectionRequest req) {
        return respond("orders", req, ordersSection::section);
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='OrdersSectionIntegrationTest,OrdersSectionTest,PercentilesTest'`
Expected: PASS.

- [ ] **Step 9: Commit the backend**

```bash
git add backend/src
git commit -m "feat(analytics): orders section with fulfilment times and stuck orders

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 10: Write the failing frontend tests**

Append to `sections.test.ts` (add `formatHours` to the import):

```ts
describe("formatHours", () => {
  it("shows hours under two days and days after", () => {
    expect(formatHours(null)).toBe("—");
    expect(formatHours(5)).toBe("5 h");
    expect(formatHours(30)).toBe("30 h");
    expect(formatHours(72)).toBe("3 days");
    expect(formatHours(60)).toBe("2.5 days");
  });
});

describe("alignPrevious with a picker", () => {
  it("can line up orders instead of revenue", () => {
    const p = (orders: number) => ({ start: "2026-10-01", revenue: 0, orders });
    expect(alignPrevious([p(1), p(2)], [p(7)], (x) => x.orders)).toEqual([7, 0]);
  });
});
```

Run: `cd ecommerce-frontend && npm test -- src/components/admin/analytics/sections.test.ts`
Expected: FAIL.

- [ ] **Step 11: Implement the helpers, type, hook and tab**

In `sections.ts` replace `alignPrevious` and add `formatHours`:

```ts
/** The comparison period's value at the same position as each current bucket. */
export const alignPrevious = (
  current: SeriesPoint[], previous?: SeriesPoint[] | null, pick: (p: SeriesPoint) => number = (p) => p.revenue,
): number[] => (previous ? current.map((_, i) => (previous[i] ? pick(previous[i]) : 0)) : []);

export const formatHours = (h?: number | null) => {
  if (h == null) return "—";
  if (h < 48) return `${Math.round(h)} h`;
  return `${(h / 24).toFixed(1).replace(/\.0$/, "")} days`;
};
```

In `types/analytics.ts`:

```ts
type Leg = { medianHours?: number | null; p90Hours?: number | null; count: number };

export type OrdersSection = SectionWindow & {
  series: SeriesPoint[];
  previousSeries?: SeriesPoint[] | null;
  byStatus: Record<string, number>;
  fulfilment: {
    placedToShipped: Leg; shippedToDelivered: Leg; placedToDelivered: Leg; withoutHistory: number;
    deliveryTrend: { start: string; medianHours?: number | null }[];
  };
  stuck: { id: string; orderNumber?: string | null; placedAt: string; ageDays: number; total: number }[];
  stuckCount: number;
  stuckAfterDays: number;
  size: { averageItems: number; bands: Record<string, number>; topCoupons: { code: string; orders: number; discount: number }[] };
  refunds?: { orders: number; amount: number; rate: number } | null;
  truncated: boolean;
};
```

In `dashboardAPI.ts` add `getOrdersSection` (url `"orders"`, `SectionResponse<OrdersSection>`) and export `useGetOrdersSectionQuery`.

`ecommerce-frontend/src/components/admin/analytics/OrdersTab.tsx`:

```tsx
import { Link } from "react-router-dom";
import { useGetOrdersSectionQuery } from "../../../redux/api/dashboardAPI";
import { SectionParams } from "../../../types/analytics";
import { formatDate, formatMoney } from "../../../utils/features";
import { BarChart, DoughnutChart, LineChart } from "../Charts";
import { chartColors as c } from "../chartTheme";
import { alignPrevious, bucketLabel, formatHours } from "./sections";
import { CsvButton, Kpi, SectionState } from "./Shared";

const OrdersTab = ({ params }: { params: SectionParams }) => {
  const { data, isLoading, isError } = useGetOrdersSectionQuery(params);
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const f = s.fulfilment;
  const labels = s.series.map((p) => bucketLabel(p.start, s.granularity));

  return (
    <>
      <div className="analytics__actions"><CsvButton section="orders" params={params} /></div>
      {s.truncated && <p className="analytics__note">This range has more than 50,000 orders; figures cover the newest 50,000.</p>}
      <section className="kpi-row">
        <Kpi label="Orders" value={String(s.series.reduce((n, p) => n + p.orders, 0))} />
        <Kpi label="Median time to deliver" value={formatHours(f.placedToDelivered.medianHours)} />
        <Kpi label={`Stuck over ${s.stuckAfterDays} days`} value={String(s.stuckCount)} />
        {s.refunds && <Kpi label="Refund rate" value={`${s.refunds.rate}%`} />}
      </section>
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide">
          <h4>Orders by {s.granularity}</h4>
          <BarChart data_1={s.series.map((p) => p.orders)} data_2={alignPrevious(s.series, s.previousSeries, (p) => p.orders)}
                    title_1="This period" title_2={s.previousSeries ? "Comparison period" : ""}
                    bgColor_1={c.series[1]} bgColor_2={c.series[2]} labels={labels} />
        </div>
        <div className="card chart-card">
          <h4>Status of orders placed</h4>
          <DoughnutChart labels={["Processing", "Shipped", "Delivered"]}
                         data={[s.byStatus.processing ?? 0, s.byStatus.shipped ?? 0, s.byStatus.delivered ?? 0]}
                         backgroundColor={[c.warning, c.info, c.success]} cutout="72%" />
        </div>
        <div className="card chart-card">
          <h4>How long orders take</h4>
          <table className="mini-table">
            <thead><tr><th>Step</th><th>Median</th><th>Slowest 10%</th><th>Orders</th></tr></thead>
            <tbody>
              {([["Placed → shipped", f.placedToShipped], ["Shipped → delivered", f.shippedToDelivered],
                 ["Placed → delivered", f.placedToDelivered]] as const).map(([label, leg]) => (
                <tr key={label}><td>{label}</td><td>{formatHours(leg.medianHours)}</td>
                  <td>{formatHours(leg.p90Hours)}</td><td>{leg.count}</td></tr>
              ))}
            </tbody>
          </table>
          {f.withoutHistory > 0 && <p className="analytics__note">{f.withoutHistory} older orders have no status history and are left out.</p>}
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Median hours from order to delivery</h4>
          <LineChart data={f.deliveryTrend.map((p) => p.medianHours ?? 0)} label="Hours"
                     labels={f.deliveryTrend.map((p) => bucketLabel(p.start, s.granularity))}
                     backgroundColor={c.fill(c.accent)} borderColor={c.accent} />
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Stuck orders (processing for more than {s.stuckAfterDays} days)</h4>
          {s.stuck.length === 0 ? <p className="data-table__empty">Nothing is stuck.</p> : (
            <table className="mini-table">
              <thead><tr><th>Order</th><th>Placed</th><th>Days waiting</th><th>Total</th></tr></thead>
              <tbody>
                {s.stuck.map((o) => (
                  <tr key={o.id}>
                    <td><Link to={`/admin/transaction/${o.id}`}>{o.orderNumber ?? o.id.slice(-8).toUpperCase()}</Link></td>
                    <td>{formatDate(o.placedAt)}</td><td>{o.ageDays}</td><td>{formatMoney(o.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {s.stuckCount > s.stuck.length && <p className="analytics__note">…and {s.stuckCount - s.stuck.length} more.</p>}
        </div>
        <div className="card chart-card">
          <h4>Items per order (average {s.size.averageItems})</h4>
          <ul className="category-bars">
            {Object.entries(s.size.bands).map(([band, n]) => {
              const total = Object.values(s.size.bands).reduce((a, b) => a + b, 0);
              return (
                <li key={band}><span>{band}</span><div><i style={{ width: `${total ? (n / total) * 100 : 0}%` }} /></div><b>{n}</b></li>
              );
            })}
          </ul>
        </div>
        <div className="card chart-card">
          <h4>Most used coupons</h4>
          {s.size.topCoupons.length === 0 ? <p className="data-table__empty">No coupons used.</p> : (
            <table className="mini-table">
              <thead><tr><th>Code</th><th>Orders</th><th>Discount</th></tr></thead>
              <tbody>{s.size.topCoupons.map((cp) => (
                <tr key={cp.code}><td>{cp.code}</td><td>{cp.orders}</td><td>{formatMoney(cp.discount)}</td></tr>
              ))}</tbody>
            </table>
          )}
        </div>
      </section>
    </>
  );
};

export default OrdersTab;
```

In `analytics.tsx` add `{tab === "orders" && <OrdersTab params={section} />}`.

- [ ] **Step 12: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all pass.

- [ ] **Step 13: Commit the frontend**

```bash
git add ecommerce-frontend/src
git commit -m "feat(analytics): orders tab

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: Stock section

**Files:**
- Create: `backend/src/main/java/com/sixpack/dashboard/domain/StockCover.java`, `StockReads.java`, `StockSection.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoStockReads.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/application/StockService.java`
- Modify: `backend/src/main/java/com/sixpack/dashboard/web/AnalyticsController.java`
- Test: `backend/src/test/java/com/sixpack/dashboard/domain/StockSectionTest.java`, `backend/src/test/java/com/sixpack/it/StockSectionIntegrationTest.java`
- Create: `ecommerce-frontend/src/components/admin/analytics/StockTab.tsx`
- Modify: `ecommerce-frontend/src/types/analytics.ts`, `redux/api/dashboardAPI.ts`, `components/admin/analytics/OverviewTab.tsx` (remove the interim low-stock card), `pages/admin/analytics.tsx`

**Interfaces:**
- Consumes: Task 1 plumbing; `StoreConfig.Effective#lowStockThreshold()`; `Period.lastDays(LocalDate, int)`.
- Produces:
  - `StockCover.WINDOW_DAYS = 30`, `StockCover.unitsPerDay(long unitsSold): double`, `StockCover.daysLeft(int stock, double unitsPerDay): Double` (0 when out, null when nothing sold).
  - `interface StockReads` with `record LineKey(String productId, String variantId)`, `record StockLine(String productId, String variantId, String name, String label, String sku, String category, int stock, double price, Integer threshold)` + `key()`; methods `lines(Scope)`, `unitsSold(Scope, Instant, Instant): Map<LineKey, Long>`, `lastSold(Collection<String> productIds): Map<LineKey, Instant>`.
  - `record StockSection(Window window, Counts counts, Value value, List<LineRow> low, List<OutRow> out, List<LineRow> all /*JSON-ignored*/)` with `rows(lines, sold30, storeThreshold)` and `of(window, rows, lastSold, limit)`.
  - `StockService#section(SectionQuery)`; `GET /api/v1/dashboard/stock`. Task 8 reuses `StockReads` and `StockCover`.

- [ ] **Step 1: Write the failing domain test**

`backend/src/test/java/com/sixpack/dashboard/domain/StockSectionTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.StockReads.LineKey;
import com.sixpack.dashboard.domain.StockReads.StockLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StockSectionTest {

    private static final Window W = new Window(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 20), null, null,
            "Asia/Kolkata", "day");
    private static final List<StockLine> LINES = List.of(
            new StockLine("shoe", null, "Shoe", null, "SH-1", "footwear", 0, 500, null),
            new StockLine("tee", "m", "Tee", "M", "TEE-M", "tops", 2, 300, null),
            new StockLine("tee", "l", "Tee", "L", "TEE-L", "tops", 50, 300, null),
            new StockLine("cap", null, "Cap", null, "CAP", null, 4, 100, 3));
    private static final Map<LineKey, Long> SOLD = Map.of(new LineKey("tee", "m"), 30L, new LineKey("cap", null), 3L);

    @Test
    void daysLeftAtTheLastThirtyDaysRate() {
        assertThat(StockCover.unitsPerDay(30)).isEqualTo(1.0);
        assertThat(StockCover.daysLeft(2, 1.0)).isEqualTo(2.0);
        assertThat(StockCover.daysLeft(4, 0.1)).isEqualTo(40.0);
        assertThat(StockCover.daysLeft(0, 1.0)).isEqualTo(0.0);
        assertThat(StockCover.daysLeft(5, 0)).isNull();
    }

    @Test
    void countsUseEachLinesOwnThresholdElseTheStores() {
        StockSection s = StockSection.of(W, StockSection.rows(LINES, SOLD, 5), Map.of(), 10);
        assertThat(s.counts()).isEqualTo(new StockSection.Counts(4, 2, 1, 1));
        assertThat(s.low()).extracting(StockSection.LineRow::label).containsExactly("M");
        assertThat(s.low().get(0).daysLeft()).isEqualTo(2.0);
        assertThat(s.low().get(0).threshold()).isEqualTo(5);
    }

    @Test
    void valueAtSellingPriceByCategory() {
        StockSection s = StockSection.of(W, StockSection.rows(LINES, SOLD, 5), Map.of(), 10);
        assertThat(s.value().total()).isEqualTo(2 * 300 + 50 * 300 + 4 * 100);
        assertThat(s.value().byCategory().get(0).category()).isEqualTo("tops");
        assertThat(s.value().byCategory().get(1).category()).isEqualTo("uncategorised");
    }

    @Test
    void outOfStockLinesCarryWhenTheyLastSold() {
        Instant sold = Instant.parse("2026-01-01T10:00:00Z");
        StockSection s = StockSection.of(W, StockSection.rows(LINES, SOLD, 5),
                Map.of(new LineKey("shoe", null), sold), 10);
        assertThat(s.out()).hasSize(1);
        assertThat(s.out().get(0).lastSoldAt()).isEqualTo(sold);
    }

    @Test
    void emptyCatalogue() {
        StockSection s = StockSection.of(W, StockSection.rows(List.of(), Map.of(), 5), Map.of(), 10);
        assertThat(s.counts()).isEqualTo(new StockSection.Counts(0, 0, 0, 0));
        assertThat(s.value().total()).isZero();
        assertThat(s.value().byCategory()).isEmpty();
    }

    @Test
    void csvListsEveryLine() {
        StockSection s = StockSection.of(W, StockSection.rows(LINES, SOLD, 5), Map.of(), 1);
        assertThat(s.csvHeader()).containsExactly("name", "variant", "sku", "category", "stock", "threshold", "price",
                "value", "unitsPerDay", "daysLeft");
        assertThat(s.csvRows()).hasSize(4);
    }
}
```

Run: `cd backend && mvn -q test -Dtest=StockSectionTest` — Expected: compilation failure.

- [ ] **Step 2: Write `StockCover`, `StockReads`, `StockSection`**

`backend/src/main/java/com/sixpack/dashboard/domain/StockCover.java`:

```java
package com.sixpack.dashboard.domain;

/** How long stock lasts at the rate it sold over the last {@link #WINDOW_DAYS} days. */
public final class StockCover {

    public static final int WINDOW_DAYS = 30;

    private StockCover() {
    }

    public static double unitsPerDay(long unitsSold) {
        return Change.round2((double) unitsSold / WINDOW_DAYS);
    }

    /** @return 0 when out of stock; null when nothing sold, so there is no rate to go on */
    public static Double daysLeft(int stock, double unitsPerDay) {
        if (stock <= 0) {
            return 0.0;
        }
        if (unitsPerDay <= 0) {
            return null;
        }
        return Math.floor(stock / unitsPerDay * 10) / 10;
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/StockReads.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read side of the Stock tab (and of stock cover on the Demand tab). */
public interface StockReads {

    /** @param variantId null for a product without variants */
    record LineKey(String productId, String variantId) {
    }

    /**
     * A sellable line: a product without variants, or one variant that is not disabled, of a product that is not a
     * draft or archived.
     *
     * @param threshold the product's own low-stock level, null to use the store's
     */
    record StockLine(String productId, String variantId, String name, String label, String sku, String category,
                     int stock, double price, Integer threshold) {

        public LineKey key() {
            return new LineKey(productId, variantId);
        }
    }

    List<StockLine> lines(Scope scope);

    /** Units sold in [from, to) per line. */
    Map<LineKey, Long> unitsSold(Scope scope, Instant from, Instant to);

    /** When each line of these products was last ordered (all time). */
    Map<LineKey, Instant> lastSold(Collection<String> productIds);
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/StockSection.java`:

```java
package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.StockReads.LineKey;
import com.sixpack.dashboard.domain.StockReads.StockLine;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stock as it is now. The window is the last 30 days, the sales that "days left" is based on; there is no
 * comparison. {@code low} and {@code out} are capped at the query's limit; {@code all} feeds the CSV.
 */
public record StockSection(@JsonUnwrapped Window window, Counts counts, Value value, List<LineRow> low,
                           List<OutRow> out, @JsonIgnore List<LineRow> all) implements Section {

    /** @param inStock above its low-stock level; {@code low} at or below it but not out; {@code out} none left */
    public record Counts(long lines, long inStock, long low, long out) {
    }

    public record CategoryValue(String category, double value, double share) {
    }

    /** At selling price; there is no cost price. */
    public record Value(double total, List<CategoryValue> byCategory) {
    }

    public record LineRow(String productId, String variantId, String name, String label, String sku, String category,
                          int stock, int threshold, double price, double value, double unitsPerDay, Double daysLeft) {

        LineKey key() {
            return new LineKey(productId, variantId);
        }
    }

    public record OutRow(String productId, String variantId, String name, String label, String sku,
                         Instant lastSoldAt) {
    }

    public static List<LineRow> rows(List<StockLine> lines, Map<LineKey, Long> sold30, int storeThreshold) {
        return lines.stream().map(l -> {
            double perDay = StockCover.unitsPerDay(sold30.getOrDefault(l.key(), 0L));
            return new LineRow(l.productId(), l.variantId(), l.name(), l.label(), l.sku(),
                    l.category() == null ? SalesSection.UNCATEGORISED : l.category(), l.stock(),
                    l.threshold() != null ? l.threshold() : storeThreshold, l.price(), Math.max(l.stock(), 0) * l.price(),
                    perDay, StockCover.daysLeft(l.stock(), perDay));
        }).toList();
    }

    public static StockSection of(Window window, List<LineRow> rows, Map<LineKey, Instant> lastSold, int limit) {
        long out = rows.stream().filter(r -> r.stock() <= 0).count();
        long low = rows.stream().filter(r -> r.stock() > 0 && r.stock() <= r.threshold()).count();
        Counts counts = new Counts(rows.size(), rows.size() - out - low, low, out);

        double total = rows.stream().mapToDouble(LineRow::value).sum();
        Map<String, Double> byCategory = new LinkedHashMap<>();
        rows.forEach(r -> byCategory.merge(r.category(), r.value(), Double::sum));
        List<CategoryValue> categories = byCategory.entrySet().stream()
                .map(e -> new CategoryValue(e.getKey(), e.getValue(), Change.share(e.getValue(), total)))
                .sorted(Comparator.comparingDouble(CategoryValue::value).reversed()
                        .thenComparing(CategoryValue::category))
                .toList();

        List<LineRow> lowRows = rows.stream().filter(r -> r.stock() > 0 && r.stock() <= r.threshold())
                .sorted(Comparator.comparing(LineRow::daysLeft, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(LineRow::stock).thenComparing(LineRow::name))
                .limit(limit).toList();
        List<OutRow> outRows = rows.stream().filter(r -> r.stock() <= 0)
                .map(r -> new OutRow(r.productId(), r.variantId(), r.name(), r.label(), r.sku(), lastSold.get(r.key())))
                .sorted(Comparator.comparing(OutRow::lastSoldAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(OutRow::name))
                .limit(limit).toList();
        List<LineRow> all = rows.stream().sorted(Comparator.comparing(LineRow::name)
                .thenComparing(r -> Objects.toString(r.label(), ""))).toList();
        return new StockSection(window, counts, new Value(total, categories), lowRows, outRows, all);
    }

    @Override
    public List<String> csvHeader() {
        return List.of("name", "variant", "sku", "category", "stock", "threshold", "price", "value", "unitsPerDay",
                "daysLeft");
    }

    @Override
    public List<List<Object>> csvRows() {
        return all.stream().map(r -> Arrays.<Object>asList(r.name(), r.label(), r.sku(), r.category(), r.stock(),
                r.threshold(), r.price(), r.value(), r.unitsPerDay(), r.daysLeft())).toList();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=StockSectionTest` — Expected: PASS (6 tests).

- [ ] **Step 3: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/StockSectionIntegrationTest.java`:

```java
package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static com.sixpack.it.AnalyticsSeed.variant;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class StockSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private static final String TEE = AnalyticsSeed.newId();
    private static final String CAP = AnalyticsSeed.newId();
    private static final String DRAFT = AnalyticsSeed.newId();
    private String admin;

    @BeforeEach
    void seed() throws Exception {
        admin = adminToken();
        AnalyticsSeed seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.saveProduct(seed.product(SHOE, "Shoe", "footwear", 500, 0));
        seed.saveProduct(seed.product(TEE, "Tee", "tops", 300, 52).append("variants", new Document("items", List.of(
                variant("m", "TEE-M", "M", 300, 2), variant("l", "TEE-L", "L", 300, 50),
                variant("xl", "TEE-XL", "XL", 300, 0).append("status", "DISABLED")))));
        seed.saveProduct(seed.product(CAP, "Cap", "tops", 100, 4).append("lowStockThreshold", 3));
        seed.saveProduct(seed.product(DRAFT, "Draft", "tops", 100, 0).append("status", "DRAFT"));
        String recent = Instant.now().minus(Duration.ofDays(2)).toString();
        seed.save(seed.order(recent, 9000, item(TEE, "m", "M", 300, 30)));
        seed.save(seed.order("2026-01-01T10:00:00Z", 500, item(SHOE, 500, 1)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder stock() {
        return get("/api/v1/dashboard/stock").header("Authorization", bearer(admin));
    }

    @Test
    void countsLowAndOutOfStockLines() throws Exception {
        mvc.perform(stock())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.counts.lines").value(4))
                .andExpect(jsonPath("$.section.counts.inStock").value(2))
                .andExpect(jsonPath("$.section.counts.low").value(1))
                .andExpect(jsonPath("$.section.counts.out").value(1))
                .andExpect(jsonPath("$.section.low[0].label").value("M"))
                .andExpect(jsonPath("$.section.low[0].unitsPerDay").value(1.0))
                .andExpect(jsonPath("$.section.low[0].daysLeft").value(2.0))
                .andExpect(jsonPath("$.section.out[0].name").value("Shoe"))
                .andExpect(jsonPath("$.section.out[0].lastSoldAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.section.value.total").value(2 * 300 + 50 * 300 + 4 * 100.0))
                .andExpect(jsonPath("$.section.compareFrom").doesNotExist());
    }

    @Test
    void theStoresLowStockLevelIsNeverAnsweredFromAStaleCache() throws Exception {
        mvc.perform(stock()).andExpect(jsonPath("$.section.counts.low").value(1));
        try {
            lowStockThreshold(1);
            mvc.perform(stock()).andExpect(jsonPath("$.section.counts.low").value(0));
        } finally {
            lowStockThreshold(null);
        }
    }

    @Test
    void categoryFilterNarrowsTheLines() throws Exception {
        mvc.perform(stock().param("category", "footwear")).andExpect(jsonPath("$.section.counts.lines").value(1));
    }

    @Test
    void exportsEveryLine() throws Exception {
        mvc.perform(stock().param("format", "csv"))
                .andExpect(content().string(startsWith("name,variant,sku,category,stock,threshold,price,value,"
                        + "unitsPerDay,daysLeft\r\nCap,,,tops,4,3,100,400,0,\r\n"))); // no SKU or variant on Cap
    }

    private void lowStockThreshold(Integer level) throws Exception {
        Map<String, Object> catalog = new HashMap<>();
        catalog.put("lowStockThreshold", level);
        mvc.perform(put("/api/v1/store/settings").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("catalog", catalog))))
                .andExpect(status().isOk());
    }
}
```

Run: `cd backend && mvn -q test -Dtest=StockSectionIntegrationTest` — Expected: FAIL (route missing).

- [ ] **Step 4: Write the adapter, service and route**

`backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoStockReads.java`:

```java
package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.dashboard.domain.StockReads;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoStockReads implements StockReads {

    private final OrderQueries q;

    MongoStockReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<StockLine> lines(Scope scope) {
        Criteria c = q.criteria(Dataset.PRODUCTS, scope, null, null).and("status").nin("DRAFT", "ARCHIVED");
        Query query = Query.query(c);
        query.fields().include("name", "sku", "category", "price", "stock", "lowStockThreshold", "variants.items");
        List<StockLine> out = new ArrayList<>();
        for (Document d : q.mongo().find(query, Document.class, OrderQueries.PRODUCTS)) {
            String id = OrderQueries.hex(d.get("_id"));
            Integer threshold = d.get("lowStockThreshold") instanceof Number n ? n.intValue() : null;
            Document variants = d.get("variants", Document.class);
            List<Document> items = variants == null ? List.of() : variants.getList("items", Document.class, List.of());
            if (items.isEmpty()) {
                out.add(new StockLine(id, null, d.getString("name"), null, d.getString("sku"), d.getString("category"),
                        (int) OrderQueries.number(d.get("stock")), OrderQueries.number(d.get("price")), threshold));
                continue;
            }
            for (Document v : items) {
                if (!"DISABLED".equals(v.getString("status"))) {
                    out.add(new StockLine(id, v.getString("variantId"), d.getString("name"), label(v),
                            v.getString("sku"), d.getString("category"), (int) OrderQueries.number(v.get("stock")),
                            OrderQueries.number(v.get("price")), threshold));
                }
            }
        }
        return out;
    }

    @Override
    public Map<LineKey, Long> unitsSold(Scope scope, Instant from, Instant to) {
        List<AggregationOperation> agg = new ArrayList<>();
        agg.add(match(q.criteria(Dataset.ORDERS, scope, from, to)));
        agg.add(OrderQueries.stage("$unwind", "$orderItems"));
        if (scope.productId() != null || scope.category() != null) {
            agg.add(match(q.itemCriteria(scope)));
        }
        agg.add(OrderQueries.stage("$group", new Document("_id", lineKey())
                .append("units", new Document("$sum", OrderQueries.orZero("$orderItems.quantity")))));
        Map<LineKey, Long> out = new HashMap<>();
        for (Document d : q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class)) {
            out.put(key(d), (long) OrderQueries.number(d.get("units")));
        }
        return out;
    }

    @Override
    public Map<LineKey, Instant> lastSold(Collection<String> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Criteria ofProducts = Criteria.where("orderItems.productId")
                .in(productIds.stream().map(OrderQueries::id).toList());
        List<AggregationOperation> agg = List.of(match(ofProducts), OrderQueries.stage("$unwind", "$orderItems"),
                match(ofProducts), OrderQueries.stage("$group", new Document("_id", lineKey())
                        .append("last", new Document("$max", "$createdAt"))));
        Map<LineKey, Instant> out = new HashMap<>();
        for (Document d : q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class)) {
            if (d.get("last") instanceof Date last) {
                out.put(key(d), last.toInstant());
            }
        }
        return out;
    }

    private static Document lineKey() {
        return new Document("p", "$orderItems.productId").append("v", "$orderItems.variantId");
    }

    private static LineKey key(Document group) {
        Document id = group.get("_id", Document.class);
        return new LineKey(OrderQueries.hex(id.get("p")), id.getString("v"));
    }

    private static String label(Document variant) {
        String label = variant.getList("options", Document.class, List.of()).stream()
                .map(o -> o.getString("value")).collect(Collectors.joining(" / "));
        return label.isEmpty() ? null : label;
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/application/StockService.java`:

```java
package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.StockCover;
import com.sixpack.dashboard.domain.StockReads;
import com.sixpack.dashboard.domain.StockSection;
import com.sixpack.dashboard.domain.Window;
import com.sixpack.shared.tenant.StoreConfig;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Stock tab: current stock; the date filters do not apply, the category filter does. */
@Service
public class StockService {

    private final StockReads read;
    private final StoreTime time;
    private final StoreConfig store;

    StockService(StockReads read, StoreTime time, StoreConfig store) {
        this.read = read;
        this.time = time;
        this.store = store;
    }

    public String cacheTag() {
        return time.zone().getId() + ":" + time.today() + ":" + store.effective().lowStockThreshold();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'stock:' + #root.target.cacheTag() + #q.scope() + ':' + #q.limit()")
    public StockSection section(SectionQuery q) {
        ZoneId zone = time.zone();
        Period cover = Period.lastDays(time.today(), StockCover.WINDOW_DAYS);
        List<StockSection.LineRow> rows = StockSection.rows(read.lines(q.scope()),
                read.unitsSold(q.scope(), cover.start(zone), cover.end(zone)), store.effective().lowStockThreshold());
        Set<String> outOfStock = rows.stream().filter(r -> r.stock() <= 0).map(StockSection.LineRow::productId)
                .collect(Collectors.toSet());
        return StockSection.of(new Window(cover.from(), cover.to(), null, null, zone.getId(), "day"), rows,
                read.lastSold(outOfStock), q.limit());
    }
}
```

In `AnalyticsController` add `StockService stock` and:

```java
    @GetMapping("/stock")
    ResponseEntity<?> stock(@Valid SectionRequest req) {
        return respond("stock", req, stock::section);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='StockSectionIntegrationTest,StockSectionTest'`
Expected: PASS. (CSV check: Cap sorts first by name; it sold nothing in 30 days, so `unitsPerDay` is `0` and `daysLeft` is empty.)

- [ ] **Step 6: Commit the backend**

```bash
git add backend/src
git commit -m "feat(analytics): stock section with low stock, days left and stock value

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Add the type, hook and tab; remove the interim card**

`types/analytics.ts`:

```ts
type StockLineRow = {
  productId: string; variantId?: string | null; name: string; label?: string | null; sku?: string | null;
  category: string; stock: number; threshold: number; price: number; value: number; unitsPerDay: number;
  daysLeft?: number | null;
};

export type StockSection = SectionWindow & {
  counts: { lines: number; inStock: number; low: number; out: number };
  value: { total: number; byCategory: { category: string; value: number; share: number }[] };
  low: StockLineRow[];
  out: { productId: string; variantId?: string | null; name: string; label?: string | null; sku?: string | null; lastSoldAt?: string | null }[];
};
```

`dashboardAPI.ts`: add `getStockSection` (url `"stock"`) and export `useGetStockSectionQuery`.

`ecommerce-frontend/src/components/admin/analytics/StockTab.tsx`:

```tsx
import { Link } from "react-router-dom";
import { useGetStockSectionQuery } from "../../../redux/api/dashboardAPI";
import { SectionParams } from "../../../types/analytics";
import { formatDate, formatMoney } from "../../../utils/features";
import { CsvButton, Kpi, SectionState } from "./Shared";

const lineName = (name: string, label?: string | null) => (label ? `${name} — ${label}` : name);

const StockTab = ({ params }: { params: SectionParams }) => {
  const { data, isLoading, isError } = useGetStockSectionQuery(params);
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;

  return (
    <>
      <div className="analytics__actions"><CsvButton section="stock" params={params} /></div>
      <p className="analytics__note">
        Current stock; the date range does not apply. Days left uses sales from {formatDate(s.from)} to {formatDate(s.to)}.
      </p>
      <section className="kpi-row">
        <Kpi label="In stock" value={String(s.counts.inStock)} />
        <Kpi label="Running low" value={String(s.counts.low)} />
        <Kpi label="Out of stock" value={String(s.counts.out)} />
        <Kpi label="Stock value (selling price)" value={formatMoney(s.value.total)} />
      </section>
      <section className="analytics__grid">
        <div className="card chart-card">
          <h4>Stock value by category</h4>
          <ul className="category-bars">
            {s.value.byCategory.map((c) => (
              <li key={c.category}>
                <span>{c.category}</span><div><i style={{ width: `${c.share}%` }} /></div><b>{formatMoney(c.value)}</b>
              </li>
            ))}
            {s.value.byCategory.length === 0 && <li>No products yet</li>}
          </ul>
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Running low (soonest to run out first)</h4>
          {s.low.length === 0 ? <p className="data-table__empty">Everything is above its low-stock level.</p> : (
            <table className="mini-table">
              <thead><tr><th>Product</th><th>SKU</th><th>Left</th><th>Level</th><th>Sold per day</th><th>Days left</th></tr></thead>
              <tbody>
                {s.low.map((r) => (
                  <tr key={`${r.productId}-${r.variantId ?? ""}`}>
                    <td><Link to={`/admin/product/${r.productId}`}>{lineName(r.name, r.label)}</Link></td>
                    <td>{r.sku ?? "—"}</td><td>{r.stock}</td><td>{r.threshold}</td><td>{r.unitsPerDay}</td>
                    <td>{r.daysLeft == null ? "No recent sales" : r.daysLeft}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Out of stock</h4>
          {s.out.length === 0 ? <p className="data-table__empty">Nothing is out of stock.</p> : (
            <table className="mini-table">
              <thead><tr><th>Product</th><th>SKU</th><th>Last sold</th></tr></thead>
              <tbody>
                {s.out.map((r) => (
                  <tr key={`${r.productId}-${r.variantId ?? ""}`}>
                    <td><Link to={`/admin/product/${r.productId}`}>{lineName(r.name, r.label)}</Link></td>
                    <td>{r.sku ?? "—"}</td><td>{r.lastSoldAt ? formatDate(r.lastSoldAt) : "Never"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>
    </>
  );
};

export default StockTab;
```

In `OverviewTab.tsx` delete the low-stock card JSX and its `useLowStockQuery` / `alerts` / `transformImage` lines (the Stock tab replaces it). In `analytics.tsx` add `{tab === "stock" && <StockTab params={section} />}`.

- [ ] **Step 8: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all pass.

- [ ] **Step 9: Commit the frontend**

```bash
git add ecommerce-frontend/src
git commit -m "feat(analytics): stock tab replaces the low-stock card

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Customers section

**Files:**
- Create: `backend/src/main/java/com/sixpack/dashboard/domain/Buckets.java`, `CustomerReads.java`, `CustomersSection.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoCustomerReads.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/application/CustomersService.java`
- Modify: `backend/src/main/java/com/sixpack/dashboard/web/AnalyticsController.java`
- Test: `backend/src/test/java/com/sixpack/dashboard/domain/CustomersSectionTest.java`, `backend/src/test/java/com/sixpack/it/CustomersSectionIntegrationTest.java`
- Create: `ecommerce-frontend/src/components/admin/analytics/CustomersTab.tsx`
- Modify: `ecommerce-frontend/src/types/analytics.ts`, `redux/api/dashboardAPI.ts`, `pages/admin/analytics.tsx`

**Interfaces:**
- Consumes: Task 1 plumbing.
- Produces:
  - `Buckets.counts(Map<LocalDate, Long> days, Period, Granularity): List<Buckets.CountPoint>`; `record CountPoint(LocalDate start, long value)`.
  - `interface CustomerReads` with `record BuyerTotals(String userId, long orders, double spent, Instant lastOrderAt)`, `record RepeatStats(long customers, long repeaters, long orders)`; methods `signups(Instant, Instant, ZoneId): Map<LocalDate, Long>`, `buyers(Scope, Instant, Instant)`, `firstOrderAt(Collection<String>)`, `repeatStats(Scope)`, `signupsWithoutOrders(Instant, Instant)`, `names(Collection<String>)`.
  - `record CustomersSection(Window window, List<Buckets.CountPoint> signups, long signupsTotal, Long signupsChange, Buyers buyers, double repeatRate, double ordersPerCustomer, List<TopCustomer> top, long neverOrdered)`; statics `buyers`, `top`.
  - `CustomersService#section(SectionQuery)`; `GET /api/v1/dashboard/customers`.

- [ ] **Step 1: Write the failing domain test**

`backend/src/test/java/com/sixpack/dashboard/domain/CustomersSectionTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.CustomerReads.BuyerTotals;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomersSectionTest {

    private static final Instant OCT_1 = Instant.parse("2026-09-30T18:30:00Z");
    private static final List<BuyerTotals> BUYERS = List.of(
            new BuyerTotals("asha", 1, 500, Instant.parse("2026-10-05T10:00:00Z")),
            new BuyerTotals("ravi", 1, 1500, Instant.parse("2026-10-06T10:00:00Z")));

    @Test
    void newBuyersPlacedTheirFirstEverOrderInThePeriod() {
        CustomersSection.Buyers b = CustomersSection.buyers(BUYERS,
                Map.of("asha", Instant.parse("2026-10-05T10:00:00Z"), "ravi", Instant.parse("2026-08-01T10:00:00Z")),
                OCT_1);
        assertThat(b).isEqualTo(new CustomersSection.Buyers(1, 1, 500, 1500));
    }

    @Test
    void topCustomersBySpendWithDeletedAccountsNamed() {
        List<CustomersSection.TopCustomer> top = CustomersSection.top(BUYERS, Map.of("ravi", "Ravi"), 10);
        assertThat(top).extracting(CustomersSection.TopCustomer::name).containsExactly("Ravi", "Deleted account");
        assertThat(CustomersSection.top(BUYERS, Map.of(), 1)).hasSize(1);
    }

    @Test
    void signupsAreBucketedAcrossThePeriod() {
        Period oct = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        List<Buckets.CountPoint> weeks = Buckets.counts(Map.of(LocalDate.of(2026, 10, 3), 2L,
                LocalDate.of(2026, 10, 10), 1L), oct, Granularity.WEEK);
        assertThat(weeks.get(0)).isEqualTo(new Buckets.CountPoint(LocalDate.of(2026, 9, 28), 2));
        assertThat(weeks.get(1).value()).isEqualTo(1);
        assertThat(weeks).hasSize(5);
    }

    @Test
    void emptyStore() {
        assertThat(CustomersSection.buyers(List.of(), Map.of(), OCT_1)).isEqualTo(new CustomersSection.Buyers(0, 0, 0, 0));
        assertThat(CustomersSection.top(List.of(), Map.of(), 10)).isEmpty();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=CustomersSectionTest` — Expected: compilation failure.

- [ ] **Step 2: Write `Buckets`, `CustomerReads`, `CustomersSection`**

`backend/src/main/java/com/sixpack/dashboard/domain/Buckets.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Daily counts folded into a period's buckets. */
public final class Buckets {

    public record CountPoint(LocalDate start, long value) {
    }

    private Buckets() {
    }

    public static List<CountPoint> counts(Map<LocalDate, Long> days, Period period, Granularity g) {
        Map<LocalDate, Long> buckets = new LinkedHashMap<>();
        for (LocalDate b : g.buckets(period)) {
            buckets.put(b, 0L);
        }
        days.forEach((day, n) -> buckets.computeIfPresent(g.bucketStart(day), (k, v) -> v + n));
        return buckets.entrySet().stream().map(e -> new CountPoint(e.getKey(), e.getValue())).toList();
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/CustomerReads.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read side of the Customers tab. Customers are accounts with the "user" role. */
public interface CustomerReads {

    /** @param spent whole order totals, also when the view is narrowed to a category */
    record BuyerTotals(String userId, long orders, double spent, Instant lastOrderAt) {
    }

    /** All time: customers with at least one order, of those with two or more, and their orders. */
    record RepeatStats(long customers, long repeaters, long orders) {
    }

    Map<LocalDate, Long> signups(Instant from, Instant to, ZoneId zone);

    List<BuyerTotals> buyers(Scope scope, Instant from, Instant to);

    Map<String, Instant> firstOrderAt(Collection<String> userIds);

    RepeatStats repeatStats(Scope scope);

    /** Accounts created in [from, to) that have never ordered. */
    long signupsWithoutOrders(Instant from, Instant to);

    Map<String, String> names(Collection<String> userIds);
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/CustomersSection.java`:

```java
package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.CustomerReads.BuyerTotals;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Who buys and whether they come back. No gender or age (decided). {@code repeatRate} and
 * {@code ordersPerCustomer} are all time; everything else covers the period.
 */
public record CustomersSection(@JsonUnwrapped Window window, List<Buckets.CountPoint> signups, long signupsTotal,
                               Long signupsChange, Buyers buyers, double repeatRate, double ordersPerCustomer,
                               List<TopCustomer> top, long neverOrdered) implements Section {

    static final String DELETED = "Deleted account";

    /** New buyers placed their first order ever in the period; returning buyers had ordered before. */
    public record Buyers(long newBuyers, long returning, double newRevenue, double returningRevenue) {
    }

    public record TopCustomer(String userId, String name, long orders, double spent, Instant lastOrderAt) {
    }

    public static Buyers buyers(List<BuyerTotals> buyers, Map<String, Instant> firstOrder, Instant periodStart) {
        long fresh = 0;
        long returning = 0;
        double freshRevenue = 0;
        double returningRevenue = 0;
        for (BuyerTotals b : buyers) {
            Instant first = firstOrder.get(b.userId());
            if (first == null || !first.isBefore(periodStart)) {
                fresh++;
                freshRevenue += b.spent();
            } else {
                returning++;
                returningRevenue += b.spent();
            }
        }
        return new Buyers(fresh, returning, freshRevenue, returningRevenue);
    }

    public static List<TopCustomer> top(List<BuyerTotals> buyers, Map<String, String> names, int limit) {
        return buyers.stream()
                .sorted(Comparator.comparingDouble(BuyerTotals::spent).reversed()
                        .thenComparing(BuyerTotals::lastOrderAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(b -> new TopCustomer(b.userId(), names.getOrDefault(b.userId(), DELETED), b.orders(), b.spent(),
                        b.lastOrderAt()))
                .toList();
    }

    @Override
    public List<String> csvHeader() {
        return List.of("name", "orders", "spent", "lastOrderAt");
    }

    @Override
    public List<List<Object>> csvRows() {
        return top.stream().map(t -> List.<Object>of(t.name(), t.orders(), t.spent(), t.lastOrderAt())).toList();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=CustomersSectionTest` — Expected: PASS (4 tests).

- [ ] **Step 3: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/CustomersSectionIntegrationTest.java`:

```java
package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class CustomersSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private String admin;
    private String ravi;

    @BeforeEach
    void seed() throws Exception {
        admin = adminToken();
        AnalyticsSeed seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        String asha = seed.customer("Asha", "2026-10-03T10:00:00Z");
        ravi = seed.customer("Ravi", "2025-01-01T10:00:00Z");
        seed.customer("Meena", "2026-10-10T10:00:00Z"); // signed up, never ordered
        seed.save(seed.order("2026-10-05T10:00:00Z", 500, item(SHOE, 500, 1)).append("user", asha));
        seed.save(seed.order("2026-10-06T10:00:00Z", 1500, item(SHOE, 500, 3)).append("user", ravi));
        seed.save(seed.order("2026-08-01T10:00:00Z", 200, item(SHOE, 200, 1)).append("user", ravi));
    }

    private MockHttpServletRequestBuilder customers() {
        return get("/api/v1/dashboard/customers").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void signupsBuyersAndRepeatRate() throws Exception {
        mvc.perform(customers())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.signupsTotal").value(2))
                .andExpect(jsonPath("$.section.signupsChange").value(100))
                .andExpect(jsonPath("$.section.signups.length()").value(31))
                .andExpect(jsonPath("$.section.buyers.newBuyers").value(1))
                .andExpect(jsonPath("$.section.buyers.returning").value(1))
                .andExpect(jsonPath("$.section.buyers.newRevenue").value(500.0))
                .andExpect(jsonPath("$.section.buyers.returningRevenue").value(1500.0))
                .andExpect(jsonPath("$.section.repeatRate").value(50.0))
                .andExpect(jsonPath("$.section.ordersPerCustomer").value(1.5))
                .andExpect(jsonPath("$.section.neverOrdered").value(1))
                .andExpect(jsonPath("$.section.top[0].userId").value(ravi))
                .andExpect(jsonPath("$.section.top[0].name").value("Ravi"))
                .andExpect(jsonPath("$.section.top[0].orders").value(1));
    }

    @Test
    void exportHasNoContactDetails() throws Exception {
        mvc.perform(customers().param("format", "csv"))
                .andExpect(content().string(
                        "name,orders,spent,lastOrderAt\r\nRavi,1,1500,2026-10-06T10:00:00Z\r\nAsha,1,500,2026-10-05T10:00:00Z\r\n"))
                .andExpect(content().string(not(containsString("@"))));
    }
}
```

Run: `cd backend && mvn -q test -Dtest=CustomersSectionIntegrationTest` — Expected: FAIL (route missing).

- [ ] **Step 4: Write the adapter, service and route**

`backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoCustomerReads.java`:

```java
package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.CustomerReads;
import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoCustomerReads implements CustomerReads {

    private static final String CUSTOMER_ROLE = "user";

    private final OrderQueries q;

    MongoCustomerReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public Map<LocalDate, Long> signups(Instant from, Instant to, ZoneId zone) {
        List<AggregationOperation> agg = List.of(match(createdCustomers(from, to)),
                OrderQueries.stage("$group", new Document("_id", OrderQueries.localDay("$createdAt", zone))
                        .append("n", new Document("$sum", 1))));
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        for (Document d : q.mongo().aggregate(agg, OrderQueries.USERS, Document.class)) {
            out.put(LocalDate.parse(d.getString("_id")), (long) OrderQueries.number(d.get("n")));
        }
        return out;
    }

    @Override
    public List<BuyerTotals> buyers(Scope scope, Instant from, Instant to) {
        List<AggregationOperation> agg = List.of(
                match(q.criteria(Dataset.ORDERS, scope, from, to).and("user").ne(null)),
                OrderQueries.stage("$group", new Document("_id", "$user")
                        .append("orders", new Document("$sum", 1))
                        .append("spent", new Document("$sum", "$total"))
                        .append("last", new Document("$max", "$createdAt"))));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> new BuyerTotals(String.valueOf(d.get("_id")), (long) OrderQueries.number(d.get("orders")),
                        OrderQueries.number(d.get("spent")),
                        d.getDate("last") == null ? null : d.getDate("last").toInstant()))
                .toList();
    }

    @Override
    public Map<String, Instant> firstOrderAt(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<AggregationOperation> agg = List.of(match(Criteria.where("user").in(userIds)),
                OrderQueries.stage("$group", new Document("_id", "$user")
                        .append("first", new Document("$min", "$createdAt"))));
        Map<String, Instant> out = new HashMap<>();
        for (Document d : q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class)) {
            if (d.getDate("first") != null) {
                out.put(String.valueOf(d.get("_id")), d.getDate("first").toInstant());
            }
        }
        return out;
    }

    @Override
    public RepeatStats repeatStats(Scope scope) {
        List<AggregationOperation> agg = List.of(
                match(q.criteria(Dataset.ORDERS, scope, null, null).and("user").ne(null)),
                OrderQueries.stage("$group", new Document("_id", "$user").append("n", new Document("$sum", 1))),
                OrderQueries.stage("$group", new Document("_id", null)
                        .append("customers", new Document("$sum", 1))
                        .append("repeaters", new Document("$sum", new Document("$cond",
                                List.of(new Document("$gte", List.of("$n", 2)), 1, 0))))
                        .append("orders", new Document("$sum", "$n"))));
        Document d = q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getUniqueMappedResult();
        return d == null ? new RepeatStats(0, 0, 0) : new RepeatStats((long) OrderQueries.number(d.get("customers")),
                (long) OrderQueries.number(d.get("repeaters")), (long) OrderQueries.number(d.get("orders")));
    }

    @Override
    public long signupsWithoutOrders(Instant from, Instant to) {
        Query query = Query.query(createdCustomers(from, to));
        query.fields().include("_id");
        List<String> ids = q.mongo().find(query, Document.class, OrderQueries.USERS).stream()
                .map(d -> OrderQueries.hex(d.get("_id"))).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        Set<String> ordered = new HashSet<>();
        List<AggregationOperation> agg = List.of(match(Criteria.where("user").in(ids)),
                OrderQueries.stage("$group", new Document("_id", "$user")));
        q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class)
                .forEach(d -> ordered.add(String.valueOf(d.get("_id"))));
        return ids.stream().filter(id -> !ordered.contains(id)).count();
    }

    @Override
    public Map<String, String> names(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where("_id").in(userIds.stream().map(OrderQueries::id).toList()));
        query.fields().include("name");
        Map<String, String> out = new HashMap<>();
        for (Document d : q.mongo().find(query, Document.class, OrderQueries.USERS)) {
            out.put(OrderQueries.hex(d.get("_id")), d.getString("name"));
        }
        return out;
    }

    private static Criteria createdCustomers(Instant from, Instant to) {
        return Criteria.where("role").is(CUSTOMER_ROLE).and("createdAt").gte(Date.from(from)).lt(Date.from(to));
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/application/CustomersService.java`:

```java
package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.Buckets;
import com.sixpack.dashboard.domain.Change;
import com.sixpack.dashboard.domain.CustomerReads;
import com.sixpack.dashboard.domain.CustomerReads.BuyerTotals;
import com.sixpack.dashboard.domain.CustomersSection;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Window;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Customers tab. Sign-ups ignore the category filter; buyers follow it. */
@Service
public class CustomersService {

    private final CustomerReads read;
    private final StoreTime time;

    CustomersService(CustomerReads read, StoreTime time) {
        this.read = read;
        this.time = time;
    }

    public String cacheTag() {
        return time.zone().getId();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'customers:' + #root.target.cacheTag() + #q")
    public CustomersSection section(SectionQuery q) {
        ZoneId zone = time.zone();
        Period p = q.period();
        Instant from = p.start(zone);
        Instant to = p.end(zone);
        Map<LocalDate, Long> signups = read.signups(from, to, zone);
        long total = signups.values().stream().mapToLong(Long::longValue).sum();
        Period other = q.comparePeriod();
        Long change = other == null ? null : Change.percent(total, (double) read.signups(other.start(zone),
                other.end(zone), zone).values().stream().mapToLong(Long::longValue).sum());

        List<BuyerTotals> buyers = read.buyers(q.scope(), from, to);
        List<String> ids = buyers.stream().map(BuyerTotals::userId).toList();
        CustomerReads.RepeatStats repeat = read.repeatStats(q.scope());
        List<CustomersSection.TopCustomer> top = CustomersSection.top(buyers, read.names(ids), q.limit());
        return new CustomersSection(Window.of(q, zone), Buckets.counts(signups, p, q.granularity()), total, change,
                CustomersSection.buyers(buyers, read.firstOrderAt(ids), from),
                Change.share(repeat.repeaters(), repeat.customers()),
                repeat.customers() == 0 ? 0 : Change.round2((double) repeat.orders() / repeat.customers()), top,
                read.signupsWithoutOrders(from, to));
    }
}
```

(`read.names(ids)` looks up every buyer; at shop scale that is one indexed `_id` query. If it shows up in profiling, look up only the top `limit` ids.)

In `AnalyticsController` add `CustomersService customers` and:

```java
    @GetMapping("/customers")
    ResponseEntity<?> customers(@Valid SectionRequest req) {
        return respond("customers", req, customers::section);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='CustomersSectionIntegrationTest,CustomersSectionTest'`
Expected: PASS.

- [ ] **Step 6: Commit the backend**

```bash
git add backend/src
git commit -m "feat(analytics): customers section with new vs returning buyers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Add the type, hook and tab**

`types/analytics.ts`:

```ts
export type CustomersSection = SectionWindow & {
  signups: { start: string; value: number }[];
  signupsTotal: number;
  signupsChange?: number | null;
  buyers: { newBuyers: number; returning: number; newRevenue: number; returningRevenue: number };
  repeatRate: number;
  ordersPerCustomer: number;
  top: { userId: string; name: string; orders: number; spent: number; lastOrderAt: string }[];
  neverOrdered: number;
};
```

`dashboardAPI.ts`: add `getCustomersSection` (url `"customers"`) and export `useGetCustomersSectionQuery`.

`ecommerce-frontend/src/components/admin/analytics/CustomersTab.tsx`:

```tsx
import { Link } from "react-router-dom";
import { useGetCustomersSectionQuery } from "../../../redux/api/dashboardAPI";
import { SectionParams } from "../../../types/analytics";
import { formatDate, formatMoney } from "../../../utils/features";
import { DoughnutChart, LineChart } from "../Charts";
import { chartColors as c } from "../chartTheme";
import { COMPARE_LABEL, bucketLabel } from "./sections";
import { CsvButton, Kpi, SectionState } from "./Shared";

const CustomersTab = ({ params }: { params: SectionParams }) => {
  const { data, isLoading, isError } = useGetCustomersSectionQuery(params);
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const b = s.buyers;

  return (
    <>
      <div className="analytics__actions"><CsvButton section="customers" params={params} /></div>
      <section className="kpi-row">
        <Kpi label="New accounts" value={String(s.signupsTotal)} change={s.signupsChange} against={COMPARE_LABEL[params.compare]} />
        <Kpi label="First-time buyers" value={String(b.newBuyers)} />
        <Kpi label="Returning buyers" value={String(b.returning)} />
        <Kpi label="Repeat customers (all time)" value={`${s.repeatRate}%`} />
      </section>
      <p className="analytics__note">
        {s.neverOrdered} of the new accounts have not ordered yet · {s.ordersPerCustomer} orders per customer on average (all time)
      </p>
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide">
          <h4>New accounts by {s.granularity}</h4>
          <LineChart data={s.signups.map((p) => p.value)} label="New accounts"
                     labels={s.signups.map((p) => bucketLabel(p.start, s.granularity))}
                     backgroundColor={c.fill(c.series[1])} borderColor={c.series[1]} />
        </div>
        <div className="card chart-card">
          <h4>Revenue from new vs returning buyers</h4>
          <DoughnutChart labels={["First-time", "Returning"]} data={[b.newRevenue, b.returningRevenue]}
                         backgroundColor={[c.series[0], c.series[4]]} cutout="72%" />
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Top customers by spend</h4>
          {s.top.length === 0 ? <p className="data-table__empty">No orders in this period.</p> : (
            <table className="mini-table">
              <thead><tr><th>Customer</th><th>Orders</th><th>Spent</th><th>Last order</th></tr></thead>
              <tbody>
                {s.top.map((t) => (
                  <tr key={t.userId}>
                    <td><Link to={`/admin/customer/${t.userId}`}>{t.name}</Link></td>
                    <td>{t.orders}</td><td>{formatMoney(t.spent)}</td><td>{formatDate(t.lastOrderAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>
    </>
  );
};

export default CustomersTab;
```

In `analytics.tsx` add `{tab === "customers" && <CustomersTab params={section} />}`.

- [ ] **Step 8: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all pass.

- [ ] **Step 9: Commit the frontend**

```bash
git add ecommerce-frontend/src
git commit -m "feat(analytics): customers tab

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 8: Demand section

**Files:**
- Create: `backend/src/main/java/com/sixpack/dashboard/domain/DemandReads.java`, `Trending.java`, `DemandSection.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoDemandReads.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/application/DemandService.java`
- Modify: `backend/src/main/java/com/sixpack/dashboard/web/AnalyticsController.java`
- Test: `backend/src/test/java/com/sixpack/dashboard/domain/DemandSectionTest.java`, `backend/src/test/java/com/sixpack/it/DemandSectionIntegrationTest.java`
- Create: `ecommerce-frontend/src/components/admin/analytics/DemandTab.tsx`
- Modify: `ecommerce-frontend/src/types/analytics.ts`, `redux/api/dashboardAPI.ts`, `pages/admin/analytics.tsx`

**Interfaces:**
- Consumes: Task 6 `StockReads` (`lines`, `unitsSold`, `LineKey`, `StockLine`) and `StockCover`; Task 1 plumbing; `SectionRequest.parse`.
- Produces:
  - `interface DemandReads` with `record LineSales(String productId, String variantId, String name, String label, long units, double revenue, long orders)`, `record PairCount(String productA, String productB, long orders)`; methods `sales(Scope, Instant, Instant, boolean byVariant)`, `names(Collection<String> productIds): Map<String,String>` (existing products only), `pairs(Scope, Instant, Instant, int minOrders, int limit, int maxDistinct)`.
  - `Trending.MIN_UNITS = 3`, `Trending.of(List<LineSales> now, List<LineSales> before, int limit): Trending.Result(List<Row> rising, List<Row> falling)`; `Row(productId, variantId, name, label, long units, long previousUnits, Long change, boolean isNew)`.
  - `DemandSection(Window, String rank, String by, List<TopRow> top, Trending.Result trending, List<IdleRow> notSelling, List<PairRow> boughtTogether, List<TopRow> all /*JSON-ignored*/)`; enums `DemandSection.Rank {UNITS, REVENUE}`, `DemandSection.By {PRODUCT, VARIANT}`; statics `key`, `stockBy`, `soldBy`, `top`, `idle`, `pairs`; constants `PAIR_MIN_ORDERS = 3`, `PAIR_LIMIT = 10`, `PAIR_MAX_DISTINCT = 20`.
  - `DemandService#section(SectionQuery, Rank, By)`; `GET /api/v1/dashboard/demand?rank=units|revenue&by=product|variant`. Task 10 reuses `DemandReads.LineSales`.

- [ ] **Step 1: Write the failing domain test**

`backend/src/test/java/com/sixpack/dashboard/domain/DemandSectionTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.DemandReads.LineSales;
import com.sixpack.dashboard.domain.DemandSection.By;
import com.sixpack.dashboard.domain.DemandSection.Rank;
import com.sixpack.dashboard.domain.StockReads.StockLine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DemandSectionTest {

    private static LineSales sold(String id, long units, double revenue) {
        return new LineSales(id, null, id.toUpperCase(), null, units, revenue, units);
    }

    private static final List<LineSales> NOW = List.of(sold("shoe", 7, 3500), sold("gone", 5, 50),
            sold("bag", 3, 300), sold("tee", 1, 300));
    private static final List<LineSales> BEFORE = List.of(sold("shoe", 1, 500), sold("bag", 6, 600));
    private static final List<StockLine> LINES = List.of(
            new StockLine("shoe", null, "Shoe", null, null, "footwear", 10, 500, null),
            new StockLine("bag", null, "Bag", null, null, "bags", 3, 100, null),
            new StockLine("hat", null, "Hat", null, null, "hats", 20, 50, null),
            new StockLine("tee", "m", "Tee", "M", null, "tops", 5, 300, null),
            new StockLine("tee", "l", "Tee", "L", null, "tops", 0, 300, null));

    private static List<DemandSection.TopRow> top(Rank rank) {
        return DemandSection.top(NOW, BEFORE, rank, Set.of("shoe", "bag", "tee"),
                DemandSection.stockBy(LINES, By.PRODUCT),
                DemandSection.soldBy(Map.of(new StockReads.LineKey("shoe", null), 30L), By.PRODUCT), 30);
    }

    @Test
    void topSellersWithShareChangeAndStockCover() {
        List<DemandSection.TopRow> top = top(Rank.UNITS);
        assertThat(top).extracting(DemandSection.TopRow::productId).containsExactly("shoe", "gone", "bag", "tee");
        DemandSection.TopRow shoe = top.get(0);
        assertThat(shoe.share()).isEqualTo(43.8);
        assertThat(shoe.change()).isEqualTo(600L);
        assertThat(shoe.unitsPerDay()).isEqualTo(1.0);
        assertThat(shoe.daysLeft()).isEqualTo(10.0);
        assertThat(shoe.reorderSoon()).isTrue();
        assertThat(top.get(1).removed()).isTrue();
        assertThat(top.get(1).stock()).isNull();
        assertThat(top.get(2).change()).isEqualTo(-50L);
        assertThat(top.get(3).stock()).isEqualTo(5); // variants summed per product
    }

    @Test
    void rankByRevenue() {
        assertThat(top(Rank.REVENUE)).extracting(DemandSection.TopRow::productId)
                .containsExactly("shoe", "bag", "tee", "gone");
    }

    @Test
    void trendingNeedsThreeUnitsAndFlagsNewProducts() {
        Trending.Result t = Trending.of(NOW, BEFORE, 10);
        assertThat(t.rising()).extracting(Trending.Row::productId).containsExactly("shoe", "gone");
        assertThat(t.rising().get(1).isNew()).isTrue();
        assertThat(t.rising().get(1).change()).isNull();
        assertThat(t.falling()).extracting(Trending.Row::productId).containsExactly("bag");
        assertThat(t.falling().get(0).change()).isEqualTo(-50L);
    }

    @Test
    void notSellingIsStockedProductsWithNoSalesByValue() {
        Set<String> soldKeys = Set.of(DemandSection.key("shoe", null), DemandSection.key("bag", null),
                DemandSection.key("tee", null));
        assertThat(DemandSection.idle(LINES, soldKeys, By.PRODUCT, 10))
                .containsExactly(new DemandSection.IdleRow("hat", null, "Hat", null, 20, 1000));
        assertThat(DemandSection.idle(LINES, Set.of(DemandSection.key("tee", "m")), By.VARIANT, 10))
                .extracting(DemandSection.IdleRow::productId).containsExactly("shoe", "hat", "bag");
    }

    @Test
    void pairsUseProductNamesAndMarkRemovedOnes() {
        assertThat(DemandSection.pairs(List.of(new DemandReads.PairCount("shoe", "gone", 3)), Map.of("shoe", "Shoe")))
                .containsExactly(new DemandSection.PairRow("shoe", "Shoe", "gone", "Removed product", 3));
    }

    @Test
    void emptyPeriod() {
        assertThat(DemandSection.top(List.of(), null, Rank.UNITS, Set.of(), Map.of(), Map.of(), 30)).isEmpty();
        assertThat(Trending.of(List.of(), List.of(), 10).rising()).isEmpty();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=DemandSectionTest` — Expected: compilation failure.

- [ ] **Step 2: Write `DemandReads`, `Trending`, `DemandSection`**

`backend/src/main/java/com/sixpack/dashboard/domain/DemandReads.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read side of the Demand tab. */
public interface DemandReads {

    /**
     * @param variantId null when grouped by product, and for products without variants
     * @param name      as on the newest order, so deleted products still have a name
     */
    record LineSales(String productId, String variantId, String name, String label, long units, double revenue,
                     long orders) {
    }

    record PairCount(String productA, String productB, long orders) {
    }

    /** Units, item revenue and orders per product (or per variant) for orders placed in [from, to). */
    List<LineSales> sales(Scope scope, Instant from, Instant to, boolean byVariant);

    /** Names of the products that still exist; deleted ones are missing. */
    Map<String, String> names(Collection<String> productIds);

    /**
     * Pairs of different products in the same order, with at least {@code minOrders} such orders, most first.
     * Orders with more than {@code maxDistinct} distinct products are skipped to bound the work.
     */
    List<PairCount> pairs(Scope scope, Instant from, Instant to, int minOrders, int limit, int maxDistinct);
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/Trending.java`:

```java
package com.sixpack.dashboard.domain;

import com.sixpack.dashboard.domain.DemandReads.LineSales;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Biggest rises and drops in units against the comparison period. Lines that sold fewer than {@link #MIN_UNITS}
 * in both periods are left out, so 1 → 2 never shows as +100%. Ranked by the change in units, not the percent.
 */
public final class Trending {

    public static final int MIN_UNITS = 3;

    /** @param change null when {@code isNew} (nothing sold before) */
    public record Row(String productId, String variantId, String name, String label, long units, long previousUnits,
                      Long change, boolean isNew) {
    }

    public record Result(List<Row> rising, List<Row> falling) {
    }

    private Trending() {
    }

    public static Result of(List<LineSales> now, List<LineSales> before, int limit) {
        Map<String, LineSales> current = index(now);
        Map<String, LineSales> previous = index(before);
        Set<String> keys = new LinkedHashSet<>(current.keySet());
        keys.addAll(previous.keySet());
        List<Row> rows = new ArrayList<>();
        for (String key : keys) {
            long units = current.containsKey(key) ? current.get(key).units() : 0;
            long was = previous.containsKey(key) ? previous.get(key).units() : 0;
            if (Math.max(units, was) < MIN_UNITS) {
                continue;
            }
            LineSales any = current.getOrDefault(key, previous.get(key));
            boolean isNew = was == 0;
            rows.add(new Row(any.productId(), any.variantId(), any.name(), any.label(), units, was,
                    isNew ? null : MonthBuckets.percentChange(units, was), isNew));
        }
        List<Row> rising = rows.stream().filter(r -> r.units() > r.previousUnits())
                .sorted(Comparator.comparingLong((Row r) -> r.units() - r.previousUnits()).reversed()
                        .thenComparing(Row::name))
                .limit(limit).toList();
        List<Row> falling = rows.stream().filter(r -> r.units() < r.previousUnits())
                .sorted(Comparator.comparingLong((Row r) -> r.previousUnits() - r.units()).reversed()
                        .thenComparing(Row::name))
                .limit(limit).toList();
        return new Result(rising, falling);
    }

    private static Map<String, LineSales> index(List<LineSales> rows) {
        Map<String, LineSales> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put(DemandSection.key(r.productId(), r.variantId()), r));
        return out;
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/DemandSection.java`:

```java
package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.DemandReads.LineSales;
import com.sixpack.dashboard.domain.DemandReads.PairCount;
import com.sixpack.dashboard.domain.StockReads.LineKey;
import com.sixpack.dashboard.domain.StockReads.StockLine;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Which products sell, rise, fall or stall. {@code top} is capped at the query's limit; {@code all} feeds the CSV.
 * Stock cover uses the last 30 days of sales, whatever the date range.
 */
public record DemandSection(@JsonUnwrapped Window window, String rank, String by, List<TopRow> top,
                            Trending.Result trending, List<IdleRow> notSelling, List<PairRow> boughtTogether,
                            @JsonIgnore List<TopRow> all) implements Section {

    public enum Rank { UNITS, REVENUE }

    public enum By { PRODUCT, VARIANT }

    public static final int PAIR_MIN_ORDERS = 3;
    public static final int PAIR_LIMIT = 10;
    public static final int PAIR_MAX_DISTINCT = 20;
    static final String REMOVED = "Removed product";

    /**
     * @param removed     the product no longer exists; its name comes from the order
     * @param stock       null for a removed product
     * @param reorderSoon stock runs out within the comparison window (30 days without one)
     */
    public record TopRow(String productId, String variantId, String name, String label, long units, double revenue,
                         long orders, double share, Long change, boolean removed, Integer stock, double unitsPerDay,
                         Double daysLeft, boolean reorderSoon) {
    }

    public record IdleRow(String productId, String variantId, String name, String label, int stock, double value) {
    }

    public record PairRow(String productA, String nameA, String productB, String nameB, long orders) {
    }

    public static String key(String productId, String variantId) {
        return productId + "|" + (variantId == null ? "" : variantId);
    }

    public static Map<String, Integer> stockBy(List<StockLine> lines, By by) {
        Map<String, Integer> out = new HashMap<>();
        lines.forEach(l -> out.merge(key(l.productId(), by == By.VARIANT ? l.variantId() : null),
                Math.max(l.stock(), 0), Integer::sum));
        return out;
    }

    public static Map<String, Long> soldBy(Map<LineKey, Long> sold, By by) {
        Map<String, Long> out = new HashMap<>();
        sold.forEach((k, units) -> out.merge(key(k.productId(), by == By.VARIANT ? k.variantId() : null), units,
                Long::sum));
        return out;
    }

    /** @param before null when the comparison is off; @param existing ids of products that still exist */
    public static List<TopRow> top(List<LineSales> now, List<LineSales> before, Rank rank, Set<String> existing,
                                   Map<String, Integer> stock, Map<String, Long> sold30, int horizonDays) {
        ToDoubleFunction<LineSales> metric = rank == Rank.UNITS ? s -> s.units() : LineSales::revenue;
        double total = now.stream().mapToDouble(metric).sum();
        Map<String, LineSales> previous = new HashMap<>();
        if (before != null) {
            before.forEach(s -> previous.put(key(s.productId(), s.variantId()), s));
        }
        return now.stream().map(s -> {
            String k = key(s.productId(), s.variantId());
            Integer left = stock.get(k);
            double perDay = StockCover.unitsPerDay(sold30.getOrDefault(k, 0L));
            Double days = left == null ? null : StockCover.daysLeft(left, perDay);
            Long change = before == null ? null : Change.percent(metric.applyAsDouble(s),
                    previous.containsKey(k) ? metric.applyAsDouble(previous.get(k)) : 0.0);
            return new TopRow(s.productId(), s.variantId(), s.name(), s.label(), s.units(), s.revenue(), s.orders(),
                    Change.share(metric.applyAsDouble(s), total), change, !existing.contains(s.productId()), left,
                    perDay, days, days != null && days <= horizonDays);
        }).sorted(Comparator.comparingDouble((TopRow r) -> rank == Rank.UNITS ? r.units() : r.revenue()).reversed()
                .thenComparing(TopRow::name, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    /** Stocked lines (or products) with no sales in the period, most money tied up first. */
    public static List<IdleRow> idle(List<StockLine> lines, Set<String> soldKeys, By by, int limit) {
        Map<String, IdleRow> rows = new LinkedHashMap<>();
        for (StockLine l : lines) {
            String variant = by == By.VARIANT ? l.variantId() : null;
            String k = key(l.productId(), variant);
            if (soldKeys.contains(k)) {
                continue;
            }
            int stock = Math.max(l.stock(), 0);
            IdleRow was = rows.get(k);
            rows.put(k, new IdleRow(l.productId(), variant, l.name(), by == By.VARIANT ? l.label() : null,
                    (was == null ? 0 : was.stock()) + stock, (was == null ? 0 : was.value()) + stock * l.price()));
        }
        return rows.values().stream().filter(r -> r.stock() > 0)
                .sorted(Comparator.comparingDouble(IdleRow::value).reversed().thenComparing(IdleRow::name))
                .limit(limit).toList();
    }

    public static List<PairRow> pairs(List<PairCount> pairs, Map<String, String> names) {
        return pairs.stream().map(p -> new PairRow(p.productA(), names.getOrDefault(p.productA(), REMOVED),
                p.productB(), names.getOrDefault(p.productB(), REMOVED), p.orders())).toList();
    }

    @Override
    public List<String> csvHeader() {
        return List.of("name", "variant", "units", "revenue", "orders", "change", "stock", "daysLeft");
    }

    @Override
    public List<List<Object>> csvRows() {
        return all.stream().map(r -> Arrays.<Object>asList(r.name(), r.label(), r.units(), r.revenue(), r.orders(),
                r.change(), r.stock(), r.daysLeft())).toList();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=DemandSectionTest` — Expected: PASS (6 tests). (Variant idle check: with `tee|m` sold, the idle lines are shoe 10×500 = 5000, hat 20×50 = 1000, bag 3×100 = 300; tee L has no stock.)

- [ ] **Step 3: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/DemandSectionIntegrationTest.java`:

```java
package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static com.sixpack.it.AnalyticsSeed.variant;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class DemandSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private static final String BAG = AnalyticsSeed.newId();
    private static final String HAT = AnalyticsSeed.newId();
    private static final String TEE = AnalyticsSeed.newId();
    private static final String GONE = AnalyticsSeed.newId();
    private String admin;
    private AnalyticsSeed seed;

    @BeforeEach
    void seedSales() throws Exception {
        admin = adminToken();
        seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.saveProduct(seed.product(SHOE, "Shoe", "footwear", 500, 10));
        seed.saveProduct(seed.product(BAG, "Bag", "bags", 100, 3));
        seed.saveProduct(seed.product(HAT, "Hat", "hats", 50, 20));
        seed.saveProduct(seed.product(TEE, "Tee", "tops", 300, 5).append("variants", new Document("items",
                List.of(variant("m", "TEE-M", "M", 300, 5), variant("l", "TEE-L", "L", 300, 0)))));
        seed.save(seed.order("2026-10-05T10:00:00Z", 2100, item(SHOE, 500, 4).append("name", "Shoe"),
                item(BAG, 100, 1).append("name", "Bag")));
        seed.save(seed.order("2026-10-06T10:00:00Z", 1100, item(SHOE, 500, 2).append("name", "Shoe"),
                item(BAG, 100, 1).append("name", "Bag")));
        seed.save(seed.order("2026-10-07T10:00:00Z", 900, item(SHOE, 500, 1).append("name", "Shoe"),
                item(BAG, 100, 1).append("name", "Bag"), item(TEE, "m", "M", 300, 1).append("name", "Tee")));
        seed.save(seed.order("2026-10-08T10:00:00Z", 50, item(GONE, 10, 5).append("name", "Old mug")));
        seed.save(seed.order("2026-09-10T10:00:00Z", 500, item(SHOE, 500, 1).append("name", "Shoe")));
        seed.save(seed.order("2026-09-12T10:00:00Z", 600, item(BAG, 100, 6).append("name", "Bag")));
    }

    private MockHttpServletRequestBuilder demand() {
        return get("/api/v1/dashboard/demand").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void topSellersTrendingAndIdleStock() throws Exception {
        mvc.perform(demand())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.rank").value("units"))
                .andExpect(jsonPath("$.section.top[0].name").value("Shoe"))
                .andExpect(jsonPath("$.section.top[0].units").value(7))
                .andExpect(jsonPath("$.section.top[0].orders").value(3))
                .andExpect(jsonPath("$.section.top[0].change").value(600))
                .andExpect(jsonPath("$.section.top[1].name").value("Old mug"))
                .andExpect(jsonPath("$.section.top[1].removed").value(true))
                .andExpect(jsonPath("$.section.trending.rising[0].name").value("Shoe"))
                .andExpect(jsonPath("$.section.trending.rising[1].isNew").value(true))
                .andExpect(jsonPath("$.section.trending.falling[0].name").value("Bag"))
                .andExpect(jsonPath("$.section.trending.falling[0].change").value(-50))
                .andExpect(jsonPath("$.section.notSelling[0].name").value("Hat"))
                .andExpect(jsonPath("$.section.notSelling[0].value").value(1000.0))
                .andExpect(jsonPath("$.section.boughtTogether.length()").value(1))
                .andExpect(jsonPath("$.section.boughtTogether[0].orders").value(3));
    }

    @Test
    void byVariantAndByRevenue() throws Exception {
        mvc.perform(demand().param("by", "variant"))
                .andExpect(jsonPath("$.section.by").value("variant"))
                .andExpect(jsonPath("$.section.top[?(@.label == 'M')].units", hasItem(1)));
        mvc.perform(demand().param("rank", "revenue"))
                .andExpect(jsonPath("$.section.top[0].revenue").value(3500.0))
                .andExpect(jsonPath("$.section.top[1].name").value("Bag"));
        mvc.perform(demand().param("rank", "sometimes")).andExpect(status().isBadRequest());
    }

    @Test
    void stockCoverUsesTheLastThirtyDays() throws Exception {
        String cup = AnalyticsSeed.newId();
        seed.saveProduct(seed.product(cup, "Cup", "kitchen", 20, 15));
        seed.save(seed.order(Instant.now().minus(Duration.ofDays(2)).toString(), 600,
                item(cup, 20, 30).append("name", "Cup")));
        mvc.perform(get("/api/v1/dashboard/demand").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.section.top[0].name").value("Cup"))
                .andExpect(jsonPath("$.section.top[0].unitsPerDay").value(1.0))
                .andExpect(jsonPath("$.section.top[0].daysLeft").value(15.0))
                .andExpect(jsonPath("$.section.top[0].reorderSoon").value(true));
    }

    @Test
    void exportsEveryProduct() throws Exception {
        mvc.perform(demand().param("format", "csv"))
                .andExpect(content().string(startsWith("name,variant,units,revenue,orders,change,stock,daysLeft\r\n"
                        + "Shoe,,7,3500,3,600,10,")));
    }
}
```

Run: `cd backend && mvn -q test -Dtest=DemandSectionIntegrationTest` — Expected: FAIL (route missing).

- [ ] **Step 4: Write the adapter, service and route**

`backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoDemandReads.java`:

```java
package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.DemandReads;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoDemandReads implements DemandReads {

    private final OrderQueries q;

    MongoDemandReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<LineSales> sales(Scope scope, Instant from, Instant to, boolean byVariant) {
        List<AggregationOperation> agg = new ArrayList<>();
        agg.add(match(q.criteria(Dataset.ORDERS, scope, from, to)));
        agg.add(OrderQueries.stage("$sort", new Document(OrderQueries.CREATED_AT, 1))); // $last = newest name
        agg.add(OrderQueries.stage("$unwind", "$orderItems"));
        if (scope.productId() != null || scope.category() != null) {
            agg.add(match(q.itemCriteria(scope)));
        }
        agg.add(OrderQueries.stage("$group", new Document("_id", new Document("p", "$orderItems.productId")
                .append("v", byVariant ? "$orderItems.variantId" : null))
                .append("name", new Document("$last", "$orderItems.name"))
                .append("label", new Document("$last", "$orderItems.variantLabel"))
                .append("units", new Document("$sum", OrderQueries.orZero("$orderItems.quantity")))
                .append("revenue", new Document("$sum", new Document("$multiply", List.of(
                        OrderQueries.orZero("$orderItems.price"), OrderQueries.orZero("$orderItems.quantity")))))
                .append("orders", new Document("$addToSet", "$_id"))));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> {
                    Document id = d.get("_id", Document.class);
                    return new LineSales(OrderQueries.hex(id.get("p")), byVariant ? id.getString("v") : null,
                            d.getString("name"), byVariant ? d.getString("label") : null,
                            (long) OrderQueries.number(d.get("units")), OrderQueries.number(d.get("revenue")),
                            OrderQueries.orders(d));
                })
                .toList();
    }

    @Override
    public Map<String, String> names(Collection<String> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where("_id").in(productIds.stream().map(OrderQueries::id).toList()));
        query.fields().include("name");
        Map<String, String> out = new HashMap<>();
        for (Document d : q.mongo().find(query, Document.class, OrderQueries.PRODUCTS)) {
            out.put(OrderQueries.hex(d.get("_id")), d.getString("name"));
        }
        return out;
    }

    @Override
    public List<PairCount> pairs(Scope scope, Instant from, Instant to, int minOrders, int limit, int maxDistinct) {
        Document size = new Document("$size", "$p");
        List<AggregationOperation> agg = List.of(
                match(q.criteria(Dataset.ORDERS, scope, from, to)),
                OrderQueries.stage("$project", new Document("p",
                        new Document("$setUnion", List.of(new Document("$ifNull", List.of("$orderItems.productId", List.of())),
                                List.of())))), // orders without items give an empty set, not null
                OrderQueries.stage("$match", new Document("$expr", new Document("$and", List.of(
                        new Document("$gte", List.of(size, 2)), new Document("$lte", List.of(size, maxDistinct)))))),
                OrderQueries.stage("$project", new Document("a", "$p").append("b", "$p")),
                OrderQueries.stage("$unwind", "$a"),
                OrderQueries.stage("$unwind", "$b"),
                OrderQueries.stage("$match", new Document("$expr", new Document("$lt", List.of("$a", "$b")))),
                OrderQueries.stage("$group", new Document("_id", new Document("a", "$a").append("b", "$b"))
                        .append("orders", new Document("$sum", 1))),
                OrderQueries.stage("$match", new Document("orders", new Document("$gte", minOrders))),
                OrderQueries.stage("$sort", new Document("orders", -1).append("_id.a", 1).append("_id.b", 1)),
                OrderQueries.stage("$limit", limit));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> {
                    Document id = d.get("_id", Document.class);
                    return new PairCount(OrderQueries.hex(id.get("a")), OrderQueries.hex(id.get("b")),
                            (long) OrderQueries.number(d.get("orders")));
                })
                .toList();
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/application/DemandService.java`:

```java
package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.DemandReads;
import com.sixpack.dashboard.domain.DemandReads.LineSales;
import com.sixpack.dashboard.domain.DemandReads.PairCount;
import com.sixpack.dashboard.domain.DemandSection;
import com.sixpack.dashboard.domain.DemandSection.By;
import com.sixpack.dashboard.domain.DemandSection.Rank;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.StockCover;
import com.sixpack.dashboard.domain.StockReads;
import com.sixpack.dashboard.domain.StockReads.StockLine;
import com.sixpack.dashboard.domain.Trending;
import com.sixpack.dashboard.domain.Window;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Demand tab. */
@Service
public class DemandService {

    private final DemandReads demand;
    private final StockReads stock;
    private final StoreTime time;

    DemandService(DemandReads demand, StockReads stock, StoreTime time) {
        this.demand = demand;
        this.stock = stock;
        this.time = time;
    }

    public String cacheTag() {
        return time.zone().getId() + ":" + time.today();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'demand:' + #root.target.cacheTag() + #q + #rank + #by")
    public DemandSection section(SectionQuery q, Rank rank, By by) {
        ZoneId zone = time.zone();
        Period p = q.period();
        Instant from = p.start(zone);
        Instant to = p.end(zone);
        boolean byVariant = by == By.VARIANT;
        List<LineSales> now = demand.sales(q.scope(), from, to, byVariant);
        Period other = q.comparePeriod();
        List<LineSales> before = other == null ? null
                : demand.sales(q.scope(), other.start(zone), other.end(zone), byVariant);

        List<StockLine> lines = stock.lines(q.scope());
        Period cover = Period.lastDays(time.today(), StockCover.WINDOW_DAYS);
        Set<String> ids = new HashSet<>();
        now.forEach(s -> ids.add(s.productId()));
        if (before != null) {
            before.forEach(s -> ids.add(s.productId()));
        }
        int horizon = other == null ? StockCover.WINDOW_DAYS : (int) other.days();
        List<DemandSection.TopRow> all = DemandSection.top(now, before, rank, demand.names(ids).keySet(),
                DemandSection.stockBy(lines, by),
                DemandSection.soldBy(stock.unitsSold(q.scope(), cover.start(zone), cover.end(zone)), by), horizon);

        Set<String> soldKeys = now.stream().map(s -> DemandSection.key(s.productId(), s.variantId()))
                .collect(Collectors.toSet());
        List<PairCount> pairs = demand.pairs(q.scope(), from, to, DemandSection.PAIR_MIN_ORDERS,
                DemandSection.PAIR_LIMIT, DemandSection.PAIR_MAX_DISTINCT);
        Set<String> pairIds = new HashSet<>();
        pairs.forEach(pc -> {
            pairIds.add(pc.productA());
            pairIds.add(pc.productB());
        });
        return new DemandSection(Window.of(q, zone), rank.name().toLowerCase(Locale.ROOT),
                by.name().toLowerCase(Locale.ROOT), all.subList(0, Math.min(q.limit(), all.size())),
                before == null ? new Trending.Result(List.of(), List.of()) : Trending.of(now, before, q.limit()),
                DemandSection.idle(lines, soldKeys, by, q.limit()), DemandSection.pairs(pairs, demand.names(pairIds)),
                all);
    }
}
```

In `AnalyticsController` add `DemandService demand` and (imports `RequestParam`, `jakarta.validation.constraints.Size`, `DemandSection`):

```java
    @GetMapping("/demand")
    ResponseEntity<?> demand(@Valid SectionRequest req,
                             @RequestParam(required = false) @Size(max = 10) String rank,
                             @RequestParam(required = false) @Size(max = 10) String by) {
        return respond("demand", req, q -> demand.section(q,
                SectionRequest.parse(DemandSection.Rank.class, rank, DemandSection.Rank.UNITS),
                SectionRequest.parse(DemandSection.By.class, by, DemandSection.By.PRODUCT)));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='DemandSectionIntegrationTest,DemandSectionTest'`
Expected: PASS.

- [ ] **Step 6: Commit the backend**

```bash
git add backend/src
git commit -m "feat(analytics): demand section with trending, idle stock and bought-together pairs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Add the type, hook and tab**

`types/analytics.ts`:

```ts
export type DemandParams = SectionParams & { rank?: "units" | "revenue"; by?: "product" | "variant" };

type TrendRow = {
  productId: string; variantId?: string | null; name: string; label?: string | null; units: number;
  previousUnits: number; change?: number | null; isNew: boolean;
};

export type DemandSection = SectionWindow & {
  rank: "units" | "revenue";
  by: "product" | "variant";
  top: {
    productId: string; variantId?: string | null; name: string; label?: string | null; units: number; revenue: number;
    orders: number; share: number; change?: number | null; removed: boolean; stock?: number | null;
    unitsPerDay: number; daysLeft?: number | null; reorderSoon: boolean;
  }[];
  trending: { rising: TrendRow[]; falling: TrendRow[] };
  notSelling: { productId: string; variantId?: string | null; name: string; label?: string | null; stock: number; value: number }[];
  boughtTogether: { productA: string; nameA: string; productB: string; nameB: string; orders: number }[];
};
```

`dashboardAPI.ts`: add `getDemandSection: builder.query<SectionResponse<DemandSection>, DemandParams>` (url `"demand"`) and export `useGetDemandSectionQuery`.

`ecommerce-frontend/src/components/admin/analytics/DemandTab.tsx`:

```tsx
import { Link, useSearchParams } from "react-router-dom";
import { useGetDemandSectionQuery } from "../../../redux/api/dashboardAPI";
import { DemandParams, SectionParams } from "../../../types/analytics";
import { formatMoney } from "../../../utils/features";
import { COMPARE_LABEL } from "./sections";
import { ChangeBadge, CsvButton, SectionState } from "./Shared";

const named = (name: string, label?: string | null) => (label ? `${name} — ${label}` : name);

const DemandTab = ({ params }: { params: SectionParams }) => {
  const [search, setSearch] = useSearchParams();
  const rank = search.get("rank") === "revenue" ? "revenue" : "units";
  const by = search.get("by") === "variant" ? "variant" : "product";
  const query: DemandParams = { ...params, rank, by };
  const { data, isLoading, isError } = useGetDemandSectionQuery(query);
  const set = (key: string, value: string, fallback: string) => {
    const next = new URLSearchParams(search);
    if (value === fallback) next.delete(key); else next.set(key, value);
    setSearch(next, { replace: true });
  };
  const against = COMPARE_LABEL[params.compare];

  const controls = (
    <div className="analytics__actions">
      <select value={rank} onChange={(e) => set("rank", e.target.value, "units")} aria-label="Rank by">
        <option value="units">Rank by units</option>
        <option value="revenue">Rank by revenue</option>
      </select>
      <select value={by} onChange={(e) => set("by", e.target.value, "product")} aria-label="Group by">
        <option value="product">Per product</option>
        <option value="variant">Per variant</option>
      </select>
      <CsvButton section="demand" params={query} />
    </div>
  );
  if (isLoading) return <>{controls}<SectionState loading /></>;
  if (isError || !data) return <>{controls}<SectionState /></>;
  const s = data.section;

  return (
    <>
      {controls}
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide">
          <h4>Top sellers</h4>
          {s.top.length === 0 ? <p className="data-table__empty">No sales in this period.</p> : (
            <table className="mini-table">
              <thead><tr><th>Product</th><th>Units</th><th>Revenue</th><th>Share</th><th>Change</th><th>Stock</th><th>Days left</th></tr></thead>
              <tbody>
                {s.top.map((r) => (
                  <tr key={`${r.productId}-${r.variantId ?? ""}`}>
                    <td>{r.removed ? <>{named(r.name, r.label)} <small>(removed)</small></>
                      : <Link to={`/admin/product/${r.productId}`}>{named(r.name, r.label)}</Link>}</td>
                    <td>{r.units}</td><td>{formatMoney(r.revenue)}</td><td>{r.share}%</td>
                    <td><ChangeBadge change={r.change} against={against} /></td>
                    <td>{r.stock ?? "—"}</td>
                    <td>{r.daysLeft == null ? "—" : r.daysLeft}{r.reorderSoon && <span className="badge badge--processing"> Reorder soon</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        {(["rising", "falling"] as const).map((dir) => (
          <div className="card chart-card" key={dir}>
            <h4>{dir === "rising" ? "Rising" : "Falling"} {against && <small>({against})</small>}</h4>
            {s.trending[dir].length === 0 ? <p className="data-table__empty">
              {s.compareFrom ? "Nothing moved by 3 or more units." : "Turn on a comparison to see trends."}</p> : (
              <table className="mini-table">
                <thead><tr><th>Product</th><th>Units</th><th>Before</th><th>Change</th></tr></thead>
                <tbody>
                  {s.trending[dir].map((r) => (
                    <tr key={`${r.productId}-${r.variantId ?? ""}`}>
                      <td>{named(r.name, r.label)}</td><td>{r.units}</td><td>{r.previousUnits}</td>
                      <td>{r.isNew ? "New" : `${r.change}%`}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ))}
        <div className="card chart-card">
          <h4>In stock but not selling</h4>
          {s.notSelling.length === 0 ? <p className="data-table__empty">Everything in stock sold at least once.</p> : (
            <table className="mini-table">
              <thead><tr><th>Product</th><th>Stock</th><th>Value</th></tr></thead>
              <tbody>{s.notSelling.map((r) => (
                <tr key={`${r.productId}-${r.variantId ?? ""}`}>
                  <td><Link to={`/admin/product/${r.productId}`}>{named(r.name, r.label)}</Link></td>
                  <td>{r.stock}</td><td>{formatMoney(r.value)}</td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </div>
        <div className="card chart-card">
          <h4>Often bought together</h4>
          {s.boughtTogether.length === 0 ? <p className="data-table__empty">No pair appears in 3 or more orders.</p> : (
            <table className="mini-table">
              <thead><tr><th>Products</th><th>Orders</th></tr></thead>
              <tbody>{s.boughtTogether.map((p) => (
                <tr key={`${p.productA}-${p.productB}`}><td>{p.nameA} + {p.nameB}</td><td>{p.orders}</td></tr>
              ))}</tbody>
            </table>
          )}
        </div>
      </section>
    </>
  );
};

export default DemandTab;
```

In `analytics.tsx` add `{tab === "demand" && <DemandTab params={section} />}`.

- [ ] **Step 8: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all pass.

- [ ] **Step 9: Commit the frontend**

```bash
git add ecommerce-frontend/src
git commit -m "feat(analytics): demand tab

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: `IndianStates` — one list of states and union territories

**Files:**
- Create: `backend/src/main/java/com/sixpack/shared/geo/IndianStates.java`
- Test: `backend/src/test/java/com/sixpack/shared/geo/IndianStatesTest.java`

**Interfaces:**
- Produces (in the OPEN `shared` module, usable from `dashboard`, `order`, `tenant`): `IndianStates.State(String code, String name, boolean unionTerritory)`; `IndianStates.all(): List<State>` (36, sorted by name); `IndianStates.normalise(String raw): Optional<State>`; `IndianStates.byCode(String code): Optional<State>`; `IndianStates.isIndia(String country): boolean`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/sixpack/shared/geo/IndianStatesTest.java`:

```java
package com.sixpack.shared.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IndianStatesTest {

    @Test
    void twentyEightStatesAndEightUnionTerritoriesSortedByName() {
        assertThat(IndianStates.all()).hasSize(36);
        assertThat(IndianStates.all().stream().filter(IndianStates.State::unionTerritory)).hasSize(8);
        assertThat(IndianStates.all().get(0).name()).isEqualTo("Andaman and Nicobar Islands");
        assertThat(IndianStates.all().stream().map(IndianStates.State::code).distinct()).hasSize(36);
    }

    @ParameterizedTest
    @CsvSource({
            "Karnataka, KA", "karnataka , KA", "KA, KA", "ka, KA", "Tamil Nadu, TN", "tamil   nadu, TN",
            "Tamil-Nadu, TN", "Tamilnadu, TN", "Orissa, OD", "OR, OD", "Pondicherry, PY", "Uttaranchal, UK",
            "UT, UK", "CT, CG", "TG, TS", "New Delhi, DL", "NCT of Delhi, DL", "J&K, JK", "Jammu & Kashmir, JK",
            "Daman and Diu, DH", "Dadra and Nagar Haveli, DH", "Andaman & Nicobar, AN"})
    void recognisesNamesCodesAndCommonSpellings(String raw, String code) {
        assertThat(IndianStates.normalise(raw)).map(IndianStates.State::code).contains(code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "Bangalore", "California", "XX"})
    void unknownIsEmpty(String raw) {
        assertThat(IndianStates.normalise(raw)).isEmpty();
    }

    @Test
    void codesLookUpTheOfficialName() {
        assertThat(IndianStates.byCode("ka")).map(IndianStates.State::name).contains("Karnataka");
        assertThat(IndianStates.byCode("ZZ")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"India", "india ", "IN", "IND", "Bharat"})
    void indiaSpelledAnyCommonWay(String country) {
        assertThat(IndianStates.isIndia(country)).isTrue();
    }

    @Test
    void otherCountriesAreNotIndia() {
        assertThat(IndianStates.isIndia("Nepal")).isFalse();
        assertThat(IndianStates.isIndia(null)).isFalse();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=IndianStatesTest` — Expected: compilation failure.

- [ ] **Step 2: Write `IndianStates`**

`backend/src/main/java/com/sixpack/shared/geo/IndianStates.java`:

```java
package com.sixpack.shared.geo;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * India's 28 states and 8 union territories, with the spellings shoppers type. Codes are the current ISO 3166-2:IN
 * subdivision codes; older codes (CT, OR, TG, UT, DN, DD) and old names are accepted as aliases. Matching ignores
 * case, spaces and punctuation, and reads "&" as "and".
 */
public final class IndianStates {

    public record State(String code, String name, boolean unionTerritory) {
    }

    private static final List<State> ALL;
    private static final Map<String, State> BY_KEY = new HashMap<>();
    private static final Map<String, State> BY_CODE = new HashMap<>();
    private static final Set<String> INDIA = Set.of("india", "in", "ind", "bharat", "republicofindia");

    static {
        state("AP", "Andhra Pradesh");
        state("AR", "Arunachal Pradesh");
        state("AS", "Assam");
        state("BR", "Bihar");
        state("CG", "Chhattisgarh", "CT", "Chattisgarh");
        state("GA", "Goa");
        state("GJ", "Gujarat");
        state("HR", "Haryana");
        state("HP", "Himachal Pradesh");
        state("JH", "Jharkhand");
        state("KA", "Karnataka");
        state("KL", "Kerala");
        state("MP", "Madhya Pradesh");
        state("MH", "Maharashtra");
        state("MN", "Manipur");
        state("ML", "Meghalaya");
        state("MZ", "Mizoram");
        state("NL", "Nagaland");
        state("OD", "Odisha", "OR", "Orissa");
        state("PB", "Punjab");
        state("RJ", "Rajasthan");
        state("SK", "Sikkim");
        state("TN", "Tamil Nadu");
        state("TS", "Telangana", "TG");
        state("TR", "Tripura");
        state("UP", "Uttar Pradesh");
        state("UK", "Uttarakhand", "UT", "Uttaranchal");
        state("WB", "West Bengal");
        territory("AN", "Andaman and Nicobar Islands", "Andaman and Nicobar", "Andaman");
        territory("CH", "Chandigarh");
        territory("DH", "Dadra and Nagar Haveli and Daman and Diu", "DN", "DD", "Dadra and Nagar Haveli",
                "Daman and Diu");
        territory("DL", "Delhi", "New Delhi", "NCT of Delhi", "National Capital Territory of Delhi");
        territory("JK", "Jammu and Kashmir", "J and K", "Kashmir");
        territory("LA", "Ladakh");
        territory("LD", "Lakshadweep");
        territory("PY", "Puducherry", "Pondicherry");
        ALL = BY_CODE.values().stream().sorted(Comparator.comparing(State::name)).toList();
    }

    private IndianStates() {
    }

    public static List<State> all() {
        return ALL;
    }

    public static Optional<State> normalise(String raw) {
        return raw == null ? Optional.empty() : Optional.ofNullable(BY_KEY.get(key(raw)));
    }

    public static Optional<State> byCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    public static boolean isIndia(String country) {
        return country != null && INDIA.contains(key(country));
    }

    private static void state(String code, String name, String... aliases) {
        add(new State(code, name, false), aliases);
    }

    private static void territory(String code, String name, String... aliases) {
        add(new State(code, name, true), aliases);
    }

    private static void add(State s, String... aliases) {
        BY_CODE.put(s.code(), s);
        BY_KEY.put(key(s.code()), s);
        BY_KEY.put(key(s.name()), s);
        for (String alias : aliases) {
            BY_KEY.put(key(alias), s);
        }
    }

    private static String key(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^a-z]", "");
    }
}
```

- [ ] **Step 3: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=IndianStatesTest`
Expected: PASS. If `junit-jupiter-params` is missing from the test classpath, it comes with `spring-boot-starter-test`; check `mvn dependency:tree | grep params`.

- [ ] **Step 4: Commit**

```bash
git add backend/src
git commit -m "feat(shared): list of Indian states and union territories with common spellings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Location section

**Files:**
- Create: `backend/src/main/java/com/sixpack/dashboard/domain/RegionLevel.java`, `LocationReads.java`, `LocationSection.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoLocationReads.java`
- Create: `backend/src/main/java/com/sixpack/dashboard/application/LocationService.java`
- Modify: `backend/src/main/java/com/sixpack/dashboard/web/AnalyticsController.java`
- Test: `backend/src/test/java/com/sixpack/dashboard/domain/LocationSectionTest.java`, `backend/src/test/java/com/sixpack/it/LocationSectionIntegrationTest.java`
- Create: `ecommerce-frontend/src/components/admin/analytics/LocationTab.tsx`
- Modify: `ecommerce-frontend/src/types/analytics.ts`, `redux/api/dashboardAPI.ts`, `components/admin/analytics/sections.ts` (+ test), `pages/admin/analytics.tsx`

**Interfaces:**
- Consumes: Task 9 `IndianStates`; Task 8 `DemandReads.LineSales`; Task 1 `OrderQueries.salesBy` (with the `extra` criteria) and plumbing.
- Produces:
  - `enum RegionLevel { COUNTRY, STATE, PIN_PREFIX, PIN }`
  - `interface LocationReads` with `record RegionCount(String key, long orders, double sales)`, `record RegionMatch(List<String> countries, List<String> states, Integer pinPrefix)` (+ `ALL`); methods `count(Scope, Instant, Instant, RegionMatch, RegionLevel)`, `topProducts(Scope, Instant, Instant, RegionMatch, int limit)`.
  - `record LocationSection(Window window, String level, String country, String state, String pinPrefix, List<RegionRow> rows, Unrecognised unrecognised, List<TopProduct> topProducts)`; `record Filter(String country, String state, String pinPrefix)` with `level(boolean csv)`; statics `rows`, `unrecognised`, `countryKey`, `countryLabel`, `stateKey`, `stateLabel`, `pinKey`; constants `SMALL = 3`, `UNKNOWN = "unknown"`, `UNRECOGNISED = "unrecognised"`, `INVALID = "invalid"`.
  - `LocationService#section(SectionQuery, LocationSection.Filter, boolean csv)`; `GET /api/v1/dashboard/location?country=&state=&pinPrefix=`.
  - Frontend: `LocationSection` type, `LocationParams`, `useGetLocationSectionQuery`, `drillTo(level, key)` in `sections.ts`.

- [ ] **Step 1: Write the failing domain test**

`backend/src/test/java/com/sixpack/dashboard/domain/LocationSectionTest.java`:

```java
package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.dashboard.domain.LocationReads.RegionCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationSectionTest {

    @Test
    void mergesSpellingsOfTheSameStateAndFlagsSmallGroups() {
        List<LocationSection.RegionRow> rows = LocationSection.rows(
                List.of(new RegionCount("Karnataka", 1, 1000), new RegionCount("karnataka ", 1, 500),
                        new RegionCount("KA", 1, 300), new RegionCount("Tamil Nadu", 1, 200),
                        new RegionCount("Bangalore", 1, 100)),
                List.of(new RegionCount("Karnataka", 1, 400)),
                LocationSection::stateKey, LocationSection::stateLabel);
        assertThat(rows).extracting(LocationSection.RegionRow::key).containsExactly("KA", "TN", "unrecognised");
        LocationSection.RegionRow ka = rows.get(0);
        assertThat(ka.label()).isEqualTo("Karnataka");
        assertThat(ka.orders()).isEqualTo(3);
        assertThat(ka.revenue()).isEqualTo(1800);
        assertThat(ka.averageOrderValue()).isEqualTo(600);
        assertThat(ka.share()).isEqualTo(60.0);
        assertThat(ka.change()).isEqualTo(200L);
        assertThat(ka.small()).isFalse();
        assertThat(rows.get(1).small()).isTrue();
        assertThat(rows.get(1).change()).isNull();
    }

    @Test
    void unrecognisedStatesKeepTheirRawSpellingForTheShopToSee() {
        LocationSection.Unrecognised u = LocationSection.unrecognised(List.of(new RegionCount("Bangalore", 2, 100),
                new RegionCount("KA", 1, 300), new RegionCount(null, 1, 10)));
        assertThat(u.orders()).isEqualTo(3);
        assertThat(u.samples()).extracting(LocationSection.Sample::raw).containsExactly("Bangalore", "(blank)");
    }

    @Test
    void countryAndPinKeys() {
        assertThat(LocationSection.countryKey(" india")).isEqualTo("india");
        assertThat(LocationSection.countryKey(null)).isEqualTo("unknown");
        assertThat(LocationSection.countryLabel("india")).isEqualTo("India");
        assertThat(LocationSection.countryLabel("sri lanka")).isEqualTo("Sri Lanka");
        assertThat(LocationSection.pinKey(RegionLevel.PIN_PREFIX, "560")).isEqualTo("560");
        assertThat(LocationSection.pinKey(RegionLevel.PIN_PREFIX, "4")).isEqualTo("invalid");
        assertThat(LocationSection.pinKey(RegionLevel.PIN, null)).isEqualTo("unknown");
    }

    @Test
    void theFilterPicksTheLevelAndCsvStopsAtThePrefix() {
        assertThat(new LocationSection.Filter(null, null, null).level(false)).isEqualTo(RegionLevel.COUNTRY);
        assertThat(new LocationSection.Filter("India", null, null).level(false)).isEqualTo(RegionLevel.STATE);
        assertThat(new LocationSection.Filter("India", "KA", null).level(false)).isEqualTo(RegionLevel.PIN_PREFIX);
        assertThat(new LocationSection.Filter("India", "KA", "560").level(false)).isEqualTo(RegionLevel.PIN);
        assertThat(new LocationSection.Filter("India", "KA", "560").level(true)).isEqualTo(RegionLevel.PIN_PREFIX);
    }

    @Test
    void theFilterRejectsDrillsThatMakeNoSense() {
        assertThatThrownBy(() -> new LocationSection.Filter(null, "KA", null))
                .hasMessage("state: choose India as the country first");
        assertThatThrownBy(() -> new LocationSection.Filter("Nepal", "KA", null))
                .hasMessage("state: choose India as the country first");
        assertThatThrownBy(() -> new LocationSection.Filter("India", "ZZ", null)).hasMessage("state: unknown state code");
        assertThatThrownBy(() -> new LocationSection.Filter("India", "KA", "56")).hasMessage("pinPrefix: three digits");
        assertThatThrownBy(() -> new LocationSection.Filter("India", null, "560"))
                .hasMessage("pinPrefix: choose a state first");
    }

    @Test
    void noOrdersNoRows() {
        assertThat(LocationSection.rows(List.of(), null, LocationSection::countryKey, LocationSection::countryLabel))
                .isEmpty();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=LocationSectionTest` — Expected: compilation failure.

- [ ] **Step 2: Write `RegionLevel`, `LocationReads`, `LocationSection`**

`backend/src/main/java/com/sixpack/dashboard/domain/RegionLevel.java`:

```java
package com.sixpack.dashboard.domain;

/** How far the Location tab has drilled down: country → state → 3-digit PIN prefix → 6-digit PIN. */
public enum RegionLevel {
    COUNTRY, STATE, PIN_PREFIX, PIN
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/LocationReads.java`:

```java
package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.util.List;

/** Read side of the Location tab, on the shipping address saved with each order. */
public interface LocationReads {

    /** @param key the raw stored value (country or state as typed, PIN prefix or PIN as digits); null if missing */
    record RegionCount(String key, long orders, double sales) {
    }

    /** Raw values to match; a null field means no constraint. */
    record RegionMatch(List<String> countries, List<String> states, Integer pinPrefix) {
        public static final RegionMatch ALL = new RegionMatch(null, null, null);
    }

    /** Orders placed in [from, to) in the matched region, grouped at {@code level}; narrowed to items like the trend. */
    List<RegionCount> count(Scope scope, Instant from, Instant to, RegionMatch match, RegionLevel level);

    /** Best sellers by units among orders shipped to the matched region. */
    List<DemandReads.LineSales> topProducts(Scope scope, Instant from, Instant to, RegionMatch match, int limit);
}
```

`backend/src/main/java/com/sixpack/dashboard/domain/LocationSection.java`:

```java
package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.LocationReads.RegionCount;
import com.sixpack.shared.geo.IndianStates;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where orders ship to, at one drill level. Groups with fewer than {@link #SMALL} orders are flagged
 * {@code small} and get no change figure. {@code unrecognised} is set at the state level only.
 *
 * @param country the drilled country's label, state the drilled state code, pinPrefix the drilled prefix
 */
public record LocationSection(@JsonUnwrapped Window window, String level, String country, String state,
                              String pinPrefix, List<RegionRow> rows, Unrecognised unrecognised,
                              List<TopProduct> topProducts) implements Section {

    public static final int SMALL = 3;
    public static final String UNKNOWN = "unknown";
    public static final String UNRECOGNISED = "unrecognised";
    public static final String INVALID = "invalid";

    /** @param change percent change in orders against the comparison period */
    public record RegionRow(String key, String label, long orders, double revenue, double averageOrderValue,
                            double share, Long change, boolean small) {
    }

    public record Sample(String raw, long orders) {
    }

    public record Unrecognised(long orders, List<Sample> samples) {
    }

    public record TopProduct(String productId, String name, long units, double revenue) {
    }

    /** The drill-down asked for; validated on construction. */
    public record Filter(String country, String state, String pinPrefix) {

        public Filter {
            country = blank(country);
            state = blank(state) == null ? null : state.trim().toUpperCase(Locale.ROOT);
            pinPrefix = blank(pinPrefix);
            if (state != null && (country == null || !IndianStates.isIndia(country))) {
                throw new IllegalArgumentException("state: choose India as the country first");
            }
            if (state != null && IndianStates.byCode(state).isEmpty()) {
                throw new IllegalArgumentException("state: unknown state code");
            }
            if (pinPrefix != null && state == null) {
                throw new IllegalArgumentException("pinPrefix: choose a state first");
            }
            if (pinPrefix != null && !pinPrefix.matches("\\d{3}")) {
                throw new IllegalArgumentException("pinPrefix: three digits");
            }
        }

        /** A CSV never goes below the 3-digit prefix, so full PIN codes are never exported. */
        public RegionLevel level(boolean csv) {
            if (pinPrefix != null && !csv) {
                return RegionLevel.PIN;
            }
            return state != null ? RegionLevel.PIN_PREFIX : country != null ? RegionLevel.STATE : RegionLevel.COUNTRY;
        }

        private static String blank(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    public static List<RegionRow> rows(List<RegionCount> now, List<RegionCount> before,
                                       Function<String, String> keyOf, Function<String, String> labelOf) {
        Map<String, double[]> current = merge(now, keyOf);
        Map<String, double[]> previous = before == null ? null : merge(before, keyOf);
        double allOrders = current.values().stream().mapToDouble(v -> v[0]).sum();
        return current.entrySet().stream().map(e -> {
            long orders = (long) e.getValue()[0];
            double revenue = e.getValue()[1];
            boolean small = orders < SMALL;
            Long change = small || previous == null ? null
                    : Change.percent(orders, previous.getOrDefault(e.getKey(), new double[2])[0]);
            return new RegionRow(e.getKey(), labelOf.apply(e.getKey()), orders, revenue,
                    orders == 0 ? 0 : Change.round2(revenue / orders), Change.share(orders, allOrders), change, small);
        }).sorted(Comparator.comparingLong(RegionRow::orders).reversed().thenComparing(RegionRow::label)).toList();
    }

    public static Unrecognised unrecognised(List<RegionCount> stateRows) {
        List<RegionCount> unknown = stateRows.stream()
                .filter(r -> IndianStates.normalise(r.key()).isEmpty()).toList();
        Map<String, Long> byRaw = new LinkedHashMap<>();
        unknown.forEach(r -> byRaw.merge(r.key() == null || r.key().isBlank() ? "(blank)" : r.key().trim(),
                r.orders(), Long::sum));
        List<Sample> samples = byRaw.entrySet().stream().map(e -> new Sample(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(Sample::orders).reversed().thenComparing(Sample::raw))
                .limit(10).toList();
        return new Unrecognised(unknown.stream().mapToLong(RegionCount::orders).sum(), samples);
    }

    public static String countryKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return IndianStates.isIndia(raw) ? "india" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static String countryLabel(String key) {
        if (UNKNOWN.equals(key)) {
            return "Unknown";
        }
        return Arrays.stream(key.split(" ")).filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining(" "));
    }

    public static String stateKey(String raw) {
        return IndianStates.normalise(raw).map(IndianStates.State::code).orElse(UNRECOGNISED);
    }

    public static String stateLabel(String key) {
        return IndianStates.byCode(key).map(IndianStates.State::name).orElse("Unrecognised");
    }

    public static String pinKey(RegionLevel level, String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return raw.matches(level == RegionLevel.PIN ? "\\d{6}" : "\\d{3}") ? raw : INVALID;
    }

    public static String pinLabel(String key) {
        return UNKNOWN.equals(key) ? "No PIN code" : INVALID.equals(key) ? "Invalid PIN code" : key;
    }

    private static Map<String, double[]> merge(List<RegionCount> rows, Function<String, String> keyOf) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (RegionCount r : rows) {
            double[] t = out.computeIfAbsent(keyOf.apply(r.key()), k -> new double[2]);
            t[0] += r.orders();
            t[1] += r.sales();
        }
        return out;
    }

    @Override
    public List<String> csvHeader() {
        return List.of("level", "key", "label", "orders", "revenue", "averageOrderValue", "share");
    }

    @Override
    public List<List<Object>> csvRows() {
        return rows.stream().map(r -> List.<Object>of(level, r.key(), r.label(), r.orders(), r.revenue(),
                r.averageOrderValue(), r.share())).toList();
    }
}
```

Run: `cd backend && mvn -q test -Dtest=LocationSectionTest` — Expected: PASS (6 tests). (Karnataka: 3 orders now vs 1 before → +200%; share 3 of 5 = 60%.)

- [ ] **Step 3: Write the failing integration test**

`backend/src/test/java/com/sixpack/it/LocationSectionIntegrationTest.java`:

```java
package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static com.sixpack.it.AnalyticsSeed.shipTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class LocationSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private String admin;

    @BeforeEach
    void seed() throws Exception {
        admin = adminToken();
        AnalyticsSeed seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.save(seed.order("2026-10-05T10:00:00Z", 1000, item(SHOE, 1000, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Karnataka", "India", 560001L)));
        seed.save(seed.order("2026-10-06T10:00:00Z", 500, item(SHOE, 500, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("karnataka ", "india", 560034L)));
        seed.save(seed.order("2026-10-07T10:00:00Z", 300, item(SHOE, 300, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("KA", "India", 562157L)));
        seed.save(seed.order("2026-10-08T10:00:00Z", 200, item(SHOE, 200, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Tamil Nadu", "India", 600001L)));
        seed.save(seed.order("2026-10-09T10:00:00Z", 100, item(SHOE, 100, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Bangalore", "India", 560100L)));
        seed.save(seed.order("2026-10-10T10:00:00Z", 50, item(SHOE, 50, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Bagmati", "Nepal", 44600L)));
        seed.save(seed.order("2026-10-11T10:00:00Z", 10, item(SHOE, 10, 1).append("name", "Shoe"))); // legacy: no address
        seed.save(seed.order("2026-09-15T10:00:00Z", 400, item(SHOE, 400, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Karnataka", "India", 560001L)));
    }

    private MockHttpServletRequestBuilder location() {
        return get("/api/v1/dashboard/location").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void byCountryWithMissingAddressesAsUnknown() throws Exception {
        mvc.perform(location())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.level").value("country"))
                .andExpect(jsonPath("$.section.rows[0].key").value("india"))
                .andExpect(jsonPath("$.section.rows[0].label").value("India"))
                .andExpect(jsonPath("$.section.rows[0].orders").value(5))
                .andExpect(jsonPath("$.section.rows[0].revenue").value(2100.0))
                .andExpect(jsonPath("$.section.rows[0].share").value(71.4))
                .andExpect(jsonPath("$.section.rows[0].change").value(400))
                .andExpect(jsonPath("$.section.rows[1].label").value("Nepal"))
                .andExpect(jsonPath("$.section.rows[1].small").value(true))
                .andExpect(jsonPath("$.section.rows[2].label").value("Unknown"));
    }

    @Test
    void byStateMergesSpellingsAndShowsWhatWasNotRecognised() throws Exception {
        mvc.perform(location().param("country", "India"))
                .andExpect(jsonPath("$.section.level").value("state"))
                .andExpect(jsonPath("$.section.rows[0].key").value("KA"))
                .andExpect(jsonPath("$.section.rows[0].orders").value(3))
                .andExpect(jsonPath("$.section.rows[0].change").value(200))
                .andExpect(jsonPath("$.section.unrecognised.orders").value(1))
                .andExpect(jsonPath("$.section.unrecognised.samples[0].raw").value("Bangalore"))
                .andExpect(jsonPath("$.section.topProducts[0].name").value("Shoe"));
    }

    @Test
    void byPinPrefixThenFullPin() throws Exception {
        mvc.perform(location().param("country", "India").param("state", "KA"))
                .andExpect(jsonPath("$.section.level").value("pin_prefix"))
                .andExpect(jsonPath("$.section.rows[0].key").value("560"))
                .andExpect(jsonPath("$.section.rows[0].orders").value(2))
                .andExpect(jsonPath("$.section.rows[1].key").value("562"));
        mvc.perform(location().param("country", "India").param("state", "KA").param("pinPrefix", "560"))
                .andExpect(jsonPath("$.section.level").value("pin"))
                .andExpect(jsonPath("$.section.rows.length()").value(2));
    }

    @Test
    void exportNeverHasFullPinCodes() throws Exception {
        mvc.perform(location().param("country", "India").param("state", "KA").param("pinPrefix", "560")
                        .param("format", "csv"))
                .andExpect(content().string(containsString("pin_prefix,560,560,2,")))
                .andExpect(content().string(not(containsString("560001"))))
                .andExpect(content().string(not(containsString("560034"))));
    }

    @Test
    void rejectsDrillsThatMakeNoSense() throws Exception {
        mvc.perform(location().param("state", "KA")).andExpect(status().isBadRequest());
        mvc.perform(location().param("country", "Nepal").param("state", "KA")).andExpect(status().isBadRequest());
        mvc.perform(location().param("country", "India").param("state", "ZZ")).andExpect(status().isBadRequest());
        mvc.perform(location().param("country", "India").param("state", "KA").param("pinPrefix", "56"))
                .andExpect(status().isBadRequest());
    }
}
```

Run: `cd backend && mvn -q test -Dtest=LocationSectionIntegrationTest` — Expected: FAIL (route missing).

- [ ] **Step 4: Write the adapter, service and route**

`backend/src/main/java/com/sixpack/dashboard/infra/mongo/MongoLocationReads.java`:

```java
package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.DemandReads.LineSales;
import com.sixpack.dashboard.domain.LocationReads;
import com.sixpack.dashboard.domain.RegionLevel;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

@Component
class MongoLocationReads implements LocationReads {

    private static final String COUNTRY = "shippingInfo.country";
    private static final String STATE = "shippingInfo.state";
    private static final String PIN = "shippingInfo.pinCode";

    private final OrderQueries q;

    MongoLocationReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<RegionCount> count(Scope scope, Instant from, Instant to, RegionMatch match, RegionLevel level) {
        // a PIN stored as text or garbage becomes null instead of failing the whole aggregation
        Document pin = new Document("$convert", new Document("input", "$" + PIN).append("to", "long")
                .append("onError", null).append("onNull", null));
        Object key = switch (level) {
            case COUNTRY -> "$" + COUNTRY;
            case STATE -> "$" + STATE;
            case PIN_PREFIX -> new Document("$toString", new Document("$floor",
                    new Document("$divide", List.of(pin, 1000))));
            case PIN -> new Document("$toString", pin);
        };
        List<RegionCount> out = new ArrayList<>();
        for (Document d : q.mongo().aggregate(q.salesBy(scope, from, to, criteria(match), key), OrderQueries.ORDERS,
                Document.class)) {
            Object id = d.get("_id");
            out.add(new RegionCount(id == null ? null : id.toString(), OrderQueries.orders(d),
                    OrderQueries.number(d.get("sales"))));
        }
        return out;
    }

    @Override
    public List<LineSales> topProducts(Scope scope, Instant from, Instant to, RegionMatch match, int limit) {
        List<AggregationOperation> agg = new ArrayList<>();
        agg.add(match(q.criteria(Dataset.ORDERS, scope, from, to)));
        Criteria region = criteria(match);
        if (region != null) {
            agg.add(match(region));
        }
        agg.add(OrderQueries.stage("$sort", new Document(OrderQueries.CREATED_AT, 1)));
        agg.add(OrderQueries.stage("$unwind", "$orderItems"));
        if (scope.productId() != null || scope.category() != null) {
            agg.add(match(q.itemCriteria(scope)));
        }
        agg.add(OrderQueries.stage("$group", new Document("_id", "$orderItems.productId")
                .append("name", new Document("$last", "$orderItems.name"))
                .append("units", new Document("$sum", OrderQueries.orZero("$orderItems.quantity")))
                .append("revenue", new Document("$sum", new Document("$multiply", List.of(
                        OrderQueries.orZero("$orderItems.price"), OrderQueries.orZero("$orderItems.quantity")))))));
        agg.add(OrderQueries.stage("$sort", new Document("units", -1).append("_id", 1)));
        agg.add(OrderQueries.stage("$limit", limit));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> new LineSales(OrderQueries.hex(d.get("_id")), null, d.getString("name"), null,
                        (long) OrderQueries.number(d.get("units")), OrderQueries.number(d.get("revenue")), 0))
                .toList();
    }

    /** @return null when the match has no constraint */
    private static Criteria criteria(RegionMatch m) {
        if (m.countries() == null && m.states() == null && m.pinPrefix() == null) {
            return null;
        }
        Criteria c = new Criteria();
        if (m.countries() != null) {
            c.and(COUNTRY).in(m.countries());
        }
        if (m.states() != null) {
            c.and(STATE).in(m.states());
        }
        if (m.pinPrefix() != null) {
            c.and(PIN).gte(m.pinPrefix() * 1000L).lt((m.pinPrefix() + 1) * 1000L);
        }
        return c;
    }
}
```

`backend/src/main/java/com/sixpack/dashboard/application/LocationService.java`:

```java
package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.LocationReads;
import com.sixpack.dashboard.domain.LocationReads.RegionCount;
import com.sixpack.dashboard.domain.LocationReads.RegionMatch;
import com.sixpack.dashboard.domain.LocationSection;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.RegionLevel;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Window;
import com.sixpack.shared.geo.IndianStates;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * The Location tab. Shoppers typed countries and states freely, so the raw values that mean the region asked for
 * are looked up first (in both periods) and then matched exactly.
 */
@Service
public class LocationService {

    private static final int TOP_PRODUCTS = 5;

    private final LocationReads read;
    private final StoreTime time;

    LocationService(LocationReads read, StoreTime time) {
        this.read = read;
        this.time = time;
    }

    public String cacheTag() {
        return time.zone().getId();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'location:' + #root.target.cacheTag() + #q + #filter + #csv")
    public LocationSection section(SectionQuery q, LocationSection.Filter filter, boolean csv) {
        ZoneId zone = time.zone();
        RegionLevel level = filter.level(csv);
        Period other = q.comparePeriod();
        List<Period> periods = other == null ? List.of(q.period()) : List.of(q.period(), other);

        RegionMatch match = RegionMatch.ALL;
        LocationSection.Unrecognised unrecognised = null;
        if (level != RegionLevel.COUNTRY) {
            List<String> india = rawValues(q.scope(), periods, zone, RegionMatch.ALL, RegionLevel.COUNTRY,
                    IndianStates::isIndia);
            match = new RegionMatch(india, null, null);
            if (level == RegionLevel.STATE) {
                unrecognised = LocationSection.unrecognised(read.count(q.scope(), q.period().start(zone),
                        q.period().end(zone), match, RegionLevel.STATE));
            } else {
                String code = filter.state();
                List<String> states = rawValues(q.scope(), periods, zone, match, RegionLevel.STATE,
                        raw -> IndianStates.normalise(raw).map(s -> s.code().equals(code)).orElse(false));
                match = new RegionMatch(india, states,
                        level == RegionLevel.PIN ? Integer.valueOf(filter.pinPrefix()) : null);
            }
        }

        List<RegionCount> now = read.count(q.scope(), q.period().start(zone), q.period().end(zone), match, level);
        List<RegionCount> before = other == null ? null
                : read.count(q.scope(), other.start(zone), other.end(zone), match, level);
        Function<String, String> keyOf = switch (level) {
            case COUNTRY -> LocationSection::countryKey;
            case STATE -> LocationSection::stateKey;
            case PIN_PREFIX, PIN -> raw -> LocationSection.pinKey(level, raw);
        };
        Function<String, String> labelOf = switch (level) {
            case COUNTRY -> LocationSection::countryLabel;
            case STATE -> LocationSection::stateLabel;
            case PIN_PREFIX, PIN -> LocationSection::pinLabel;
        };
        List<LocationSection.TopProduct> top = level == RegionLevel.COUNTRY ? List.of()
                : read.topProducts(q.scope(), q.period().start(zone), q.period().end(zone), match, TOP_PRODUCTS)
                        .stream().map(s -> new LocationSection.TopProduct(s.productId(), s.name(), s.units(),
                                s.revenue())).toList();
        return new LocationSection(Window.of(q, zone), level.name().toLowerCase(Locale.ROOT),
                filter.country() == null ? null : LocationSection.countryLabel(LocationSection.countryKey(filter.country())),
                filter.state(), level == RegionLevel.PIN ? filter.pinPrefix() : null,
                LocationSection.rows(now, before, keyOf, labelOf), unrecognised, top);
    }

    /** The stored spellings, across all the periods shown, that the test accepts. Empty matches nothing. */
    private List<String> rawValues(Scope scope, List<Period> periods, ZoneId zone, RegionMatch match,
                                   RegionLevel level, java.util.function.Predicate<String> accept) {
        Set<String> out = new LinkedHashSet<>();
        for (Period p : periods) {
            Instant from = p.start(zone);
            Instant to = p.end(zone);
            for (RegionCount r : read.count(scope, from, to, match, level)) {
                if (r.key() != null && accept.test(r.key())) {
                    out.add(r.key());
                }
            }
        }
        return new ArrayList<>(out);
    }
}
```

(An empty `states` list makes `$in: []`, which matches nothing — correct for a state with no orders.)

In `AnalyticsController` add `LocationService location` and:

```java
    @GetMapping("/location")
    ResponseEntity<?> location(@Valid SectionRequest req,
                               @RequestParam(required = false) @Size(max = 60) String country,
                               @RequestParam(required = false) @Size(max = 10) String state,
                               @RequestParam(required = false) @Size(max = 3) String pinPrefix) {
        return respond("location", req, q -> location.section(q,
                new LocationSection.Filter(country, state, pinPrefix), req.csv()));
    }
```

(`@Size(max = 3)` on `pinPrefix` turns "5600" into a 400 before the filter sees it; the filter's own message covers "56".)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && mvn -q test -Dtest='LocationSectionIntegrationTest,LocationSectionTest'`
Expected: PASS. (Country level: India has 5 of 7 orders = 71.4%, 5 vs 1 in September = +400%; Nepal and the address-less order are small and sort by label.)

- [ ] **Step 6: Commit the backend**

```bash
git add backend/src
git commit -m "feat(analytics): location section from country down to PIN prefix

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Write the failing frontend test for `drillTo`**

Append to `sections.test.ts` (add `drillTo` to the import):

```ts
describe("drillTo", () => {
  it("goes one level down from a row, and nowhere from a dead end", () => {
    expect(drillTo("country", "india")).toEqual({ country: "India" });
    expect(drillTo("country", "nepal")).toBeNull();
    expect(drillTo("state", "KA")).toEqual({ state: "KA" });
    expect(drillTo("state", "unrecognised")).toBeNull();
    expect(drillTo("pin_prefix", "560")).toEqual({ pinPrefix: "560" });
    expect(drillTo("pin_prefix", "invalid")).toBeNull();
    expect(drillTo("pin", "560001")).toBeNull();
  });
});
```

Run: `cd ecommerce-frontend && npm test -- src/components/admin/analytics/sections.test.ts`
Expected: FAIL.

- [ ] **Step 8: Implement `drillTo`, the type, hook and tab**

In `sections.ts`:

```ts
/** The URL parameter that drills into a Location row, or null when the row cannot be opened. */
export const drillTo = (level: string, key: string): Record<string, string> | null => {
  if (level === "country") return key === "india" ? { country: "India" } : null;
  if (level === "state") return /^[A-Z]{2}$/.test(key) ? { state: key } : null;
  if (level === "pin_prefix") return /^\d{3}$/.test(key) ? { pinPrefix: key } : null;
  return null;
};
```

`types/analytics.ts`:

```ts
export type LocationParams = SectionParams & { country?: string; state?: string; pinPrefix?: string };

export type LocationSection = SectionWindow & {
  level: "country" | "state" | "pin_prefix" | "pin";
  country?: string | null;
  state?: string | null;
  pinPrefix?: string | null;
  rows: { key: string; label: string; orders: number; revenue: number; averageOrderValue: number; share: number;
          change?: number | null; small: boolean }[];
  unrecognised?: { orders: number; samples: { raw: string; orders: number }[] } | null;
  topProducts: { productId: string; name: string; units: number; revenue: number }[];
};
```

`dashboardAPI.ts`: add `getLocationSection: builder.query<SectionResponse<LocationSection>, LocationParams>` (url `"location"`) and export `useGetLocationSectionQuery`.

`ecommerce-frontend/src/components/admin/analytics/LocationTab.tsx`:

```tsx
import { useSearchParams } from "react-router-dom";
import { useGetLocationSectionQuery } from "../../../redux/api/dashboardAPI";
import { LocationParams, SectionParams } from "../../../types/analytics";
import { formatMoney } from "../../../utils/features";
import { COMPARE_LABEL, drillTo } from "./sections";
import { ChangeBadge, CsvButton, SectionState } from "./Shared";

const LocationTab = ({ params }: { params: SectionParams }) => {
  const [search, setSearch] = useSearchParams();
  const query: LocationParams = {
    ...params,
    country: search.get("country") ?? undefined,
    state: search.get("state") ?? undefined,
    pinPrefix: search.get("pinPrefix") ?? undefined,
  };
  const { data, isLoading, isError } = useGetLocationSectionQuery(query);
  const go = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(search);
    Object.entries(changes).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    setSearch(next, { replace: true });
  };
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const against = COMPARE_LABEL[params.compare];

  return (
    <>
      <div className="analytics__actions">
        <nav className="breadcrumb" aria-label="Location drill-down">
          <button type="button" className="link" onClick={() => go({ country: null, state: null, pinPrefix: null })}>All countries</button>
          {s.country && <> › <button type="button" className="link" onClick={() => go({ state: null, pinPrefix: null })}>{s.country}</button></>}
          {s.state && <> › <button type="button" className="link" onClick={() => go({ pinPrefix: null })}>{s.state}</button></>}
          {s.pinPrefix && <> › {s.pinPrefix}</>}
        </nav>
        <CsvButton section="location" params={query} />
      </div>
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide">
          <h4>Orders by {s.level === "pin_prefix" ? "PIN area (first 3 digits)" : s.level === "pin" ? "PIN code" : s.level}</h4>
          {s.rows.length === 0 ? <p className="data-table__empty">No orders shipped here in this period.</p> : (
            <table className="mini-table">
              <thead><tr><th>Region</th><th>Orders</th><th>Share</th><th>Revenue</th><th>Avg order</th><th>Change</th></tr></thead>
              <tbody>
                {s.rows.map((r) => {
                  const next = drillTo(s.level, r.key);
                  return (
                    <tr key={r.key} className={r.small ? "is-muted" : undefined}>
                      <td>{next ? <button type="button" className="link" onClick={() => go(next)}>{r.label}</button> : r.label}</td>
                      <td>{r.orders}</td><td>{r.share}%</td><td>{formatMoney(r.revenue)}</td>
                      <td>{formatMoney(r.averageOrderValue)}</td>
                      <td>{r.small ? <small>too few orders</small> : <ChangeBadge change={r.change} against={against} />}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          <p className="analytics__note">Regions with fewer than 3 orders are greyed out and get no change figure. Exports stop at the 3-digit PIN area.</p>
        </div>
        {s.unrecognised && s.unrecognised.orders > 0 && (
          <div className="card chart-card">
            <h4>States we could not recognise ({s.unrecognised.orders} orders)</h4>
            <ul className="category-bars">
              {s.unrecognised.samples.map((u) => <li key={u.raw}><span>“{u.raw}”</span><b>{u.orders}</b></li>)}
            </ul>
            <p className="analytics__note">These were typed at checkout before the state list existed.</p>
          </div>
        )}
        {s.topProducts.length > 0 && (
          <div className="card chart-card">
            <h4>Top products here</h4>
            <table className="mini-table">
              <thead><tr><th>Product</th><th>Units</th><th>Revenue</th></tr></thead>
              <tbody>{s.topProducts.map((p) => (
                <tr key={p.productId}><td>{p.name}</td><td>{p.units}</td><td>{formatMoney(p.revenue)}</td></tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
};

export default LocationTab;
```

Add to `_analytics.scss`:

```scss
.breadcrumb {
  margin-right: auto;
  color: $color-text-secondary;

  .link {
    border: 0;
    background: none;
    padding: 0;
    color: var(--accent);
    font: inherit;
    cursor: pointer;
  }
}

.mini-table tr.is-muted td {
  color: $color-text-secondary;
}

.mini-table .link {
  border: 0;
  background: none;
  padding: 0;
  color: var(--accent);
  font: inherit;
  cursor: pointer;
  text-align: left;
}
```

In `analytics.tsx` add `{tab === "location" && <LocationTab params={section} />}`, and when the tab changes away from Location/Demand drop their own params: in the tab button's `onClick`, call `setParam({ tab: …, country: "", state: "", pinPrefix: "", rank: "", by: "" })`.

- [ ] **Step 9: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all pass.

- [ ] **Step 10: Commit the frontend**

```bash
git add ecommerce-frontend/src
git commit -m "feat(analytics): location tab with drill-down

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 11: Indian state dropdown at checkout, and state-aware invoices

**Files:**
- Create: `backend/src/main/java/com/sixpack/order/web/ReferenceController.java`
- Modify: `backend/src/main/java/com/sixpack/order/web/OrderController.java` (`toDomain`)
- Modify: `backend/src/main/java/com/sixpack/order/domain/Invoice.java` (`sameState`)
- Modify: `backend/src/main/java/com/sixpack/tenant/application/StoreSettingsService.java` (`validInvoice`)
- Modify: `backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/sixpack/it/IndianStateCheckoutIntegrationTest.java`, `backend/src/test/java/com/sixpack/order/domain/InvoiceSameStateTest.java`, `backend/src/test/java/com/sixpack/it/StoreSettingsIntegrationTest.java`, `backend/src/test/java/com/sixpack/it/OrderErasureIntegrationTest.java` (expected value)
- Create: `ecommerce-frontend/src/redux/api/referenceAPI.ts`, `ecommerce-frontend/src/utils/indianStates.ts`, `indianStates.test.ts`
- Modify: `ecommerce-frontend/src/redux/store.ts`, `ecommerce-frontend/src/pages/shipping.tsx`, `ecommerce-frontend/src/pages/admin/settings.tsx`

**Interfaces:**
- Consumes: Task 9 `IndianStates`.
- Produces: public `GET /api/v1/reference/indian-states` → `{success, states:[{code, name, unionTerritory}]}`; checkout stores the official state name for Indian addresses and rejects unknown ones with `400 "<shippingInfo|billingInfo>.state: not an Indian state or union territory"`; `invoice.state` store setting stored as the official name; `useIndianStatesQuery()`; `matchState(raw, states): string`.

- [ ] **Step 1: Write the failing backend tests**

`backend/src/test/java/com/sixpack/order/domain/InvoiceSameStateTest.java`:

```java
package com.sixpack.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvoiceSameStateTest {

    @Test
    void comparesStatesNotSpellings() {
        assertThat(Invoice.sameState("Karnataka", "KA")).isTrue();
        assertThat(Invoice.sameState("karnataka ", "Karnataka")).isTrue();
        assertThat(Invoice.sameState("Karnataka", "Tamil Nadu")).isFalse();
    }

    @Test
    void fallsBackToTextForAnythingElse() {
        assertThat(Invoice.sameState("Somewhere", "somewhere ")).isTrue();
        assertThat(Invoice.sameState(null, "KA")).isFalse();
        assertThat(Invoice.sameState(" ", " ")).isFalse();
    }
}
```

`backend/src/test/java/com/sixpack/it/IndianStateCheckoutIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class IndianStateCheckoutIntegrationTest extends IntegrationTest {

    private String user;
    private String product;

    @BeforeEach
    void shop() throws Exception {
        product = createProduct(adminToken(), "Shoe", 500, 10, "footwear");
        user = register("buyer@test.dev");
    }

    @Test
    void theListIsPublic() throws Exception {
        mvc.perform(get("/api/v1/reference/indian-states"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.states.length()").value(36))
                .andExpect(jsonPath("$.states[0].code").value("AN"));
    }

    @Test
    void anIndianStateIsStoredUnderItsOfficialName() throws Exception {
        String orderId = body(confirm(address("KA", "India"), null).andExpect(status().isCreated()).andReturn())
                .get("orderId").asText();
        Document stored = mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
        assertThat(stored.get("shippingInfo", Document.class).getString("state")).isEqualTo("Karnataka");
    }

    @Test
    void anUnknownIndianStateIsRejected() throws Exception {
        confirm(address("Bangalore", "India"), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("shippingInfo.state: not an Indian state or union territory"));
        confirm(address("KA", "India"), address("Atlantis", "India")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("billingInfo.state: not an Indian state or union territory"));
    }

    @Test
    void otherCountriesKeepWhatWasTyped() throws Exception {
        String orderId = body(confirm(address("Bagmati", "Nepal"), null).andExpect(status().isCreated()).andReturn())
                .get("orderId").asText();
        Document stored = mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
        assertThat(stored.get("shippingInfo", Document.class).getString("state")).isEqualTo("Bagmati");
    }

    private static Map<String, Object> address(String state, String country) {
        return Map.of("address", "1 MG Road", "city", "Bengaluru", "state", state, "country", country,
                "pinCode", "560001");
    }

    private ResultActions confirm(Map<String, Object> shipping, Map<String, Object> billing) throws Exception {
        String providerOrderId = body(mvc.perform(post("/api/v1/order/checkout")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items",
                                List.of(Map.of("productId", product, "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        Map<String, Object> request = new HashMap<>(Map.of("providerOrderId", providerOrderId,
                "paymentId", "fake_pay_1", "signature", "fake", "shippingInfo", shipping));
        if (billing != null) {
            request.put("billingInfo", billing);
        }
        return mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }
}
```

(`body(...)` is the existing `IntegrationTest` helper that parses a response. If checkout rejects non-Indian countries for another reason, delete `otherCountriesKeepWhatWasTyped` and say so in the task report.)

Add to `StoreSettingsIntegrationTest`:

```java
    @Test
    void theInvoiceStateIsStoredUnderItsOfficialName() throws Exception {
        saveSettings(acme, adminA, Map.of("invoice", Map.of("state", "ka"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.invoice.state").value("Karnataka"));
        saveSettings(acme, adminA, Map.of("invoice", Map.of("state", "Atlantis"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invoice.state: not an Indian state or union territory"));
    }
```

Run: `cd backend && mvn -q test -Dtest='InvoiceSameStateTest,IndianStateCheckoutIntegrationTest,StoreSettingsIntegrationTest'`
Expected: compilation failure (`Invoice.sameState` is private), then once it compiles, the new tests FAIL.

- [ ] **Step 2: Implement**

`Invoice.java` — make `sameState` package-private and compare normalised states:

```java
    /** Same state when both name one Indian state, however spelled; otherwise the trimmed text must match. */
    static boolean sameState(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        Optional<IndianStates.State> x = IndianStates.normalise(a);
        Optional<IndianStates.State> y = IndianStates.normalise(b);
        if (x.isPresent() && y.isPresent()) {
            return x.get().equals(y.get());
        }
        return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }
```

(imports `com.sixpack.shared.geo.IndianStates`, `java.util.Optional`).

`OrderController.java` — give `toDomain` the field name and normalise Indian states; update its two callers to pass `"shippingInfo"` and `"billingInfo"`:

```java
    private static ShippingInfo toDomain(ShippingRequest s, String field) {
        if (s == null) {
            return null;
        }
        String state = s.state().trim();
        if (IndianStates.isIndia(s.country())) {
            state = IndianStates.normalise(state).map(IndianStates.State::name).orElseThrow(() ->
                    ApiException.badRequest(field + ".state: not an Indian state or union territory"));
        }
        return new ShippingInfo(s.address().trim(), s.city().trim(), state, s.country().trim(),
                s.pinCode(), blankToNull(s.name()), blankToNull(s.phone()));
    }
```

`backend/src/main/java/com/sixpack/order/web/ReferenceController.java`:

```java
package com.sixpack.order.web;

import com.sixpack.shared.geo.IndianStates;
import com.sixpack.shared.web.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed lists the checkout form offers. Public: shoppers see them before signing in. */
@RestController
@RequestMapping("/api/v1/reference")
class ReferenceController {

    @GetMapping("/indian-states")
    Map<String, Object> indianStates() {
        return ApiResponse.of("states", IndianStates.all());
    }
}
```

`SecurityConfig.java` — next to `.requestMatchers(GET, "/api/v1/legal/**").permitAll()` add:

```java
                        .requestMatchers(GET, "/api/v1/reference/**").permitAll()
```

`StoreSettingsService.validInvoice` — replace the length check on `state` with:

```java
        String state = i.state() == null ? null : IndianStates.normalise(i.state()).map(IndianStates.State::name)
                .orElseThrow(() -> ApiException.badRequest("invoice.state: not an Indian state or union territory"));
        return new StoreSettings.Invoice(gstin, state);
```

In `OrderErasureIntegrationTest` the order was placed with state "KA", which is now stored as "Karnataka": change `assertThat(shipping.get("state")).isEqualTo("KA");` to `isEqualTo("Karnataka")`.

- [ ] **Step 3: Run the whole backend suite**

Run: `cd backend && mvn -q verify`
Expected: BUILD SUCCESS. Any other test that asserted a raw "KA"/"MH" stored through checkout gets the same one-line update to the official name ("Karnataka"/"Maharashtra"); tests that insert documents straight into Mongo are unaffected.

- [ ] **Step 4: Commit the backend**

```bash
git add backend/src
git commit -m "feat(order): Indian states are checked and stored under their official names

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Write the failing frontend test**

`ecommerce-frontend/src/utils/indianStates.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { matchState } from "./indianStates";

const STATES = [
  { code: "KA", name: "Karnataka", unionTerritory: false },
  { code: "TN", name: "Tamil Nadu", unionTerritory: false },
];

describe("matchState", () => {
  it("pre-selects a saved address's state by name or code", () => {
    expect(matchState("Karnataka", STATES)).toBe("Karnataka");
    expect(matchState(" tamil  nadu ", STATES)).toBe("Tamil Nadu");
    expect(matchState("ka", STATES)).toBe("Karnataka");
  });

  it("leaves the choice empty when nothing matches", () => {
    expect(matchState("Bangalore", STATES)).toBe("");
    expect(matchState("", STATES)).toBe("");
  });
});
```

Run: `cd ecommerce-frontend && npm test -- src/utils/indianStates.test.ts` — Expected: FAIL.

- [ ] **Step 6: Implement the helper, API and dropdowns**

`ecommerce-frontend/src/utils/indianStates.ts`:

```ts
import type { IndianState } from "../redux/api/referenceAPI";

const key = (s: string) => s.toLowerCase().replace(/&/g, "and").replace(/[^a-z]/g, "");

/** The official name to pre-select for a typed or saved state, or "" when it is not on the list. */
export const matchState = (raw: string, states: IndianState[]) => {
  const k = key(raw);
  if (!k) return "";
  return states.find((s) => key(s.name) === k || key(s.code) === k)?.name ?? "";
};
```

`ecommerce-frontend/src/redux/api/referenceAPI.ts`:

```ts
import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

export type IndianState = { code: string; name: string; unionTerritory: boolean };

export const referenceAPI = createApi({
  reducerPath: "referenceApi",
  baseQuery: authedBaseQuery("reference/"),
  endpoints: (builder) => ({
    indianStates: builder.query<{ success: boolean; states: IndianState[] }, void>({ query: () => "indian-states" }),
  }),
});

export const { useIndianStatesQuery } = referenceAPI;
```

Register it in `redux/store.ts`: `[referenceAPI.reducerPath]: referenceAPI.reducer` in the reducers and `referenceAPI.middleware` in the `concat(...)` list.

In `pages/shipping.tsx`: `const { data: stateList } = useIndianStatesQuery(); const states = stateList?.states ?? [];` and replace each of the two state `<input>`s (shipping and billing) with this component, defined at the top of the file (import `IndianState`, `useIndianStatesQuery` and `matchState`):

```tsx
const StateField = ({ value, country, states, onChange, autoComplete }: {
  value: string; country: string; states: IndianState[]; autoComplete?: string;
  onChange: (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => void;
}) =>
  country === "India" && states.length > 0 ? (
    <select name="state" value={matchState(value, states)} onChange={onChange} required autoComplete={autoComplete}>
      <option value="">Choose a state</option>
      {states.map((s) => <option key={s.code} value={s.name}>{s.name}</option>)}
    </select>
  ) : (
    <input type="text" name="state" value={value} onChange={onChange} maxLength={80} required autoComplete={autoComplete} />
  );
```

Use it as `<StateField value={shippingInfo.state} country={shippingInfo.country} states={states} onChange={changeHandler} autoComplete="address-level1" />` and `<StateField value={billing.state} country={billing.country} states={states} onChange={changeBilling} />`. (If the list fails to load, the text box stays; the server still checks the state.)

In `pages/admin/settings.tsx` replace `{textField("state", "State of registration", { maxLength: 60 })}` with a select bound like the other fields:

```tsx
              <label>
                State of registration
                <select value={matchState(form.state, states)} onChange={set("state")}>
                  <option value="">Not set</option>
                  {states.map((s) => <option key={s.code} value={s.name}>{s.name}</option>)}
                </select>
              </label>
```

with `const { data: stateList } = useIndianStatesQuery(); const states = stateList?.states ?? [];` near the other hooks, and change the hint below it to "The state decides CGST + SGST (buyer in the same state) or IGST." (If `set` is typed for `HTMLInputElement` only, widen it to `HTMLInputElement | HTMLSelectElement`.)

- [ ] **Step 7: Run the frontend checks**

Run: `cd ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all pass.

- [ ] **Step 8: Commit the frontend**

```bash
git add ecommerce-frontend/src
git commit -m "feat(checkout): choose the state from a list for Indian addresses

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Contracts, ADR, help article and a browser pass

**Files:**
- Modify: `docs/foundations/contracts.md`, `docs/foundations/architecture.md`, `ecommerce-frontend/src/help/adminGuides.ts`, `docs/superpowers/specs/2026-10-20-analytics-sections-design.md` (status line)

**Interfaces:**
- Consumes: everything above.
- Produces: documentation only, plus a verified browser run.

- [ ] **Step 1: Document the endpoints**

In `docs/foundations/contracts.md`, after the `/summary` entry in the Dashboard section, add:

```markdown
- **Analytics sections** — `GET /overview|/sales|/orders|/stock|/customers|/demand|/location`. All take `from`, `to`,
  `compare`, `granularity`, `basis`, `category` exactly like `/summary`, plus `limit` (ranked lists, 1–50, default 10)
  and `format` (`json` default, or `csv` for the section's main table as `text/csv`, file
  `<section>-<from>_<to>.csv`). JSON is `{success, section:{from, to, compareFrom?, compareTo?, timezone, granularity, …}}`.
  - `/overview` → summary fields (`basis, current, previous?, change?, series`) + `byWeekday[7]{weekday, orders, revenue}`
    (1 = Monday, before refunds), `byHour[24]`, `heat[7][24]`. CSV: series.
  - `/sales` → `series, previousSeries?, byCategory[{category, revenue, units, share, change?}], breakdown?{charged,
    discount, tax, shipping, refunds, net, couponOrders, couponOrderShare}, byPaymentMethod?[{method, orders, revenue,
    share}]` (`breakdown`/`byPaymentMethod` omitted when narrowed to a category). CSV: series + previousRevenue.
  - `/orders` → `series, previousSeries?, byStatus, fulfilment{placedToShipped, shippedToDelivered, placedToDelivered:
    {medianHours?, p90Hours?, count}, withoutHistory, deliveryTrend[]}, stuck[], stuckCount, stuckAfterDays,
    size{averageItems, bands, topCoupons[]}, refunds?{orders, amount, rate}, truncated`. Stuck = Processing longer than
    the store's `commerce.stuckOrderDays` (default 3). CSV: one row per order, max 10 000.
  - `/stock` → current stock (dates ignored; window = last 30 days used for days left): `counts{lines, inStock, low,
    out}, value{total, byCategory[]}, low[], out[]`. CSV: every line.
  - `/customers` → `signups[], signupsTotal, signupsChange?, buyers{newBuyers, returning, newRevenue,
    returningRevenue}, repeatRate, ordersPerCustomer, top[{userId, name, orders, spent, lastOrderAt}], neverOrdered`.
    CSV: top customers, no contact details.
  - `/demand?rank=units|revenue&by=product|variant` → `top[], trending{rising[], falling[]}, notSelling[],
    boughtTogether[]`. CSV: every product/variant sold.
  - `/location?country=&state=<code>&pinPrefix=<3 digits>` → `level (country|state|pin_prefix|pin), rows[{key, label,
    orders, revenue, averageOrderValue, share, change?, small}], unrecognised?{orders, samples[]}, topProducts[]`.
    Below country only for India. CSV stops at `pin_prefix`.
- `GET /api/v1/reference/indian-states` (public) → `{success, states:[{code, name, unionTerritory}]}`. Checkout stores
  Indian states under these names and rejects others (400).
```

In the store-settings contract entry, add `commerce.stuckOrderDays` (1–60, default 3).

- [ ] **Step 2: Record ADR-025**

In `docs/foundations/architecture.md`, above the ADR-024 line (step 2's daily summary table), add:

```markdown
- 2026-10-20: ADR-025 **Analytics is one endpoint and one read port per section.** Overview, Sales, Orders, Stock,
  Customers, Demand and Location each have a `…Reads` port with its own Mongo adapter (sharing `OrderQueries`), a
  cached service and a route under `/api/v1/dashboard/`; each tab loads only when opened, and `?format=csv` exports its
  main table. Indian states are normalised with `shared.geo.IndianStates` at read time (no migration) and checked at
  checkout from now on. Gender and age charts are dropped. Rejected: one combined endpoint (pays for every tab on every
  visit) and a generic metric-query endpoint (a query language before anything needs one). The step-2 daily summary
  table (ADR-024) feeds Overview through `SummaryService`; the other sections read orders live and can move onto the
  table behind the same ports when volume needs it.
```

- [ ] **Step 3: Rewrite the Analytics help article**

In `ecommerce-frontend/src/help/adminGuides.ts`, replace the `body` of the `analytics` article with:

```ts
    summary: "Seven tabs of shop numbers for any date range, each with a CSV export.",
    body: [
      { type: "p", text: "Pick a date range, what to compare it with and a category at the top; every tab except Stock follows them. Green changes are up, red are down. Export CSV downloads the tab's main table." },
      { type: "steps", title: "The tabs", items: [
        "Overview: revenue, orders, average order value and units, the trend, and when in the week orders come in.",
        "Sales: revenue by category and payment method, and where the money went (tax, shipping, discounts, refunds).",
        "Orders: status, how long orders take to ship and deliver, orders stuck in Processing, order size and coupons.",
        "Stock: what is in stock, running low or out, how many days it will last at the last 30 days' rate, and its value.",
        "Customers: new accounts, first-time vs returning buyers, repeat rate and top customers.",
        "Demand: best sellers, what is rising or falling, stock that is not selling, and products bought together.",
        "Location: orders by country, Indian state and PIN area; click a row to drill down.",
      ] },
      { type: "tip", text: "When an order counts as stuck, and the low-stock level, are set on Store settings." },
    ],
```

- [ ] **Step 4: Update the spec status**

In the spec, change the status line to: `Status: **approved 2026-10-20; implemented on feat/analytics per docs/superpowers/plans/2026-10-20-analytics-sections.md (see its "Changes from the spec").**`

- [ ] **Step 5: Run everything**

Run: `cd backend && mvn -q verify` then `cd ../ecommerce-frontend && npm test && npm run lint && npm run build`
Expected: all green. Report the test counts.

- [ ] **Step 6: Browser pass**

Start the stack the way the project runs locally (separate `MONGO_DB` from the docker stack — see the repo's run notes), sign in as the store admin, open `/admin/analytics`, and for each of the seven tabs at 1280px wide and at 390px wide:
- the tab loads and shows numbers or an empty state (no "Could not load");
- scroll to the last card: it is reachable;
- Export CSV downloads a file whose name starts with the tab name;
- switching the date range, comparison and category updates the tab;
- Location: drill India → a state → a PIN area and back with the breadcrumb;
- Demand: switch rank and grouping;
- Stock: a product at its low-stock level appears under "Running low".
At checkout, the State field is a dropdown for India and the order saves. Take one screenshot per tab at 1280px for the report.

- [ ] **Step 7: Commit**

```bash
git add docs ecommerce-frontend/src/help
git commit -m "docs(analytics): section contracts, ADR-025 and the help article

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Amendment 1 (2026-10-21): build on `dev`'s step 3

While Tasks 1–5 ran, another session merged its own step 3 into `dev` (`a8b2287`, ADR-025): tabs **Products, Customers, Coupons, Payments, Tax** via `DashboardSectionsController` / `SectionsService` / `SectionsReadPort` / `MongoSectionsReadAdapter`, date-range only (no compare/category/basis), every table downloadable from the audited `GET /dashboard/export/{table}` (`SectionCsv`, `AuditAction.ANALYTICS_EXPORTED`), frontend `components/admin/AnalyticsSections.tsx`, `utils/sections.ts` (`SECTION_TABS`, `downloadCsv`), `types/sections.ts`. The user chose: **keep `dev`'s five tabs and add only what they lack.**

**Status of the original tasks:** 1–5 done (Overview, Sales, Orders, stuck-order setting). **Dropped:** 6 (Stock — `dev` Products has low stock with days left), 7 (Customers — `dev` has it). **Replaced:** 8 → Task 16 (only trending + bought-together, added to `dev`'s Products), 10 → Task 15, 11 → Task 17, 12 → Task 18. **Kept as written:** 9.

**Execution order:** 13, 14, 9, 15, 16, 17, 18.

**Final tab bar:** Overview · Sales · Orders · Products · Customers · Coupons · Payments · Tax · Location. Compare / basis / category filters show only on Overview, Sales, Orders and Location (the `dev` tabs ignore them).

**This work's ADR is ADR-026** (`dev`'s step 3 took ADR-025). It supersedes the "ADR-025" text in Task 12.

### Task 13: Merge `dev` and make one Analytics page

**Files:** merge commit; `ecommerce-frontend/src/pages/admin/analytics.tsx`, `src/redux/api/dashboardAPI.ts`, `src/components/admin/analytics/sections.ts` (+ test), `src/utils/sections.ts` (+ test), anything else the merge conflicts on.

**Requirements:**
1. `git merge dev` into `feat/analytics` (a merge commit, no rebase). Message: `merge: dev (step 3 sections) into feat/analytics`.
2. `dashboardAPI.ts`: keep every endpoint from both sides.
3. One tab list, owned by `components/admin/analytics/sections.ts` `TABS` (it keeps `tabFrom`): `overview, sales, orders, products, customers, coupons, payments, tax` (Location is added in Task 15). Remove `SECTION_TABS`/`SectionTab` from `utils/sections.ts` if nothing else uses them; keep its export helpers. Update both test files so they still pass and `tabFrom("products")` returns `"products"`.
4. `analytics.tsx`: the toolbar (date range always; compare, basis, category only when the tab is overview/sales/orders); one `<nav className="tabs">` rendered from `TABS`; body renders `OverviewTab` / `SalesTab` / `OrdersTab` with `SectionParams`, and `dev`'s `AnalyticsSection` component with `range` for the five `dev` tabs. `dev`'s legacy inline overview (inventory by category, gender ratio, order fulfilment, stock availability, revenue distribution, age, 12-month lines, latest transactions) is removed — gender/age by decision D2, the rest superseded by the tabs. Keep the low-stock card that `OverviewTab` already has. Keep `dev`'s "range in the store's time zone" note above the `dev` tabs.
5. Backend: resolve any conflicts keeping both sides; `AnalyticsController` and `DashboardSectionsController` both map `/api/v1/dashboard` — confirm no duplicate route.
6. Verify: `mvn -q verify`, `npm test && npm run lint && npm run build`, and a browser check that every tab loads (headless is fine, with a seeded or mocked API).

### Task 14: Remove duplicates and audit this work's exports

**Files:** `SalesSection`, `SalesReads`, `MongoSalesReads`, `SalesService` (+ their tests), `OrdersSection` (+ tests), `AnalyticsController`, `SalesTab.tsx`, `OrdersTab.tsx`, `types/analytics.ts`, `_analytics.scss`.

**Requirements:**
1. Sales: remove `byCategory` and `byPaymentMethod` (and `CategoryRow`, `MethodRow`, `ProductRevenue`, `MethodTotals`, `byProduct`, `categories`, `byPaymentMethod` reads) — `dev`'s Products and Payments show them. Sales keeps: trend with comparison series, `breakdown`. The tab keeps the trend chart and the "Where the money went" table; delete their cards and any styles only they used. Update unit and IT tests (drop the removed assertions, keep the rest).
2. Orders: remove `topCoupons` from `Size` (and its UI table) — `dev`'s Coupons tab shows it. Keep size bands and average items.
3. Exports: when `respond` serves CSV, record `AuditEvent.of(AuditAction.ANALYTICS_EXPORTED).on("report", <section name>)` through `AuditTrail`, exactly as `DashboardSectionsController.export` does — ADR-025 says every export is audited. Add one IT assertion (via the existing `auditEntries(...)` helper in `IntegrationTest`) that an Overview CSV download writes that audit entry.
4. TDD for the audit assertion (red first). Run `mvn -q verify` and the frontend checks.

### Task 9: `IndianStates` — unchanged, as written above.

### Task 15: Location tab (Task 10, adapted)

Implement Task 10 as written, with these changes:
1. `LocationReads.topProducts` returns its own `record RegionProduct(String productId, String name, long units, double revenue)` (declared in `LocationReads`) instead of `DemandReads.LineSales` (Task 8 is dropped). `LocationService` maps it to `LocationSection.TopProduct`.
2. The route is added to `AnalyticsController`; its CSV goes through `respond`, so it is audited (Task 14).
3. Add `location` (label "Location") to `TABS`, last; it uses the compare/category filters.
4. `dev`'s Customers "places" table (`SectionsService.customers` → `read.places`): group rows by state normalised with `IndianStates` (state label = official name; unrecognised raw text kept as typed), so the two tabs agree. Adjust `DashboardSectionsIntegrationTest` expectations only where normalisation changes them, and add one case where "KA" and "Karnataka" merge into one place.

### Task 16: Trending and bought-together on the Products tab (from Task 8)

**Files:** `dashboard/domain/Trending.java` (new), `Sections.java` (`Products` gains two fields), `SectionsReadPort` + `MongoSectionsReadAdapter` (pairs query), `SectionsService.products`, `SectionCsv` (two tables), `DashboardSectionsIntegrationTest`, a new `TrendingTest`, `AnalyticsSections.tsx` (Products), `types/sections.ts`, `utils/sections.ts` (`ExportTable`).

**Requirements:**
1. `Trending` (pure, unit-tested): rising/falling products by change in units against the previous period of the same length (`Period.previous()`); minimum 3 units in either period; no previous sales → `isNew: true`, change null; ranked by unit delta; `limit` rows each. Port Task 8's `Trending` but over `Sections.ProductSales` (productId, name, units). Row: `productId, name, units, previousUnits, change, isNew`.
2. Bought together: pairs of distinct products in the same order, ≥ 3 shared orders, top 10, orders with more than 20 distinct products skipped — port Task 8's pairs pipeline (with the `$ifNull` guard) into `MongoSectionsReadAdapter`, through `TenantMongo` like the rest of that adapter. Names from the latest order item name, or the product; "Removed product" when unknown.
3. `Sections.Products` gains `Trending trending` and `List<Pair> boughtTogether` (`Pair(productA, nameA, productB, nameB, orders)`); `SectionCsv` gains tables `trending` and `bought-together`; `ExportTable` gains both.
4. UI: two cards on the Products tab (Rising / Falling as one card with two lists, and "Often bought together"), each with the existing CSV button.
5. TDD: unit tests for `Trending` (min 3, new items, ordering, empty), IT asserting trending and pairs on seeded data and both CSV tables.

### Task 17: State dropdown at checkout, state-aware invoices and GST report (Task 11, adapted)

Implement Task 11 as written, plus:
1. `SectionsService.sameState` (the Tax report) uses the same normalised comparison as `Invoice.sameState` — put one shared helper in `IndianStates` (`static boolean same(String a, String b)`: same state when both normalise; else trimmed case-insensitive text match; false for null/blank) and call it from both; `InvoiceSameStateTest` tests the helper's behaviour through `Invoice` or directly.
2. `docs/CHALLENGES_AND_LIMITATIONS.md` P3: mark it fixed for Indian addresses (states are picked from a list and stored by official name; old free-text values are normalised when compared) and keep what remains open (no state code stored on addresses).

### Task 18: Docs and browser pass (Task 12, adapted)

Implement Task 12 with these changes:
1. Contracts: document only `/overview`, `/sales` (trend + breakdown), `/orders` (no `topCoupons`), `/location`, `GET /api/v1/reference/indian-states`, the `?format=csv` audited exports, `commerce.stuckOrderDays`; update `dev`'s Products entry with `trending` and `boughtTogether` and its export table list.
2. ADR-026 (not 025): "Analytics adds Overview, Sales, Orders and Location tabs beside step 3's sections" — one endpoint and read port per added tab, CSV via `?format=csv` audited like ADR-025's exports, Indian states normalised with `IndianStates`, stuck orders as a store setting; rejected: duplicating step 3's tabs.
3. Help article: merge into `dev`'s Analytics article (it already describes Products…Tax); add Overview, Sales, Orders, Location lines and the Products additions; don't duplicate.
4. `.env.example`: add `STORE_STUCK_ORDER_DAYS=3` next to `STORE_MINIMUM_AGE`.
5. `docs/CHALLENGES_AND_LIMITATIONS.md`: add any new limits this work introduced (e.g. cached order rows on very large stores, refund rate vs amount time bases).
6. Spec status line: implemented per this plan and Amendment 1.
7. Browser pass over all nine tabs at 1280px and 390px as Task 12 describes.

---

## Amendment 2 (2026-10-21): Audience — sales by gender and age band

User decision (relayed by the "Analytics visibility" session): *"gender age charts are not necessary. but can provide sales filter by these category that would tell the target audience"* — chose **breakdown + filter**, added to this plan. Decision D2 stands: no gender/age pie charts.

**Execution order after Amendment 1:** 14, 9, 15, 16, **19, 20, 21**, 17, 18. Task 18 also documents this amendment (contracts, ADR-026 privacy threshold, help lines).

**Shared rules (all three tasks):**
- Gender segments: `female`, `male`, `unknown` (gender is optional at sign-up; null or an erased account is unknown).
- Age bands: `18-24`, `25-34`, `35-44`, `45-54`, `55+`, `unknown` (sign-up enforces 18+). Age is taken **at the period's end date in the store's zone** — one rule for the breakdown and the filter; a birthday inside the period can move a buyer up one band. State this in the contract and the UI note.
- **Privacy threshold:** any cell or filtered result with fewer than **5** distinct buyers in the period is `small: true` with its figures null ("fewer than 5 buyers"); CSV leaves those cells blank; no per-person rows anywhere. 5 is a fixed analysis constant (like trending's 3), not a store setting.
- No `$lookup` from orders to users (`orders.user` is a string, `users._id` an ObjectId — it would defeat the index). Breakdown: aggregate orders grouped by `user` (orders, revenue), then load those users' `gender` and `dob` with one `$in` query and bin them in Java. Filter: resolve matching user ids from `users` (gender, plus dob bounds for the band at the period end), then add `user $in ids` to the order match. All through `TenantMongo`.

### Task 19: `AudienceBands` and the Audience table on the Customers tab

**Files:** `dashboard/domain/AudienceBands.java` (+ `AudienceBandsTest`), `Sections.java` (`Customers` gains `Audience audience`), `SectionsReadPort` + `MongoSectionsReadAdapter` (buyers by user, users' gender/dob by id), `SectionsService.customers`, `SectionCsv` (table `audience`), `DashboardSectionsIntegrationTest`, `AnalyticsSections.tsx` (Customers), `types/sections.ts`, `utils/sections.ts` (`ExportTable`).

**Requirements:**
1. `AudienceBands` (pure): `gender(String raw) → female|male|unknown`; `band(LocalDate dob, LocalDate asOf) → 18-24 … 55+|unknown`; `table(List<Buyer> buyers, LocalDate asOf)` → rows = gender (+ total row), columns = band (+ total column), each cell `{buyers, orders, revenue, share}` where share = revenue share of the period; cells/totals with < 5 buyers → `small: true`, figures null; constant `MIN_BUYERS = 5`. `Buyer(userId, gender, dob, orders, revenue)`.
2. `Sections.Customers` gains `audience` (`Audience(asOf, List<String> bands, List<AudienceRow> rows)` — shape your choice, documented in the record's Javadoc).
3. CSV table `audience`: one line per gender × band (+ totals), columns `gender, age_band, buyers, orders, revenue, share_percent`; small cells' numbers blank. Audited like the other exports (it goes through `/export/{table}`).
4. UI: an "Audience" card on Customers — a gender × band grid; small cells show "fewer than 5 buyers"; note stating the age rule. CSV button as on the other cards.
5. TDD: band edges (birthday exactly on the period end; 24→25 boundary; under-18/future dob → unknown), unknown gender/dob, 4 vs 5 buyers threshold, totals, tenant isolation, CSV blanks for small cells.

### Task 20: `gender` and `ageBand` filters on Sales and Products

**Files:** `SectionRequest`/`SectionQuery` or a small `Audience` filter value (your choice, one place parses and validates), `SalesService`/`SalesReads` path, `SectionsService.products` + its controller params + cache keys, a shared user-id resolver in `dashboard.infra.mongo`, `analytics.tsx` (two selects shown only on Sales and Products, kept in the URL), `types/*`, tests.

**Requirements:**
1. Query params `gender` (`female|male|unknown`) and `ageBand` (`18-24|25-34|35-44|45-54|55+|unknown`) on `/sales` and `/products` (and their CSV exports). Invalid values → 400 in the `/summary` message style (`gender: unknown value`).
2. Resolve matching user ids (age at the period end in the store zone) and add `user $in ids` to every order match those sections run. Segment-filtered requests never read `daily_sales` (they go live). The comparison period on Sales uses the same segment, with ages taken at the comparison period's end.
3. Privacy: if the filtered period has fewer than 5 distinct buyers, the response says so (`small: true`) and returns no figures/rows; the UI shows "fewer than 5 buyers in this group".
4. Cache keys include the segment.
5. TDD: a filter that excludes other segments (numbers change as expected), the small-result case, invalid values → 400, tenant isolation, Products top sellers filtered.

### Task 21: Privacy Policy and compliance note for the new purpose

**Files:** `ecommerce-frontend/src/legal/policies.ts`, `docs/foundations/compliance-consent.md`, any legal-version constant/config the repo uses.

**Requirements:**
1. The Privacy Policy currently says DOB is used only to confirm 18+ and gender only for anonymous store statistics. Change it to say DOB is also used for **anonymous age-band statistics** (with the 5-buyer minimum stated plainly), and gender for anonymous statistics including sales by audience.
2. Record the new processing purpose in `compliance-consent.md`.
3. Find the repo's rule for material policy changes (version bump → re-consent). **Do not bump the version on your own:** report what the rule says and what a bump would trigger; the controller asks the user. Commit the text change; leave the version change for the user's decision.
