# Coupon Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the flat `{code, amount}` coupon into a rule-based coupon: flat or percent discounts, a cap, a minimum order value, a validity window, a total usage limit, a per-customer limit and an active switch. Admins can edit rules, see each coupon's status and usage, and customers get the exact reason a coupon was refused.

**Architecture:** Same pattern as plans 1 to 3: new optional fields live in a nested `CouponRules` record on `Coupon`, so the 3-argument constructor and legacy `coupons` documents (just a code and an amount) keep working as always-active flat coupons with no migration. All eligibility logic is one pure method, `Coupon.evaluate`, used for the cart preview and for checkout. Redemptions are counted in the coupon module only (a `usedCount` on the coupon plus a per-user counter collection), so the coupon module never depends on `order`.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.13, Spring Data MongoDB, JUnit 5 + AssertJ + MockMvc + Testcontainers `mongo:7.0`; React + TypeScript + Redux Toolkit Query.

**Spec:** `docs/superpowers/specs/2026-10-17-richer-entities-design.md` (§4 Entities: Coupon row, delivery step 4). Plan 4 of 4. **Requires plans 1 to 3 in the base branch** (checkout reads `couponCode` on orders and the order-details page).

## Rulings built into this plan

1. **A coupon is redeemed when the order is created, not when checkout opens.** Checkout only checks eligibility. If the last use is taken between a customer's checkout and their payment confirmation, their order still goes through (they have already paid the discounted price), so `usedCount` can exceed `usageLimit` by at most the number of checkouts that were open at the same moment. New checkouts are refused as soon as `usedCount >= usageLimit`. *Cost if wrong:* a few extra uses on a limited coupon; the alternative is a paid-but-unfulfilled order or a reservation system with expiry.
2. **Deleting or cancelling an order does not give a use back.** Admin order deletion is rare and used for clean-ups, and a per-order redemption ledger is not worth it yet. *Cost if wrong:* an admin can raise `usageLimit` by hand.
3. **Percent coupons need the cart subtotal.** `GET /payment/discount` takes an optional `subtotal`. Without it a percent coupon previews as ₹0 and flat coupons behave exactly as before. The server always recomputes at checkout.
4. **Editing cannot clear a field** (same rule as plans 1 to 3: omitted means keep). To stop a coupon, set `active=false`; to remove an expiry or limit, create a new coupon. `type` and `coupon` (the code) cannot be edited, because changing the type would silently reinterpret `amount`.
5. **Not in this plan** (Tier 2 in the spec): scoping to products or categories and first-order-only coupons.

## Global Constraints

- Keep the existing routes under `/api/v1/payment` and the JSON names `coupon` (the code) and `amount`. A request or stored document with only those two fields must behave as before: an always-active flat coupon.
- New fields are optional on read. No migration; only a new collection `coupon_redemptions` and its index are added.
- Module boundary: the `coupon` module's public API is `com.sixpack.coupon.CouponLookup`. It may listen to `com.sixpack.user.UserDeletedEvent` (public) but must not depend on `order`. `ModularityTests` must stay green.
- Personal data: `coupon_redemptions` rows carry a user id, so they are deleted when the user is (ADR-014). The coupon's `usedCount` is an anonymous counter and stays.
- Money in rupees as `double` in the API; the discount is rounded to paise.
- Errors use `ApiException`; responses use `ApiResponse`.
- Backend tests: `cd backend && mvn -q -Dtest=<Class> test` (Docker running). Final gate: `mvn verify`, `npm run build`, `npm run lint`.

## Review Focus

1. A coupon stored before this change (just a code and an amount) must still validate and apply as an always-active flat coupon (Tasks 2, 3, 4).
2. Usage limits must hold across sequential orders, and the per-customer limit must apply per customer, not globally (Task 4).
3. A rejected coupon must say why (expired, not started, inactive, limit reached, minimum order) and must never produce a 500 or a negative or oversized discount (Tasks 1, 3, 4).
4. Percent discounts must respect the cap and round to paise, and the discount must never exceed the subtotal (Tasks 1, 4).
5. Deleting a user must delete their per-coupon usage rows but keep the coupon's total count (Task 4).

---

## File Structure

Backend (`backend/src/main/java/com/sixpack/coupon/`):

| File | Responsibility |
|---|---|
| `domain/CouponType.java`, `CouponRules.java`, `Evaluation.java` (create) | Type, rules bundle (with `LEGACY`), evaluation result |
| `domain/Coupon.java` (modify) | Add `rules`, `usedCount`; `evaluate`, `status` |
| `domain/CouponRepository.java` (modify) | `findById`, redemption counters |
| `infra/mongo/CouponDocument.java`, `MongoCouponRepository.java` (modify); `CouponRedemptionDocument.java` (create) | Persist rules, atomic counters |
| `CouponLookup.java` (modify) | Public API: `discountFor(code, subtotal, userId)`, `redeem`, `release` |
| `application/CouponService.java` (modify); `UserErasureListener.java` (create) | Rules validation, redemption, erasure |
| `web/CouponController.java` (modify) | Rich create, new update, view with status, preview with subtotal |

Also `order/application/CheckoutService.java` (modify), frontend files in Task 5, docs in Task 6.

---

### Task 1: Domain rules and evaluation

**Files:**
- Create: `backend/src/main/java/com/sixpack/coupon/domain/CouponType.java`, `CouponRules.java`, `Evaluation.java`
- Modify: `backend/src/main/java/com/sixpack/coupon/domain/Coupon.java`
- Test: `backend/src/test/java/com/sixpack/coupon/domain/CouponDomainTest.java`

**Interfaces:**
- Produces:
  - `enum CouponType { FLAT, PERCENT; static CouponType parse(String) }` (`parse` throws `ApiException` 400)
  - `record CouponRules(CouponType type, Double maxDiscount, Double minOrderValue, Instant startsAt, Instant expiresAt, Integer usageLimit, Integer perUserLimit, boolean active)` with `static final CouponRules LEGACY` (FLAT, no limits, active)
  - `record Evaluation(double discount, String rejection)` with `boolean accepted()`, `static Evaluation accept(double)`, `static Evaluation reject(String)`
  - `Coupon(String id, String code, double amount, CouponRules rules, int usedCount)` plus the old 3-arg constructor (rules = `LEGACY`, usedCount 0)
  - `Evaluation Coupon.evaluate(double subtotal, int userRedemptions, Instant now)` and `String Coupon.status(Instant now)` returning `inactive | scheduled | expired | exhausted | active`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/coupon/domain/CouponDomainTest.java`:

```java
package com.sixpack.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CouponDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final Instant PAST = NOW.minusSeconds(86_400);
    private static final Instant FUTURE = NOW.plusSeconds(86_400);

    private static Coupon coupon(double amount, CouponRules rules, int used) {
        return new Coupon("c1", "SAVE", amount, rules, used);
    }

    private static CouponRules rules(CouponType type, Double max, Double min, Instant from, Instant to,
                                     Integer limit, Integer perUser, boolean active) {
        return new CouponRules(type, max, min, from, to, limit, perUser, active);
    }

    @Test
    void legacyCouponsAreAlwaysActiveFlatDiscounts() {
        Coupon legacy = new Coupon("c1", "OLD", 100.0);
        assertThat(legacy.rules()).isEqualTo(CouponRules.LEGACY);
        assertThat(legacy.usedCount()).isZero();
        assertThat(legacy.status(NOW)).isEqualTo("active");

        Evaluation e = legacy.evaluate(1200, 0, NOW);
        assertThat(e.accepted()).isTrue();
        assertThat(e.discount()).isEqualTo(100.0);
        // Without a subtotal (old clients) a flat coupon still reports its amount.
        assertThat(legacy.evaluate(0, 0, NOW).discount()).isEqualTo(100.0);
    }

    @Test
    void percentDiscountsRoundToPaiseAndRespectTheCap() {
        Coupon tenPercent = coupon(10, rules(CouponType.PERCENT, null, null, null, null, null, null, true), 0);
        assertThat(tenPercent.evaluate(1234.57, 0, NOW).discount()).isEqualTo(123.46);
        assertThat(tenPercent.evaluate(0, 0, NOW).discount()).isZero();

        Coupon capped = coupon(10, rules(CouponType.PERCENT, 50.0, null, null, null, null, null, true), 0);
        assertThat(capped.evaluate(1200, 0, NOW).discount()).isEqualTo(50.0);
        assertThat(capped.evaluate(200, 0, NOW).discount()).isEqualTo(20.0);
    }

    @Test
    void minimumOrderValueIsEnforcedWithAReadableReason() {
        Coupon c = coupon(100, rules(CouponType.FLAT, null, 500.0, null, null, null, null, true), 0);
        Evaluation low = c.evaluate(499.99, 0, NOW);
        assertThat(low.accepted()).isFalse();
        assertThat(low.rejection()).isEqualTo("Minimum order value for this coupon is ₹500");
        assertThat(c.evaluate(500, 0, NOW).accepted()).isTrue();

        Coupon fractional = coupon(10, rules(CouponType.FLAT, null, 499.5, null, null, null, null, true), 0);
        assertThat(fractional.evaluate(100, 0, NOW).rejection()).endsWith("₹499.50");
    }

    @Test
    void validityWindowAndActiveSwitch() {
        assertThat(coupon(10, rules(CouponType.FLAT, null, null, FUTURE, null, null, null, true), 0)
                .evaluate(1000, 0, NOW).rejection()).isEqualTo("This coupon is not valid yet");
        assertThat(coupon(10, rules(CouponType.FLAT, null, null, null, PAST, null, null, true), 0)
                .evaluate(1000, 0, NOW).rejection()).isEqualTo("This coupon has expired");
        assertThat(coupon(10, rules(CouponType.FLAT, null, null, null, NOW, null, null, true), 0)
                .evaluate(1000, 0, NOW).rejection()).isEqualTo("This coupon has expired"); // expiry is exclusive
        assertThat(coupon(10, rules(CouponType.FLAT, null, null, NOW, FUTURE, null, null, true), 0)
                .evaluate(1000, 0, NOW).accepted()).isTrue(); // start is inclusive
        assertThat(coupon(10, rules(CouponType.FLAT, null, null, null, null, null, null, false), 0)
                .evaluate(1000, 0, NOW).rejection()).isEqualTo("This coupon is not active");
    }

    @Test
    void usageLimitsAreTotalAndPerCustomer() {
        Coupon c = coupon(10, rules(CouponType.FLAT, null, null, null, null, 3, 1, true), 2);
        assertThat(c.evaluate(1000, 0, NOW).accepted()).isTrue();
        assertThat(c.evaluate(1000, 1, NOW).rejection()).isEqualTo("You have already used this coupon");

        Coupon exhausted = coupon(10, rules(CouponType.FLAT, null, null, null, null, 3, null, true), 3);
        assertThat(exhausted.evaluate(1000, 0, NOW).rejection()).isEqualTo("This coupon's usage limit has been reached");
        // Over-redeemed coupons (see the plan's ruling 1) are still just "reached".
        assertThat(coupon(10, rules(CouponType.FLAT, null, null, null, null, 3, null, true), 5)
                .evaluate(1000, 0, NOW).accepted()).isFalse();
    }

    @Test
    void statusReflectsTheMostRelevantReason() {
        assertThat(coupon(1, rules(CouponType.FLAT, null, null, null, null, null, null, false), 0).status(NOW))
                .isEqualTo("inactive");
        assertThat(coupon(1, rules(CouponType.FLAT, null, null, FUTURE, null, null, null, true), 0).status(NOW))
                .isEqualTo("scheduled");
        assertThat(coupon(1, rules(CouponType.FLAT, null, null, null, PAST, null, null, true), 0).status(NOW))
                .isEqualTo("expired");
        assertThat(coupon(1, rules(CouponType.FLAT, null, null, null, null, 2, null, true), 2).status(NOW))
                .isEqualTo("exhausted");
        assertThat(coupon(1, rules(CouponType.FLAT, null, null, null, null, 2, null, true), 1).status(NOW))
                .isEqualTo("active");
    }

    @Test
    void typeParsingAndDefaults() {
        assertThat(CouponType.parse(" percent ")).isEqualTo(CouponType.PERCENT);
        assertThat(CouponType.parse(null)).isEqualTo(CouponType.FLAT);
        assertThat(CouponType.parse("")).isEqualTo(CouponType.FLAT);
        assertThatThrownBy(() -> CouponType.parse("bogus")).isInstanceOf(ApiException.class);
        assertThat(new CouponRules(null, null, null, null, null, null, null, true).type()).isEqualTo(CouponType.FLAT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=CouponDomainTest test`
Expected: compilation FAIL (`CouponRules`, `CouponType`, `Evaluation` do not exist).

- [ ] **Step 3: Write minimal implementation**

`CouponType.java`:

```java
package com.sixpack.coupon.domain;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** FLAT: {@code amount} rupees off. PERCENT: {@code amount} percent off, optionally capped. */
public enum CouponType {
    FLAT, PERCENT;

    /** Blank or null means FLAT (the only kind that existed before). */
    public static CouponType parse(String input) {
        if (input == null || input.isBlank()) {
            return FLAT;
        }
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("type: must be FLAT or PERCENT");
        }
    }
}
```

`CouponRules.java`:

```java
package com.sixpack.coupon.domain;

import java.time.Instant;

/**
 * Eligibility rules. Coupons stored before rules existed carry {@link #LEGACY}.
 *
 * @param maxDiscount   rupee cap for PERCENT coupons, or null
 * @param minOrderValue smallest cart subtotal that qualifies, or null
 * @param startsAt      first moment the coupon works (inclusive), or null
 * @param expiresAt     first moment it no longer works (exclusive), or null
 * @param usageLimit    total redemptions allowed, or null
 * @param perUserLimit  redemptions allowed per customer, or null
 */
public record CouponRules(CouponType type, Double maxDiscount, Double minOrderValue, Instant startsAt,
                          Instant expiresAt, Integer usageLimit, Integer perUserLimit, boolean active) {

    public static final CouponRules LEGACY = new CouponRules(CouponType.FLAT, null, null, null, null, null, null, true);

    public CouponRules {
        type = type == null ? CouponType.FLAT : type;
    }
}
```

`Evaluation.java`:

```java
package com.sixpack.coupon.domain;

/** Outcome of checking a coupon against a cart: either a discount, or the reason it was refused. */
public record Evaluation(double discount, String rejection) {

    public static Evaluation accept(double discount) {
        return new Evaluation(discount, null);
    }

    public static Evaluation reject(String reason) {
        return new Evaluation(0, reason);
    }

    public boolean accepted() {
        return rejection == null;
    }
}
```

`Coupon.java`:

```java
package com.sixpack.coupon.domain;

import java.time.Instant;

/**
 * @param amount rupees for FLAT, percent for PERCENT
 */
public record Coupon(String id, String code, double amount, CouponRules rules, int usedCount) {

    public Coupon {
        rules = rules == null ? CouponRules.LEGACY : rules;
    }

    /** Pre-rules shape, kept so existing callers compile unchanged. */
    public Coupon(String id, String code, double amount) {
        this(id, code, amount, CouponRules.LEGACY, 0);
    }

    public Coupon withRules(double newAmount, CouponRules newRules) {
        return new Coupon(id, code, newAmount, newRules, usedCount);
    }

    /**
     * @param subtotal        cart subtotal in rupees (0 when unknown, for example an old client previewing a code)
     * @param userRedemptions how many times this customer has already used the coupon
     */
    public Evaluation evaluate(double subtotal, int userRedemptions, Instant now) {
        if (!rules.active()) {
            return Evaluation.reject("This coupon is not active");
        }
        if (rules.startsAt() != null && now.isBefore(rules.startsAt())) {
            return Evaluation.reject("This coupon is not valid yet");
        }
        if (rules.expiresAt() != null && !now.isBefore(rules.expiresAt())) {
            return Evaluation.reject("This coupon has expired");
        }
        if (rules.usageLimit() != null && usedCount >= rules.usageLimit()) {
            return Evaluation.reject("This coupon's usage limit has been reached");
        }
        if (rules.perUserLimit() != null && userRedemptions >= rules.perUserLimit()) {
            return Evaluation.reject("You have already used this coupon");
        }
        if (rules.minOrderValue() != null && subtotal > 0 && subtotal < rules.minOrderValue()) {
            return Evaluation.reject("Minimum order value for this coupon is ₹" + rupees(rules.minOrderValue()));
        }
        return Evaluation.accept(discountFor(subtotal));
    }

    /** inactive, scheduled, expired, exhausted or active: for the admin list. */
    public String status(Instant now) {
        if (!rules.active()) {
            return "inactive";
        }
        if (rules.startsAt() != null && now.isBefore(rules.startsAt())) {
            return "scheduled";
        }
        if (rules.expiresAt() != null && !now.isBefore(rules.expiresAt())) {
            return "expired";
        }
        if (rules.usageLimit() != null && usedCount >= rules.usageLimit()) {
            return "exhausted";
        }
        return "active";
    }

    private double discountFor(double subtotal) {
        if (rules.type() == CouponType.FLAT) {
            return amount;
        }
        double percent = Math.round(subtotal * 100 * amount / 100.0) / 100.0;
        return rules.maxDiscount() == null ? percent : Math.min(percent, rules.maxDiscount());
    }

    private static String rupees(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format("%.2f", value);
    }
}
```

(The minimum-order check is skipped when `subtotal` is 0 so an old client that previews a code without a subtotal is not told the cart is too small.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=CouponDomainTest test`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(coupon): add coupon rules and a pure eligibility evaluation to the domain"
```

---

### Task 2: Persist rules and redemption counters

**Files:**
- Modify: `backend/src/main/java/com/sixpack/coupon/domain/CouponRepository.java`
- Modify: `backend/src/main/java/com/sixpack/coupon/infra/mongo/CouponDocument.java`, `MongoCouponRepository.java`
- Create: `backend/src/main/java/com/sixpack/coupon/infra/mongo/CouponRedemptionDocument.java`
- Test: `backend/src/test/java/com/sixpack/it/CouponPersistenceIntegrationTest.java` (create)

**Interfaces:**
- Consumes: Task 1 types.
- Produces (on `CouponRepository`): `Optional<Coupon> findById(String id)`; `int redemptionsBy(String couponId, String userId)`; `void recordRedemption(String couponId, String userId)` (increments the coupon's `usedCount` and the user's counter); `void releaseRedemption(String couponId, String userId)` (decrements both, never below zero); `void deleteRedemptionsOfUser(String userId)`. `save` writes rules but never touches `usedCount` of an existing coupon.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/CouponPersistenceIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRepository;
import com.sixpack.coupon.domain.CouponRules;
import com.sixpack.coupon.domain.CouponType;
import java.time.Instant;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CouponPersistenceIntegrationTest extends IntegrationTest {

    @Autowired
    CouponRepository coupons;

    @Test
    void roundTripsEveryRule() {
        Instant from = Instant.parse("2026-10-18T10:00:00Z");
        Instant to = Instant.parse("2026-12-31T18:30:00Z");
        Coupon saved = coupons.save(new Coupon(null, "FESTIVE", 15, new CouponRules(CouponType.PERCENT, 200.0, 999.0,
                from, to, 100, 2, false), 0));

        Coupon loaded = coupons.findById(saved.id()).orElseThrow();

        assertThat(loaded.rules()).isEqualTo(saved.rules());
        assertThat(loaded.amount()).isEqualTo(15.0);
        assertThat(coupons.findByCode("FESTIVE")).map(Coupon::id).contains(saved.id());
    }

    @Test
    void legacyDocumentLoadsAsAnActiveFlatCoupon() {
        ObjectId id = new ObjectId();
        mongo.getCollection("coupons").insertOne(new Document("_id", id).append("coupon", "OLD").append("amount", 100.0));

        Coupon c = coupons.findById(id.toHexString()).orElseThrow();

        assertThat(c.rules()).isEqualTo(CouponRules.LEGACY);
        assertThat(c.usedCount()).isZero();
        assertThat(c.rules().active()).isTrue();
    }

    @Test
    void redemptionsAreCountedPerCouponAndPerUserAndNeverGoNegative() {
        Coupon c = coupons.save(new Coupon(null, "COUNT", 10));

        coupons.recordRedemption(c.id(), "alice");
        coupons.recordRedemption(c.id(), "alice");
        coupons.recordRedemption(c.id(), "bob");

        assertThat(coupons.findById(c.id()).orElseThrow().usedCount()).isEqualTo(3);
        assertThat(coupons.redemptionsBy(c.id(), "alice")).isEqualTo(2);
        assertThat(coupons.redemptionsBy(c.id(), "bob")).isEqualTo(1);
        assertThat(coupons.redemptionsBy(c.id(), "carol")).isZero();

        coupons.releaseRedemption(c.id(), "alice");
        assertThat(coupons.redemptionsBy(c.id(), "alice")).isEqualTo(1);
        assertThat(coupons.findById(c.id()).orElseThrow().usedCount()).isEqualTo(2);

        coupons.releaseRedemption(c.id(), "carol"); // never redeemed: nothing happens
        coupons.releaseRedemption(c.id(), "bob");
        coupons.releaseRedemption(c.id(), "bob");
        assertThat(coupons.redemptionsBy(c.id(), "bob")).isZero();
        assertThat(coupons.findById(c.id()).orElseThrow().usedCount()).isEqualTo(1);
    }

    @Test
    void savingAnEditedCouponKeepsItsUsage() {
        Coupon c = coupons.save(new Coupon(null, "KEEP", 10));
        coupons.recordRedemption(c.id(), "alice");

        Coupon edited = coupons.findById(c.id()).orElseThrow().withRules(20,
                new CouponRules(CouponType.FLAT, null, null, null, null, 5, null, true));
        coupons.save(edited);

        Coupon reloaded = coupons.findById(c.id()).orElseThrow();
        assertThat(reloaded.amount()).isEqualTo(20.0);
        assertThat(reloaded.rules().usageLimit()).isEqualTo(5);
        assertThat(reloaded.usedCount()).isEqualTo(1);
        assertThat(coupons.redemptionsBy(c.id(), "alice")).isEqualTo(1);
    }

    @Test
    void deletingAUsersRedemptionsKeepsTheCouponsTotal() {
        Coupon c = coupons.save(new Coupon(null, "ERASE", 10));
        coupons.recordRedemption(c.id(), "alice");
        coupons.recordRedemption(c.id(), "bob");

        coupons.deleteRedemptionsOfUser("alice");

        assertThat(coupons.redemptionsBy(c.id(), "alice")).isZero();
        assertThat(coupons.redemptionsBy(c.id(), "bob")).isEqualTo(1);
        assertThat(coupons.findById(c.id()).orElseThrow().usedCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=CouponPersistenceIntegrationTest test`
Expected: compilation FAIL (`findById`, `recordRedemption`… undefined).

- [ ] **Step 3: Write minimal implementation**

`CouponRepository.java`: add

```java
    Optional<Coupon> findById(String id);

    int redemptionsBy(String couponId, String userId);

    /** Counts one use against the coupon and against this customer. */
    void recordRedemption(String couponId, String userId);

    /** Undoes {@link #recordRedemption}; counters never go below zero. */
    void releaseRedemption(String couponId, String userId);

    /** Erasure: removes the per-customer counters but leaves each coupon's total. */
    void deleteRedemptionsOfUser(String userId);
```

`CouponDocument.java`: add imports `java.time.Instant`; fields after `amount`:

```java
    String type;
    Double maxDiscount;
    Double minOrderValue;
    Instant startsAt;
    Instant expiresAt;
    Integer usageLimit;
    Integer usedCount;
    Integer perUserLimit;
    Boolean active;
```

`CouponRedemptionDocument.java`:

```java
package com.sixpack.coupon.infra.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** How often one customer has used one coupon. */
@Document("coupon_redemptions")
@CompoundIndex(name = "coupon_user", def = "{'couponId': 1, 'userId': 1}", unique = true)
class CouponRedemptionDocument {
    @Id
    String id;
    String couponId;
    String userId;
    Integer count;
}
```

`MongoCouponRepository.java` (add imports `Update`, `CouponRules`, `CouponType`, `Instant`):

```java
    @Override
    public Optional<Coupon> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, CouponDocument.class)).map(MongoCouponRepository::toDomain);
    }

    @Override
    public int redemptionsBy(String couponId, String userId) {
        CouponRedemptionDocument d = mongo.findOne(redemption(couponId, userId), CouponRedemptionDocument.class);
        return d == null || d.count == null ? 0 : d.count;
    }

    @Override
    public void recordRedemption(String couponId, String userId) {
        mongo.upsert(redemption(couponId, userId), new Update().inc("count", 1), CouponRedemptionDocument.class);
        mongo.updateFirst(Query.query(Criteria.where("id").is(couponId)), new Update().inc("usedCount", 1),
                CouponDocument.class);
    }

    @Override
    public void releaseRedemption(String couponId, String userId) {
        var released = mongo.updateFirst(redemption(couponId, userId).addCriteria(Criteria.where("count").gt(0)),
                new Update().inc("count", -1), CouponRedemptionDocument.class);
        if (released.getModifiedCount() == 1) {
            mongo.updateFirst(Query.query(Criteria.where("id").is(couponId).and("usedCount").gt(0)),
                    new Update().inc("usedCount", -1), CouponDocument.class);
        }
    }

    @Override
    public void deleteRedemptionsOfUser(String userId) {
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), CouponRedemptionDocument.class);
    }

    private static Query redemption(String couponId, String userId) {
        return Query.query(Criteria.where("couponId").is(couponId).and("userId").is(userId));
    }
```

Replace `save` so that it loads the stored document first and never touches `usedCount`:

```java
    @Override
    public Coupon save(Coupon coupon) {
        CouponDocument doc = coupon.id() == null ? null : mongo.findById(coupon.id(), CouponDocument.class);
        if (doc == null) {
            doc = new CouponDocument();
            doc.usedCount = 0;
        }
        doc.id = coupon.id();
        doc.coupon = coupon.code();
        doc.amount = coupon.amount();
        CouponRules r = coupon.rules();
        doc.type = r.type().name();
        doc.maxDiscount = r.maxDiscount();
        doc.minOrderValue = r.minOrderValue();
        doc.startsAt = r.startsAt();
        doc.expiresAt = r.expiresAt();
        doc.usageLimit = r.usageLimit();
        doc.perUserLimit = r.perUserLimit();
        doc.active = r.active();
        return toDomain(mongo.save(doc));
    }
```

and `toDomain`:

```java
    private static Coupon toDomain(CouponDocument d) {
        CouponRules rules = new CouponRules(d.type == null ? null : CouponType.valueOf(d.type), d.maxDiscount,
                d.minOrderValue, d.startsAt, d.expiresAt, d.usageLimit, d.perUserLimit,
                d.active == null || d.active);
        return new Coupon(d.id, d.coupon, d.amount == null ? 0 : d.amount, rules,
                d.usedCount == null ? 0 : d.usedCount);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=CouponPersistenceIntegrationTest,CheckoutIntegrationTest,AuthorizationIntegrationTest,ModularityTests test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(coupon): persist coupon rules and atomic per-user redemption counters"
```

---

### Task 3: Service, admin API and the discount preview

**Files:**
- Modify: `backend/src/main/java/com/sixpack/coupon/CouponLookup.java`, `application/CouponService.java`, `web/CouponController.java`
- Modify: `backend/src/main/java/com/sixpack/order/application/CheckoutService.java` (only the call that no longer compiles; real integration is Task 4)
- Test: `backend/src/test/java/com/sixpack/it/CouponAdminIntegrationTest.java` (create)

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces:
  - `CouponLookup`: `double discountFor(String code, double subtotal, String userId)` (throws `ApiException` 400 with the reason; `userId` may be null for anonymous previews); `void redeem(String code, String userId)`; `void release(String code, String userId)` (both no-ops for an unknown code).
  - `CouponService`: `List<Coupon> list()`, `Coupon create(CreateCommand)`, `Coupon update(String id, UpdateCommand)`, `void delete(String id)`.
  - HTTP under `/api/v1/payment`: `GET /discount?coupon=&subtotal=` (public; counts the caller's own uses when a valid token is sent); `POST /coupon/new` body `{coupon, amount, type?, maxDiscount?, minOrderValue?, startsAt?, expiresAt?, usageLimit?, perUserLimit?, active?}` → 201; `PUT /coupon/{id}` body `{amount?, maxDiscount?, minOrderValue?, startsAt?, expiresAt?, usageLimit?, perUserLimit?, active?}`; `GET /coupon/all` returns each coupon with `type, maxDiscount, minOrderValue, startsAt, expiresAt, usageLimit, usedCount, perUserLimit, active, status`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/CouponAdminIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CouponAdminIntegrationTest extends IntegrationTest {

    private ResultActions create(String admin, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions update(String admin, String id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> coupon(String code, double amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("coupon", code);
        m.put("amount", amount);
        return m;
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andReturn()).get("coupon").get("_id").asText();
    }

    @Test
    void legacyBodyStillCreatesAnActiveFlatCoupon() throws Exception {
        String admin = adminToken();
        create(admin, coupon("SAVE100", 100)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.type").value("FLAT"))
                .andExpect(jsonPath("$.coupon.active").value(true))
                .andExpect(jsonPath("$.coupon.status").value("active"))
                .andExpect(jsonPath("$.coupon.usedCount").value(0));
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "SAVE100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discount").value(100.0));
    }

    @Test
    void createsAPercentCouponWithEveryRule() throws Exception {
        String admin = adminToken();
        Map<String, Object> m = coupon("FEST10", 10);
        m.put("type", "percent");
        m.put("maxDiscount", 150);
        m.put("minOrderValue", 500);
        m.put("startsAt", Instant.now().minusSeconds(60).toString());
        m.put("expiresAt", Instant.now().plusSeconds(86_400).toString());
        m.put("usageLimit", 100);
        m.put("perUserLimit", 2);

        create(admin, m).andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.type").value("PERCENT"))
                .andExpect(jsonPath("$.coupon.maxDiscount").value(150.0))
                .andExpect(jsonPath("$.coupon.minOrderValue").value(500.0))
                .andExpect(jsonPath("$.coupon.usageLimit").value(100))
                .andExpect(jsonPath("$.coupon.perUserLimit").value(2))
                .andExpect(jsonPath("$.coupon.status").value("active"));

        mvc.perform(get("/api/v1/payment/coupon/all").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.coupons.length()").value(1))
                .andExpect(jsonPath("$.coupons[0].coupon").value("FEST10"));
    }

    @Test
    void invalidRulesAreBadRequest() throws Exception {
        String admin = adminToken();
        Map<String, Object> over100 = coupon("BAD1", 150);
        over100.put("type", "PERCENT");
        create(admin, over100).andExpect(status().isBadRequest());

        Map<String, Object> flatWithCap = coupon("BAD2", 50);
        flatWithCap.put("maxDiscount", 20);
        create(admin, flatWithCap).andExpect(status().isBadRequest());

        Map<String, Object> reversed = coupon("BAD3", 50);
        reversed.put("startsAt", Instant.now().plusSeconds(7200).toString());
        reversed.put("expiresAt", Instant.now().plusSeconds(3600).toString());
        create(admin, reversed).andExpect(status().isBadRequest());

        Map<String, Object> alreadyExpired = coupon("BAD4", 50);
        alreadyExpired.put("expiresAt", Instant.now().minusSeconds(60).toString());
        create(admin, alreadyExpired).andExpect(status().isBadRequest());

        Map<String, Object> zeroLimit = coupon("BAD5", 50);
        zeroLimit.put("usageLimit", 0);
        create(admin, zeroLimit).andExpect(status().isBadRequest());

        Map<String, Object> badType = coupon("BAD6", 50);
        badType.put("type", "bogus");
        create(admin, badType).andExpect(status().isBadRequest());

        create(admin, coupon("DUP", 10)).andExpect(status().isCreated());
        create(admin, coupon("DUP", 10)).andExpect(status().isConflict());
    }

    @Test
    void updateChangesOnlyWhatIsSentAndKeepsUsageAndType() throws Exception {
        String admin = adminToken();
        Map<String, Object> m = coupon("EDIT", 10);
        m.put("type", "PERCENT");
        m.put("usageLimit", 5);
        String id = id(create(admin, m));

        update(admin, id, Map.of("active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.active").value(false))
                .andExpect(jsonPath("$.coupon.status").value("inactive"))
                .andExpect(jsonPath("$.coupon.amount").value(10.0))
                .andExpect(jsonPath("$.coupon.usageLimit").value(5))
                .andExpect(jsonPath("$.coupon.type").value("PERCENT"));

        update(admin, id, Map.of("amount", 20, "type", "FLAT", "active", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.amount").value(20.0))
                .andExpect(jsonPath("$.coupon.type").value("PERCENT")); // the type cannot be edited

        update(admin, id, Map.of("amount", 150)).andExpect(status().isBadRequest()); // percent over 100
        update(admin, "64b7f0000000000000000000", Map.of("active", false)).andExpect(status().isNotFound());
    }

    @Test
    void previewExplainsWhyACodeIsRefused() throws Exception {
        String admin = adminToken();
        Map<String, Object> percent = coupon("PCT", 10);
        percent.put("type", "PERCENT");
        percent.put("minOrderValue", 500);
        percent.put("maxDiscount", 100);
        String id = id(create(admin, percent));

        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT").param("subtotal", "1200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discount").value(100.0)); // 120 capped at 100
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT").param("subtotal", "800"))
                .andExpect(jsonPath("$.discount").value(80.0));
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT").param("subtotal", "200"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Minimum order value for this coupon is ₹500"));
        // An old client that sends no subtotal gets a harmless zero for a percent coupon.
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discount").value(0.0));

        update(admin, id, Map.of("active", false)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT").param("subtotal", "1200"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon is not active"));
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "NOPE").param("subtotal", "1200"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid coupon"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=CouponAdminIntegrationTest test`
Expected: FAIL (no `PUT`, rules ignored, no `status` in the response).

- [ ] **Step 3: Write minimal implementation**

`CouponLookup.java`:

```java
package com.sixpack.coupon;

/** Public API of the coupon module. */
public interface CouponLookup {

    /**
     * Checks a code against a cart and returns the ₹ discount.
     *
     * @param subtotal cart subtotal in rupees (0 if unknown)
     * @param userId   the customer, or null for an anonymous preview (per-customer limits are then skipped)
     * @throws com.sixpack.shared.ApiException 400 with a readable reason if the code is unknown or not usable
     */
    double discountFor(String code, double subtotal, String userId);

    /** Counts one use. A no-op if the code no longer exists. */
    void redeem(String code, String userId);

    /** Undoes {@link #redeem} when the order could not be created. */
    void release(String code, String userId);
}
```

`CouponService.java` (replace; keep the existing imports for `ApiException`, `List`, `Service`):

```java
package com.sixpack.coupon.application;

import com.sixpack.coupon.CouponLookup;
import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRepository;
import com.sixpack.coupon.domain.CouponRules;
import com.sixpack.coupon.domain.CouponType;
import com.sixpack.shared.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CouponService implements CouponLookup {

    private static final double MAX_FLAT = 100_000;

    /** Everything an admin can set when creating a coupon. Null means "not set". */
    public record CreateCommand(String code, double amount, String type, Double maxDiscount, Double minOrderValue,
                                Instant startsAt, Instant expiresAt, Integer usageLimit, Integer perUserLimit,
                                Boolean active) {
    }

    /** What an admin can change later. Null means "keep the current value". */
    public record UpdateCommand(Double amount, Double maxDiscount, Double minOrderValue, Instant startsAt,
                                Instant expiresAt, Integer usageLimit, Integer perUserLimit, Boolean active) {
    }

    private final CouponRepository coupons;

    CouponService(CouponRepository coupons) {
        this.coupons = coupons;
    }

    @Override
    public double discountFor(String code, double subtotal, String userId) {
        Coupon coupon = find(code).orElseThrow(() -> ApiException.badRequest("Invalid coupon"));
        int used = userId == null ? 0 : coupons.redemptionsBy(coupon.id(), userId);
        var evaluation = coupon.evaluate(subtotal, used, Instant.now());
        if (!evaluation.accepted()) {
            throw ApiException.badRequest(evaluation.rejection());
        }
        return evaluation.discount();
    }

    @Override
    public void redeem(String code, String userId) {
        find(code).ifPresent(c -> coupons.recordRedemption(c.id(), userId));
    }

    @Override
    public void release(String code, String userId) {
        find(code).ifPresent(c -> coupons.releaseRedemption(c.id(), userId));
    }

    public List<Coupon> list() {
        return coupons.findAll();
    }

    public Coupon create(CreateCommand c) {
        String code = c.code().trim();
        if (coupons.existsByCode(code)) {
            throw ApiException.conflict("Coupon already exists");
        }
        CouponType type = CouponType.parse(c.type());
        Instant now = Instant.now();
        if (c.expiresAt() != null && !c.expiresAt().isAfter(now)) {
            throw ApiException.badRequest("expiresAt: must be in the future");
        }
        CouponRules rules = new CouponRules(type, c.maxDiscount(), c.minOrderValue(), c.startsAt(), c.expiresAt(),
                c.usageLimit(), c.perUserLimit(), c.active() == null || c.active());
        validate(c.amount(), rules);
        return coupons.save(new Coupon(null, code, c.amount(), rules, 0));
    }

    public Coupon update(String id, UpdateCommand u) {
        Coupon current = coupons.findById(id).orElseThrow(() -> ApiException.notFound("Coupon not found"));
        CouponRules r = current.rules();
        CouponRules merged = new CouponRules(r.type(),
                u.maxDiscount() != null ? u.maxDiscount() : r.maxDiscount(),
                u.minOrderValue() != null ? u.minOrderValue() : r.minOrderValue(),
                u.startsAt() != null ? u.startsAt() : r.startsAt(),
                u.expiresAt() != null ? u.expiresAt() : r.expiresAt(),
                u.usageLimit() != null ? u.usageLimit() : r.usageLimit(),
                u.perUserLimit() != null ? u.perUserLimit() : r.perUserLimit(),
                u.active() != null ? u.active() : r.active());
        double amount = u.amount() != null ? u.amount() : current.amount();
        validate(amount, merged);
        return coupons.save(current.withRules(amount, merged));
    }

    public void delete(String id) {
        if (!coupons.deleteById(id)) {
            throw ApiException.notFound("Coupon not found");
        }
    }

    private java.util.Optional<Coupon> find(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        return coupons.findByCode(code.trim());
    }

    private static void validate(double amount, CouponRules rules) {
        if (amount <= 0) {
            throw ApiException.badRequest("amount: must be positive");
        }
        if (rules.type() == CouponType.PERCENT && amount > 100) {
            throw ApiException.badRequest("amount: a percent coupon cannot exceed 100");
        }
        if (rules.type() == CouponType.FLAT && amount > MAX_FLAT) {
            throw ApiException.badRequest("amount: must be at most " + (long) MAX_FLAT);
        }
        if (rules.maxDiscount() != null && rules.type() != CouponType.PERCENT) {
            throw ApiException.badRequest("maxDiscount: only applies to percent coupons");
        }
        if (rules.startsAt() != null && rules.expiresAt() != null && !rules.startsAt().isBefore(rules.expiresAt())) {
            throw ApiException.badRequest("startsAt: must be before expiresAt");
        }
        if (rules.usageLimit() != null && rules.usageLimit() < 1) {
            throw ApiException.badRequest("usageLimit: must be at least 1");
        }
        if (rules.perUserLimit() != null && rules.perUserLimit() < 1) {
            throw ApiException.badRequest("perUserLimit: must be at least 1");
        }
    }
}
```

`CouponController.java` (replace the records and the methods; keep the class annotations and `CODE_PATTERN`; add imports for the validation annotations, `Instant`, `AuthUser`, `Jwt`, `AuthenticationPrincipal`, `PutMapping`, `PathVariable`):

```java
    record NewCouponRequest(
            @NotBlank @Pattern(regexp = CODE_PATTERN, message = "3-32 letters, digits, _ or -") String coupon,
            @NotNull @Positive @DecimalMax("100000") Double amount,
            @Pattern(regexp = "(?i)flat|percent|", message = "must be FLAT or PERCENT") String type,
            @Positive @DecimalMax("100000") Double maxDiscount,
            @PositiveOrZero @DecimalMax("10000000") Double minOrderValue,
            Instant startsAt,
            Instant expiresAt,
            @Min(1) @Max(10_000_000) Integer usageLimit,
            @Min(1) @Max(1000) Integer perUserLimit,
            Boolean active) {
    }

    record UpdateCouponRequest(
            @Positive @DecimalMax("100000") Double amount,
            @Positive @DecimalMax("100000") Double maxDiscount,
            @PositiveOrZero @DecimalMax("10000000") Double minOrderValue,
            Instant startsAt,
            Instant expiresAt,
            @Min(1) @Max(10_000_000) Integer usageLimit,
            @Min(1) @Max(1000) Integer perUserLimit,
            Boolean active) {
    }

    record CouponView(@JsonProperty("_id") String id, String coupon, double amount, String type, Double maxDiscount,
                      Double minOrderValue, Instant startsAt, Instant expiresAt, Integer usageLimit, int usedCount,
                      Integer perUserLimit, boolean active, String status) {
        static CouponView from(Coupon c) {
            CouponRules r = c.rules();
            return new CouponView(c.id(), c.code(), c.amount(), r.type().name(), r.maxDiscount(), r.minOrderValue(),
                    r.startsAt(), r.expiresAt(), r.usageLimit(), c.usedCount(), r.perUserLimit(), r.active(),
                    c.status(Instant.now()));
        }
    }
```

```java
    @GetMapping("/discount")
    Map<String, Object> discount(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "Invalid coupon") String coupon,
            @RequestParam(required = false) @PositiveOrZero @DecimalMax("100000000") Double subtotal,
            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt == null ? null : AuthUser.from(jwt).id();
        return ApiResponse.of("discount", service.discountFor(coupon, subtotal == null ? 0 : subtotal, userId));
    }

    @PostMapping("/coupon/new")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@Valid @RequestBody NewCouponRequest r) {
        Coupon c = service.create(new CreateCommand(r.coupon(), r.amount(), r.type(), r.maxDiscount(),
                r.minOrderValue(), r.startsAt(), r.expiresAt(), r.usageLimit(), r.perUserLimit(), r.active()));
        return ApiResponse.of("message", "Coupon " + c.code() + " created successfully", "coupon", CouponView.from(c));
    }

    @PutMapping("/coupon/{id}")
    Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateCouponRequest r) {
        Coupon c = service.update(id, new UpdateCommand(r.amount(), r.maxDiscount(), r.minOrderValue(), r.startsAt(),
                r.expiresAt(), r.usageLimit(), r.perUserLimit(), r.active()));
        return ApiResponse.of("message", "Coupon " + c.code() + " updated successfully", "coupon", CouponView.from(c));
    }
```

(`/coupon/all` and `DELETE /coupon/{id}` stay as they are. `PUT /coupon/{id}` is already covered by the existing `/api/v1/payment/coupon/**` admin rule.)

`CheckoutService.java` (keeps compiling until Task 4 does the real work): replace the `coupons.discountFor(normalizedCoupon).orElseThrow(...)` call with `coupons.discountFor(normalizedCoupon, 0, userId)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=CouponAdminIntegrationTest,CheckoutIntegrationTest,AuthorizationIntegrationTest,CouponPersistenceIntegrationTest,ModularityTests test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(coupon): rule-based create and edit, status in the list, and a reasoned discount preview"
```

---

### Task 4: Checkout honours the rules and counts redemptions

**Files:**
- Modify: `backend/src/main/java/com/sixpack/order/application/CheckoutService.java`
- Create: `backend/src/main/java/com/sixpack/coupon/application/UserErasureListener.java`
- Test: `backend/src/test/java/com/sixpack/it/CouponCheckoutIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `CouponLookup.discountFor/redeem/release`, `UserDeletedEvent` (public, `com.sixpack.user`), `CouponRepository.deleteRedemptionsOfUser`.
- Produces: checkout computes the cart subtotal first and passes it, with the user id, to `discountFor` (so percent coupons, minimums, windows and limits all apply at the real price). `confirm` redeems the coupon after stock is reserved and before the order is saved, and releases it if saving fails. A deleted user's per-coupon counters are removed.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/CouponCheckoutIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CouponCheckoutIntegrationTest extends IntegrationTest {

    private void createCoupon(String admin, Map<String, Object> body) throws Exception {
        mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private static Map<String, Object> coupon(String code, double amount, Object... extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("coupon", code);
        m.put("amount", amount);
        for (int i = 0; i < extra.length; i += 2) {
            m.put((String) extra[i], extra[i + 1]);
        }
        return m;
    }

    private ResultActions checkout(String token, String productId, String coupon) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        req.put("coupon", coupon);
        return mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)));
    }

    private String placeOrder(String token, String productId, String coupon) throws Exception {
        String intent = body(checkout(token, productId, coupon).andExpect(status().isCreated()).andReturn())
                .get("checkout").get("providerOrderId").asText();
        Map<String, Object> req = new HashMap<>(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping()));
        return body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    private JsonNode couponRow(String admin, String code) throws Exception {
        JsonNode all = body(mvc.perform(get("/api/v1/payment/coupon/all").header("Authorization", bearer(admin)))
                .andReturn()).get("coupons");
        for (JsonNode c : all) {
            if (code.equals(c.get("coupon").asText())) {
                return c;
            }
        }
        throw new AssertionError("coupon not listed: " + code);
    }

    @Test
    void percentCouponIsCappedAndAppliedToTheRealSubtotal() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Shoe", 1200, 5, "footwear");
        createCoupon(admin, coupon("TEN", 10, "type", "PERCENT", "maxDiscount", 50));
        createCoupon(admin, coupon("TENUNCAPPED", 10, "type", "PERCENT"));
        String user = register("pct@test.dev");

        JsonNode capped = body(checkout(user, product, "TEN").andExpect(status().isCreated()).andReturn())
                .get("checkout");
        assertThat(capped.get("discount").asDouble()).isEqualTo(50.0);
        assertThat(capped.get("total").asDouble()).isEqualTo(1366.0); // 1200 + 216 tax - 50

        JsonNode full = body(checkout(user, product, "TENUNCAPPED").andExpect(status().isCreated()).andReturn())
                .get("checkout");
        assertThat(full.get("discount").asDouble()).isEqualTo(120.0);
        assertThat(full.get("total").asDouble()).isEqualTo(1296.0);
    }

    @Test
    void rulesAreEnforcedAtCheckoutWithReadableReasons() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Mug", 300, 5, "home");
        String user = register("rules@test.dev");
        createCoupon(admin, coupon("BIGONLY", 50, "minOrderValue", 1000));
        checkout(user, product, "BIGONLY").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Minimum order value for this coupon is ₹1000"));

        // Seed an expired and a not-yet-valid coupon directly: the API refuses to create an expired one.
        mongo.getCollection("coupons").insertOne(new Document("coupon", "OLDONE").append("amount", 10.0)
                .append("type", "FLAT").append("active", true)
                .append("expiresAt", Date.from(Instant.now().minusSeconds(3600))));
        mongo.getCollection("coupons").insertOne(new Document("coupon", "SOON").append("amount", 10.0)
                .append("type", "FLAT").append("active", true)
                .append("startsAt", Date.from(Instant.now().plusSeconds(3600))));
        checkout(user, product, "OLDONE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon has expired"));
        checkout(user, product, "SOON").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon is not valid yet"));

        createCoupon(admin, coupon("PAUSED", 10));
        String id = couponRow(admin, "PAUSED").get("_id").asText();
        mongo.getCollection("coupons").updateOne(new Document("_id", new ObjectId(id)),
                new Document("$set", new Document("active", false)));
        checkout(user, product, "PAUSED").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon is not active"));
    }

    @Test
    void oldStyleCouponsKeepWorking() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Pen", 500, 5, "office");
        mongo.getCollection("coupons").insertOne(new Document("coupon", "LEGACY").append("amount", 100.0));
        String user = register("legacy@test.dev");

        JsonNode quote = body(checkout(user, product, "LEGACY").andExpect(status().isCreated()).andReturn())
                .get("checkout");
        assertThat(quote.get("discount").asDouble()).isEqualTo(100.0);
        placeOrder(user, product, "LEGACY");
        assertThat(couponRow(admin, "LEGACY").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    void totalUsageLimitStopsNewCheckoutsOnceReached() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Lamp", 500, 10, "home");
        createCoupon(admin, coupon("ONCE", 50, "usageLimit", 1));
        String alice = register("alice@test.dev");
        String bob = register("bob@test.dev");

        placeOrder(alice, product, "ONCE");

        JsonNode row = couponRow(admin, "ONCE");
        assertThat(row.get("usedCount").asInt()).isEqualTo(1);
        assertThat(row.get("status").asText()).isEqualTo("exhausted");
        checkout(bob, product, "ONCE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon's usage limit has been reached"));
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "ONCE").param("subtotal", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void perCustomerLimitIsPerCustomer() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Bag", 500, 10, "fashion");
        createCoupon(admin, coupon("ONEEACH", 50, "perUserLimit", 1));
        String alice = register("alice2@test.dev");
        String bob = register("bob2@test.dev");

        placeOrder(alice, product, "ONEEACH");

        checkout(alice, product, "ONEEACH").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You have already used this coupon"));
        // The preview knows who is asking when a token is sent, and stays generous to anonymous callers.
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "ONEEACH").param("subtotal", "500")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "ONEEACH").param("subtotal", "500"))
                .andExpect(status().isOk());
        placeOrder(bob, product, "ONEEACH");
        assertThat(couponRow(admin, "ONEEACH").get("usedCount").asInt()).isEqualTo(2);
    }

    @Test
    void reactivatingACouponWorksAgain() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Cap", 500, 10, "fashion");
        createCoupon(admin, coupon("TOGGLE", 50));
        String id = couponRow(admin, "TOGGLE").get("_id").asText();
        String user = register("toggle@test.dev");

        mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
        checkout(user, product, "TOGGLE").andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")).andExpect(status().isOk());
        checkout(user, product, "TOGGLE").andExpect(status().isCreated());
    }

    @Test
    void deletingAUserRemovesTheirCounterButKeepsTheCouponTotal() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Vase", 500, 10, "home");
        createCoupon(admin, coupon("KEEPTOTAL", 50, "perUserLimit", 5));
        String user = register("erasecoupon@test.dev");
        String orderId = placeOrder(user, product, "KEEPTOTAL");
        String couponId = couponRow(admin, "KEEPTOTAL").get("_id").asText();
        assertThat(mongo.getCollection("coupon_redemptions").countDocuments(new Document("couponId", couponId)))
                .isEqualTo(1);

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/user/me").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        assertThat(mongo.getCollection("coupon_redemptions").countDocuments(new Document("couponId", couponId)))
                .isZero();
        assertThat(couponRow(admin, "KEEPTOTAL").get("usedCount").asInt()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=CouponCheckoutIntegrationTest test`
Expected: FAIL (percent coupon priced as 0, minimum/limits ignored, `usedCount` stays 0).

- [ ] **Step 3: Write minimal implementation**

`CheckoutService.checkout`: replace the coupon block with

```java
        double discount = 0;
        String normalizedCoupon = coupon == null || coupon.isBlank() ? null : coupon.trim();
        if (normalizedCoupon != null) {
            double subtotal = PriceCalculator.quote(items, 0).subtotal();
            discount = coupons.discountFor(normalizedCoupon, subtotal, userId);
        }
```

`CheckoutService.confirm`: in the order-saving `try` block, redeem first and release on failure. Replace

```java
        Instant now = Instant.now();
        Order order;
        try {
            order = orders.save(new Order(
```

with

```java
        Instant now = Instant.now();
        Order order;
        boolean couponRedeemed = false;
        try {
            if (intent.coupon() != null) {
                coupons.redeem(intent.coupon(), userId);
                couponRedeemed = true;
            }
            order = orders.save(new Order(
```

and in the `catch (RuntimeException e)` that follows, add before `catalog.releaseStock(quantities);`:

```java
            if (couponRedeemed) {
                coupons.release(intent.coupon(), userId);
            }
```

(`couponRedeemed` is assigned inside the `try`, so declare it as shown, outside the `try`, and read it in the `catch`.)

`UserErasureListener.java`:

```java
package com.sixpack.coupon.application;

import com.sixpack.coupon.domain.CouponRepository;
import com.sixpack.user.UserDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Account erasure: forget which coupons a deleted user used. Each coupon's total count stays. */
@Component
class UserErasureListener {

    private final CouponRepository coupons;

    UserErasureListener(CouponRepository coupons) {
        this.coupons = coupons;
    }

    @EventListener
    void onUserDeleted(UserDeletedEvent event) {
        coupons.deleteRedemptionsOfUser(event.userId());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=CouponCheckoutIntegrationTest,CheckoutIntegrationTest,OrderCheckoutDetailsIntegrationTest,OrderHistoryIntegrationTest,ConsentIntegrationTest,ModularityTests test`
Expected: PASS. If `ModularityTests` rejects the listener, confirm `UserDeletedEvent` lives in the `com.sixpack.user` root package (it does, as `OrderAccountPolicy` already imports it).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(coupon): checkout applies coupon rules, counts redemptions and forgets erased users"
```

---

### Task 5: Frontend — cart preview and admin coupon page

**Files:**
- Modify: `ecommerce-frontend/src/types/types.ts`, `redux/api/couponAPI.ts`, `pages/cart.tsx`
- Replace: `ecommerce-frontend/src/pages/admin/apps/coupon.tsx`
- Modify: `ecommerce-frontend/src/styles/admin-styles/_admin-ui.scss`

**Interfaces:**
- Consumes: Task 3 endpoints.
- Produces: `Coupon` type with the new fields; hooks `useLazyDiscountQuery({coupon, subtotal})`, `useNewCouponMutation`, `useUpdateCouponMutation`.

- [ ] **Step 1: Types and API**

`types.ts`: replace the `Coupon` type with

```ts
export type CouponStatus = "active" | "scheduled" | "expired" | "exhausted" | "inactive";

export type Coupon = {
  _id: string;
  coupon: string;
  amount: number;
  type: "FLAT" | "PERCENT";
  maxDiscount?: number;
  minOrderValue?: number;
  startsAt?: string;
  expiresAt?: string;
  usageLimit?: number;
  usedCount: number;
  perUserLimit?: number;
  active: boolean;
  status: CouponStatus;
};

export type NewCouponInput = {
  coupon: string;
  amount: number;
  type?: "FLAT" | "PERCENT";
  maxDiscount?: number;
  minOrderValue?: number;
  startsAt?: string;
  expiresAt?: string;
  usageLimit?: number;
  perUserLimit?: number;
  active?: boolean;
};
```

`couponAPI.ts`: import `NewCouponInput` from `../../types/types`; change the `discount` and `newCoupon` endpoints and add `updateCoupon`:

```ts
    discount: builder.query<DiscountResponse, { coupon: string; subtotal: number }>({
      query: ({ coupon, subtotal }) => ({ url: "discount", params: { coupon, subtotal } }),
    }),
```

```ts
    newCoupon: builder.mutation<MessageResponse, NewCouponInput>({
      query: (body) => ({ url: "coupon/new", method: "POST", body }),
      invalidatesTags: ["coupons"],
    }),
    updateCoupon: builder.mutation<MessageResponse, { id: string; body: Partial<NewCouponInput> }>({
      query: ({ id, body }) => ({ url: `coupon/${id}`, method: "PUT", body }),
      invalidatesTags: ["coupons"],
    }),
```

and add `useUpdateCouponMutation` to the exported hooks.

- [ ] **Step 2: Cart preview**

In `pages/cart.tsx`: import `errorMessage` from `../redux/api/baseQuery` (if not already), add `const [couponMessage, setCouponMessage] = useState("");`, destructure `subtotal` (already available) and replace the validation effect with

```tsx
  // Validate the coupon with the server after the user stops typing, and again when the subtotal changes
  // (percent coupons and minimum order values depend on it).
  useEffect(() => {
    const code = couponCode.trim();
    if (!code) {
      setCouponMessage("");
      dispatch(applyCoupon({ coupon: "", discount: 0 }));
      return;
    }
    const t = setTimeout(async () => {
      const res = await checkDiscount({ coupon: code, subtotal });
      if (res.data) {
        setCouponMessage("");
        dispatch(applyCoupon({ coupon: code, discount: res.data.discount }));
      } else {
        setCouponMessage(errorMessage(res.error, "Invalid coupon"));
        dispatch(applyCoupon({ coupon: "", discount: 0 }));
      }
    }, 600);
    return () => clearTimeout(t);
  }, [couponCode, subtotal, checkDiscount, dispatch]);
```

and show the server's reason instead of the fixed text: change `isError && (<span className="red"><LuAlertCircle /> Invalid coupon</span>)` to `couponMessage && (<span className="red"><LuAlertCircle /> {couponMessage}</span>)`, and drop the now unused `isError` from the `useLazyDiscountQuery` destructuring.

- [ ] **Step 3: Admin coupon page**

Replace `ecommerce-frontend/src/pages/admin/apps/coupon.tsx`:

```tsx
import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { LuPause, LuPlay, LuTrash2 } from "react-icons/lu";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../../components/admin/AdminLayout";
import DataTable from "../../../components/admin/DataTable";
import StatusBadge from "../../../components/admin/StatusBadge";
import { errorMessage } from "../../../redux/api/baseQuery";
import {
  useAllCouponsQuery,
  useDeleteCouponMutation,
  useNewCouponMutation,
  useUpdateCouponMutation,
} from "../../../redux/api/couponAPI";
import { Coupon as CouponType, NewCouponInput } from "../../../types/types";
import { formatDate, formatINR } from "../../../utils/features";

const randomCode = () =>
  Array.from(crypto.getRandomValues(new Uint8Array(8)))
    .map((b) => "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[b % 32])
    .join("");

type Form = {
  code: string;
  type: "FLAT" | "PERCENT";
  amount: string;
  maxDiscount: string;
  minOrderValue: string;
  startsAt: string;
  expiresAt: string;
  usageLimit: string;
  perUserLimit: string;
};

const emptyForm: Form = {
  code: "",
  type: "FLAT",
  amount: "",
  maxDiscount: "",
  minOrderValue: "",
  startsAt: "",
  expiresAt: "",
  usageLimit: "",
  perUserLimit: "",
};

const num = (v: string) => (v === "" ? undefined : Number(v));
const iso = (local: string) => (local ? new Date(local).toISOString() : undefined);

const describe = (c: CouponType) =>
  c.type === "PERCENT"
    ? `${c.amount}% off${c.maxDiscount ? ` (max ${formatINR(c.maxDiscount)})` : ""}`
    : `${formatINR(c.amount)} off`;

const Coupon = () => {
  const { data, isLoading } = useAllCouponsQuery();
  const [newCoupon, { isLoading: creating }] = useNewCouponMutation();
  const [updateCoupon] = useUpdateCouponMutation();
  const [deleteCoupon] = useDeleteCouponMutation();
  const [form, setForm] = useState<Form>(emptyForm);
  const set = (field: keyof Form) => (e: { target: { value: string } }) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const columns: Column<CouponType>[] = [
    { Header: "Code", accessor: "coupon" },
    { Header: "Discount", accessor: "amount", width: 190, Cell: ({ row }: CellProps<CouponType>) => describe(row.original) } as Column<CouponType>,
    { Header: "Min order", accessor: "minOrderValue", width: 110, Cell: ({ value }: CellProps<CouponType>) => (value ? formatINR(value) : "—") } as Column<CouponType>,
    {
      Header: "Valid",
      id: "valid",
      width: 190,
      disableSortBy: true,
      Cell: ({ row }: CellProps<CouponType>) =>
        row.original.startsAt || row.original.expiresAt
          ? `${row.original.startsAt ? formatDate(row.original.startsAt) : "now"} → ${row.original.expiresAt ? formatDate(row.original.expiresAt) : "no end"}`
          : "Always",
    } as Column<CouponType>,
    {
      Header: "Used",
      accessor: "usedCount",
      width: 100,
      Cell: ({ row }: CellProps<CouponType>) =>
        row.original.usageLimit ? `${row.original.usedCount} / ${row.original.usageLimit}` : String(row.original.usedCount),
    } as Column<CouponType>,
    { Header: "Status", accessor: "status", width: 110, Cell: ({ value }: CellProps<CouponType>) => <StatusBadge status={value} /> } as Column<CouponType>,
    {
      Header: "",
      id: "actions",
      width: 100,
      disableSortBy: true,
      Cell: ({ row }: CellProps<CouponType>) => (
        <>
          <button
            className="icon-btn"
            aria-label={row.original.active ? `Pause ${row.original.coupon}` : `Activate ${row.original.coupon}`}
            onClick={async () => {
              const res = await updateCoupon({ id: row.original._id, body: { active: !row.original.active } });
              if ("error" in res) toast.error(errorMessage(res.error));
            }}
          >
            {row.original.active ? <LuPause /> : <LuPlay />}
          </button>
          <button
            className="icon-btn icon-btn--danger"
            aria-label={`Delete ${row.original.coupon}`}
            onClick={async () => {
              if (!confirm(`Delete coupon ${row.original.coupon}?`)) return;
              const res = await deleteCoupon(row.original._id);
              if ("error" in res) toast.error(errorMessage(res.error));
              else toast.success("Coupon deleted");
            }}
          >
            <LuTrash2 />
          </button>
        </>
      ),
    } as Column<CouponType>,
  ];

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const body: NewCouponInput = {
      coupon: form.code.trim(),
      amount: Number(form.amount),
      type: form.type,
      maxDiscount: form.type === "PERCENT" ? num(form.maxDiscount) : undefined,
      minOrderValue: num(form.minOrderValue),
      startsAt: iso(form.startsAt),
      expiresAt: iso(form.expiresAt),
      usageLimit: num(form.usageLimit),
      perUserLimit: num(form.perUserLimit),
    };
    const res = await newCoupon(body);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    setForm(emptyForm);
  };

  return (
    <AdminLayout>
      <DataTable
        columns={columns}
        data={data?.coupons ?? []}
        isLoading={isLoading}
        searchPlaceholder="Search coupons…"
        actions={
          <details className="coupon-form">
            <summary className="btn btn--primary">New coupon</summary>
            <form className="card form-grid" onSubmit={submit}>
              <label>
                Code
                <input
                  value={form.code}
                  onChange={(e) => setForm((p) => ({ ...p, code: e.target.value.toUpperCase() }))}
                  pattern="[A-Za-z0-9_\-]{3,32}"
                  title="3-32 letters, digits, _ or -"
                  required
                />
              </label>
              <button type="button" className="btn" onClick={() => setForm((p) => ({ ...p, code: randomCode() }))}>
                Generate
              </button>
              <label>
                Type
                <select value={form.type} onChange={set("type")}>
                  <option value="FLAT">Flat (₹ off)</option>
                  <option value="PERCENT">Percent (% off)</option>
                </select>
              </label>
              <label>
                {form.type === "PERCENT" ? "Percent off (1–100)" : "Amount off (₹)"}
                <input type="number" min={1} max={form.type === "PERCENT" ? 100 : 100000} step="0.01"
                       value={form.amount} onChange={set("amount")} required />
              </label>
              {form.type === "PERCENT" && (
                <label>
                  Max discount (₹, optional)
                  <input type="number" min={1} value={form.maxDiscount} onChange={set("maxDiscount")} />
                </label>
              )}
              <label>
                Minimum order (₹, optional)
                <input type="number" min={0} value={form.minOrderValue} onChange={set("minOrderValue")} />
              </label>
              <label>
                Starts (optional)
                <input type="datetime-local" value={form.startsAt} onChange={set("startsAt")} />
              </label>
              <label>
                Expires (optional)
                <input type="datetime-local" value={form.expiresAt} onChange={set("expiresAt")} />
              </label>
              <label>
                Total uses (optional)
                <input type="number" min={1} value={form.usageLimit} onChange={set("usageLimit")} />
              </label>
              <label>
                Uses per customer (optional)
                <input type="number" min={1} value={form.perUserLimit} onChange={set("perUserLimit")} />
              </label>
              <button type="submit" className="btn btn--primary span-2" disabled={creating}>
                Create coupon
              </button>
            </form>
          </details>
        }
      />
    </AdminLayout>
  );
};

export default Coupon;
```

Append to the `.badge` block in `styles/admin-styles/_admin-ui.scss` (next to `&--delivered`):

```scss
  &--active { background: var(--success-subtle); color: var(--success); }
  &--scheduled { background: var(--info-subtle); color: var(--info); }
  &--expired,
  &--exhausted,
  &--inactive { background: $color-bg-tertiary; color: $color-text-secondary; }
```

and, after the `.inline-form` block:

```scss
.coupon-form {
  position: relative;

  summary {
    list-style: none;
    cursor: pointer;
  }

  form {
    position: absolute;
    right: 0;
    z-index: 10;
    width: min(32rem, 90vw);
    margin-top: 0.5rem;
  }
}
```

- [ ] **Step 4: Verify**

Run: `cd ecommerce-frontend && npm run build && npm run lint`
Expected: both succeed with no type errors or warnings. Then with the stack up: create a 10% coupon with a ₹100 cap and a ₹500 minimum, apply it in the cart with a ₹300 cart (the red message reads "Minimum order value for this coupon is ₹500"), raise the cart above ₹500 (it applies and the discount tracks the subtotal), pause it from the admin table (the cart now says "This coupon is not active"), and check that an old flat coupon still shows "₹X off".

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(coupons): percent and rule-based coupons in the cart and the admin page"
```

---

### Task 6: Docs and full verification

**Files:**
- Modify: `docs/foundations/contracts.md`, `requirements.md`, `architecture.md`, `compliance-consent.md`, `test-rollout.md`

- [ ] **Step 1: Contracts and requirements**

In `contracts.md`, replace the **Payment / coupons** table rows with:

| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /discount` | 🔓 (a valid token adds the caller's per-customer limit) | `?coupon=CODE&subtotal=` (`subtotal` optional; a percent coupon previews as ₹0 without it) | `{success, discount}`. 400 with the reason: `Invalid coupon`, `This coupon is not active`, `…is not valid yet`, `…has expired`, `…usage limit has been reached`, `You have already used this coupon`, `Minimum order value for this coupon is ₹N` |
| `GET /coupon/all` | 🛡 | — | `{success, coupons}` |
| `POST /coupon/new` | 🛡 | `{coupon, amount, type? (FLAT\|PERCENT), maxDiscount? (percent only), minOrderValue?, startsAt?, expiresAt?, usageLimit?, perUserLimit?, active?}` (ISO-8601 instants). A body with only `coupon` and `amount` still creates an always-active flat coupon | 201 `{success, message, coupon}`. 400 for a percent over 100, `maxDiscount` on a flat coupon, `startsAt` not before `expiresAt`, an expiry in the past, or a limit under 1. 409 if the code exists |
| `PUT /coupon/{id}` | 🛡 | any of `amount, maxDiscount, minOrderValue, startsAt, expiresAt, usageLimit, perUserLimit, active` (omitted means keep; `type` and the code cannot change) | `{success, message, coupon}`. 404 unknown id |
| `DELETE /coupon/{id}` | 🛡 | — | `{success, message}` |

Add the shape: `coupon = {_id, coupon, amount, type, maxDiscount?, minOrderValue?, startsAt?, expiresAt?, usageLimit?, usedCount, perUserLimit?, active, status}` with `status` one of `active | scheduled | expired | exhausted | inactive`. State that `amount` is rupees for FLAT and percent for PERCENT, that a coupon is counted when the order is created (a use can exceed `usageLimit` by the number of checkouts that were open at the same moment), and that deleting or cancelling an order does not return a use.

In `requirements.md` change `FR7` to: "Admin coupon management: flat or percent, optional cap, minimum order, validity window, total and per-customer limits, pause/resume, and a status and usage count per coupon. Customers see why a code was refused."

- [ ] **Step 2: ADR, compliance, rollout**

- `architecture.md`: add `ADR-018` dated 2026-10-18 above ADR-017: nested `CouponRules`; one pure `Coupon.evaluate` used for preview and checkout; redemption counted at order creation with the over-limit tolerance (ruling 1); counters kept in the coupon module (`usedCount` plus `coupon_redemptions`) so `coupon` never depends on `order`; usage is not returned on order deletion (ruling 2); no clearing of fields in edits (ruling 4). Rejected: reserving a use at checkout with an expiry (more moving parts than a handful of coupons need) and counting per-customer use from the orders collection (would make `coupon` depend on `order`). Product/category scoping and first-order-only stay Tier 2.
- `compliance-consent.md`: in §7 Retention add a row "Per-customer coupon usage counters (`coupon_redemptions`)": kept while the account exists, deleted on `UserDeletedEvent`; each coupon's total `usedCount` is anonymous and stays.
- `test-rollout.md`: list `CouponDomainTest`, `CouponPersistenceIntegrationTest`, `CouponAdminIntegrationTest`, `CouponCheckoutIntegrationTest`; rollout needs no migration, and the `coupon_redemptions` collection and its unique index are created at startup. Mention that existing coupons keep working as always-active flat coupons.

- [ ] **Step 3: Full verification**

```bash
cd backend && mvn verify
cd ../ecommerce-frontend && npm run build && npm run lint
```

Expected: all green, including `ModularityTests` and every pre-existing integration test.

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs: document coupon rules and ADR-018"
```

---

## Self-Review

- **Spec coverage (§4 Coupon row, Tier 1):** `type (FLAT|PERCENT)`, `maxDiscount`, `minOrderValue`, `startsAt`, `expiresAt`, `usageLimit`, `usedCount`, `perUserLimit`, `active` → Tasks 1 to 4; admin UI and cart behaviour → Task 5; docs and compliance → Task 6. Not here (Tier 2 in the spec): product/category scoping, first-order-only.
- **Placeholder scan:** none. The `CheckoutService` edits in Tasks 3 and 4 are exact replacements of existing statements; the Task 4 `catch` addition names the line it goes before.
- **Type consistency:** `Coupon(id, code, amount, rules, usedCount)` and `CouponRules` (8 components) are the same in Tasks 1, 2, 3 and in `CouponView.from`. `CouponLookup.discountFor(code, subtotal, userId)` is introduced in Task 3 (with `CheckoutService` temporarily passing 0) and used with the real subtotal in Task 4. Repository method names (`findById`, `redemptionsBy`, `recordRedemption`, `releaseRedemption`, `deleteRedemptionsOfUser`) match across Tasks 2, 3 and 4. Frontend `Coupon`/`NewCouponInput` are used consistently in `couponAPI.ts` and the admin page.
- **Review Focus coverage:** 1 → Task 2 `legacyDocumentLoadsAsAnActiveFlatCoupon`, Task 3 `legacyBodyStillCreatesAnActiveFlatCoupon`, Task 4 `oldStyleCouponsKeepWorking`; 2 → Task 4 `totalUsageLimitStopsNewCheckoutsOnceReached`, `perCustomerLimitIsPerCustomer`; 3 → Task 1 tests, Task 3 `previewExplainsWhyACodeIsRefused`, Task 4 `rulesAreEnforcedAtCheckoutWithReadableReasons`; 4 → Task 1 `percentDiscountsRoundToPaiseAndRespectTheCap`, Task 4 `percentCouponIsCappedAndAppliedToTheRealSubtotal`; 5 → Task 2 `deletingAUsersRedemptionsKeepsTheCouponsTotal`, Task 4 `deletingAUserRemovesTheirCounterButKeepsTheCouponTotal`.
