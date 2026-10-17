# Order and Payment Fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `Order` and `PaymentInfo` the Tier 1 fields from the spec: a human-readable order number, billing address, recipient name/phone, a customer contact snapshot, an append-only status history, coupon code, customer notes, per-item sku and tax rate, and payment status/amount/currency/paid-at. Pricing starts to honour the per-product tax rate.

**Architecture:** Same pattern as plans 1 and 2: new optional fields sit in a nested `OrderDetails` record on `Order`, so the 9-argument constructor and existing call sites keep compiling, and old `orders` documents load with defaults (no migration). `OrderItem`, `ShippingInfo` and `PaymentInfo` each gain trailing optional components plus a legacy constructor. All new personal data (customer snapshot, billing address, recipient name/phone, notes) is added to the existing account-erasure path so DPDP erasure keeps working.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.13, Spring Data MongoDB, JUnit 5 + AssertJ + MockMvc + Testcontainers `mongo:7.0`; React + TypeScript + Redux Toolkit Query.

**Spec:** `docs/superpowers/specs/2026-10-17-richer-entities-design.md` (§4 Entities: Order and PaymentInfo rows, delivery step 3). Plan 3 of 4. **Requires plans 1 and 2 in the base branch** (it reads `Product.sku`/`taxRate`, `UserDetails.phone`, and the saved-address picker).

## Rulings built into this plan

These depart from or interpret the spec; each is cheap to change before execution.

1. **`PaymentInfo.method` and `failureReason` are deferred.** Nothing can fill them today: the gateway port only creates orders and verifies signatures, and a failed payment never creates an order. They need a gateway "fetch payment" call or webhooks (the refund/settlement work in Tier 2). `status`, `amount`, `currency` and `paidAt` ship now. *Cost if wrong:* add two nullable fields later; no data impact.
2. **Per-item "tax" is the rate, not an amount.** Each `OrderItem` stores `taxRate` (percent). A per-line amount would disagree with the order total, which rounds to whole rupees per rate group (existing behaviour). *Cost if wrong:* derive the amount from price × quantity × rate.
3. **Pricing honours `Product.taxRate` (Task 2, separable).** A missing rate means the existing 18%, and carts where every item uses 18% compute exactly the old tax (a test proves parity). If you would rather keep a flat 18%, skip Task 2 and the cart-mirror step in Task 7; everything else still works and items simply record `taxRate: null`.
4. **No retry on a duplicate order number.** `ORD-YYYYMMDD-XXXXXX` has ~887M combinations per day; a collision hits the unique index, the existing rollback path releases stock and re-opens the payment intent, and the customer retries. *Cost if wrong:* one failed confirm in billions.
5. **Customer contact is snapshotted at confirm** (name, email, phone from the account), so invoices and notifications survive later profile edits, and is erased with the account like the address.

## Global Constraints

- Keep JSON field names and `_id` (ADR-004). `shippingInfo` and `orderItems` keep their names; new fields are additive.
- New fields are optional on read; old `orders` documents must load with defaults. No migration. Only new indexes are added.
- Module boundary: `order` may call other modules only through their public API types (`com.sixpack.user.UserDirectory`, `com.sixpack.product.ProductCatalog`). `ModularityTests` must stay green.
- Account erasure (ADR-014): every new personal-data field must be removed from a deleted user's orders; amounts, order number, coupon code, status history and item data stay for tax records.
- Money: rupees as `double` in the API; pricing stays in integer paise internally.
- Errors use `ApiException`; responses use `ApiResponse`.
- Backend tests: `cd backend && mvn -q -Dtest=<Class> test` (Docker running). Final gate: `mvn verify`, `npm run build`, `npm run lint`.

## Review Focus

1. Erasing an account must remove customer email/phone/name, billing address, recipient name/phone and notes from its orders, and keep amounts, order number and history (Task 6).
2. Orders stored before this change (no order number, no history, 3-field payment) must load, list, display and advance without errors (Tasks 3 and 5).
3. A cart where every product uses the default tax must price to exactly the same paise as before; mixed rates and a 0% rate must be right (Task 2).
4. Invalid confirm input (bad phone, oversized notes) must return 400 **without** consuming the payment intent, so the customer can fix it and retry (Task 4).
5. Status history must be append-only: no duplicate entry when advancing a delivered order, only admins can advance, and the actor is recorded (Task 5).

---

## File Structure

Backend (`backend/src/main/java/com/sixpack/`):

| File | Responsibility |
|---|---|
| `order/domain/OrderItem.java`, `ShippingInfo.java`, `PaymentInfo.java` (modify) | Trailing optional components + legacy constructors |
| `order/domain/Customer.java`, `StatusEntry.java`, `OrderDetails.java`, `OrderNumbers.java` (create) | Snapshot, history entry, nested details, number generator |
| `order/domain/Order.java` (modify) | Add `details`, history-appending `withStatus` |
| `order/domain/PaymentIntent.java` (modify) | Carry `currency` |
| `order/domain/PriceCalculator.java` (modify) | Tax per rate group |
| `order/infra/mongo/OrderDocument.java`, `MongoOrderRepository.java`, `PaymentIntentDocument.java`, `MongoPaymentIntentRepository.java` (modify) | Persist new fields, wider anonymisation |
| `order/application/CheckoutService.java`, `OrderService.java` (modify) | Build the richer order, append history |
| `order/web/OrderController.java`, `OrderView.java` (modify) | Accept and expose new fields |
| `product/ProductSnapshot.java`, `product/application/ProductCatalogService.java` (modify) | Expose sku and tax rate to checkout |
| `user/UserContact.java` (create), `user/UserDirectory.java`, `user/application/UserDirectoryService.java` (modify) | Contact lookup for the snapshot |

Frontend: types, cart reducer, `productCard`, `home`, `search`, `shipping`, `orders`, `order-details`, admin `transactionmanagement`, `utils/features.ts`.

---

### Task 1: Domain types and order numbers

**Files:**
- Create: `backend/src/main/java/com/sixpack/order/domain/Customer.java`, `StatusEntry.java`, `OrderDetails.java`, `OrderNumbers.java`
- Modify: `backend/src/main/java/com/sixpack/order/domain/OrderItem.java`, `ShippingInfo.java`, `PaymentInfo.java`, `Order.java`
- Modify: `backend/src/main/java/com/sixpack/order/application/OrderService.java` (`advance` call site, see Step 3)
- Test: `backend/src/test/java/com/sixpack/order/domain/OrderDomainTest.java`

**Interfaces:**
- Produces:
  - `OrderItem(String productId, String name, String photo, double price, int quantity, String sku, Double taxRate)` + legacy 5-arg constructor
  - `ShippingInfo(String address, String city, String state, String country, String pinCode, String name, String phone)` + legacy 5-arg constructor
  - `PaymentInfo(String provider, String orderId, String paymentId, String status, Double amount, String currency, Instant paidAt)` + legacy 3-arg constructor
  - `Customer(String name, String email, String phone)`
  - `StatusEntry(OrderStatus status, Instant at, String actor)`
  - `OrderDetails(String orderNumber, ShippingInfo billingInfo, Customer customer, List<StatusEntry> statusHistory, String couponCode, String notes)` with `static final OrderDetails EMPTY`
  - `Order(..., Instant createdAt, OrderDetails details)` + legacy 9-arg constructor; `Order withDetails(OrderDetails)`; `Order withStatus(OrderStatus next, String actor, Instant at)` (sets the status and appends a `StatusEntry`)
  - `OrderNumbers.generate(Instant now, java.util.random.RandomGenerator random) : String` → `ORD-yyyyMMdd-XXXXXX` (UTC date; 6 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`)

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/order/domain/OrderDomainTest.java`:

```java
package com.sixpack.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderDomainTest {

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    private static Order legacyOrder() {
        return new Order("o1", "u1", new ShippingInfo("1 MG Road", "Pune", "MH", "India", "411001"),
                List.of(new OrderItem("p1", "Tee", "", 250.0, 2)), new Quote(500, 90, 200, 0, 790),
                OrderStatus.PROCESSING, new PaymentInfo("fake", "po1", "pay1"), "2026-10-16", T0);
    }

    @Test
    void orderNumbersHaveTheDocumentedShape() {
        String number = OrderNumbers.generate(T0, new Random(1));
        assertThat(number).matches("ORD-20261017-[A-HJKMNP-Z2-9]{6}");
    }

    @Test
    void orderNumbersUseTheUtcDateAndRarelyCollide() {
        assertThat(OrderNumbers.generate(Instant.parse("2026-10-17T23:59:59Z"), new Random(2)))
                .startsWith("ORD-20261017-");
        assertThat(OrderNumbers.generate(Instant.parse("2026-10-18T00:00:01Z"), new Random(2)))
                .startsWith("ORD-20261018-");

        Random random = new Random(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add(OrderNumbers.generate(T0, random));
        }
        assertThat(seen).hasSize(2000);
    }

    @Test
    void legacyConstructorsLeaveNewFieldsEmpty() {
        OrderItem item = new OrderItem("p1", "Tee", "", 250.0, 2);
        assertThat(item.sku()).isNull();
        assertThat(item.taxRate()).isNull();

        ShippingInfo shipping = new ShippingInfo("a", "b", "c", "d", "e");
        assertThat(shipping.name()).isNull();
        assertThat(shipping.phone()).isNull();

        PaymentInfo payment = new PaymentInfo("fake", "po", "pay");
        assertThat(payment.status()).isNull();
        assertThat(payment.amount()).isNull();
        assertThat(payment.paidAt()).isNull();

        assertThat(legacyOrder().details()).isEqualTo(OrderDetails.EMPTY);
        assertThat(OrderDetails.EMPTY.statusHistory()).isEmpty();
        assertThat(new OrderDetails(null, null, null, null, null, null)).isEqualTo(OrderDetails.EMPTY);
    }

    @Test
    void withStatusAppendsToTheHistoryAndKeepsEverythingElse() {
        Order placed = legacyOrder().withDetails(new OrderDetails("ORD-20261017-ABCDEF", null,
                new Customer("Asha", "a@b.dev", "9876543210"),
                List.of(new StatusEntry(OrderStatus.PROCESSING, T0, "u1")), "SAVE100", "Leave at door"));

        Instant later = T0.plusSeconds(3600);
        Order shipped = placed.withStatus(OrderStatus.SHIPPED, "admin1", later);

        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.details().statusHistory()).containsExactly(
                new StatusEntry(OrderStatus.PROCESSING, T0, "u1"),
                new StatusEntry(OrderStatus.SHIPPED, later, "admin1"));
        assertThat(shipped.details().orderNumber()).isEqualTo("ORD-20261017-ABCDEF");
        assertThat(shipped.details().notes()).isEqualTo("Leave at door");
        assertThat(shipped.payment()).isEqualTo(placed.payment());
        assertThat(placed.details().statusHistory()).hasSize(1); // the original is untouched
    }

    @Test
    void withStatusOnALegacyOrderStartsTheHistory() {
        Order shipped = legacyOrder().withStatus(OrderStatus.SHIPPED, "admin1", T0);
        assertThat(shipped.details().statusHistory()).containsExactly(
                new StatusEntry(OrderStatus.SHIPPED, T0, "admin1"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=OrderDomainTest test`
Expected: compilation FAIL (`OrderNumbers`, `OrderDetails`, `Customer`, `StatusEntry` do not exist).

- [ ] **Step 3: Write minimal implementation**

`Customer.java`:

```java
package com.sixpack.order.domain;

/** Contact details of the buyer at the time of purchase, kept so later profile edits don't rewrite history. */
public record Customer(String name, String email, String phone) {
}
```

`StatusEntry.java`:

```java
package com.sixpack.order.domain;

import java.time.Instant;

/** One step in an order's life. {@code actor} is the id of the user who caused it. */
public record StatusEntry(OrderStatus status, Instant at, String actor) {
}
```

`OrderDetails.java`:

```java
package com.sixpack.order.domain;

import java.util.List;

/** Optional order fields. Orders placed before they existed carry {@link #EMPTY}. */
public record OrderDetails(
        String orderNumber,
        ShippingInfo billingInfo,
        Customer customer,
        List<StatusEntry> statusHistory,
        String couponCode,
        String notes) {

    public static final OrderDetails EMPTY = new OrderDetails(null, null, null, null, null, null);

    public OrderDetails {
        statusHistory = statusHistory == null ? List.of() : List.copyOf(statusHistory);
    }
}
```

`OrderNumbers.java`:

```java
package com.sixpack.order.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

/** Human-friendly order references like {@code ORD-20261017-K7M3QX}. Characters that read alike (I, L, O, 0, 1) are left out. */
public final class OrderNumbers {
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final int SUFFIX_LENGTH = 6;

    private OrderNumbers() {
    }

    public static String generate(Instant now, RandomGenerator random) {
        StringBuilder number = new StringBuilder("ORD-").append(DAY.format(now)).append('-');
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            number.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return number.toString();
    }
}
```

`OrderItem.java`:

```java
package com.sixpack.order.domain;

/**
 * Snapshot of the product at purchase time, so later product edits/deletes do not change history.
 *
 * @param taxRate percent applied to this line, or null for the default rate (older orders)
 */
public record OrderItem(String productId, String name, String photo, double price, int quantity, String sku,
                        Double taxRate) {

    /** Pre-sku shape, kept so existing callers compile unchanged. */
    public OrderItem(String productId, String name, String photo, double price, int quantity) {
        this(productId, name, photo, price, quantity, null, null);
    }
}
```

`ShippingInfo.java`:

```java
package com.sixpack.order.domain;

/** A delivery or billing address. {@code name} and {@code phone} identify the recipient for the courier. */
public record ShippingInfo(String address, String city, String state, String country, String pinCode, String name,
                           String phone) {

    /** Pre-recipient shape, kept so existing callers compile unchanged. */
    public ShippingInfo(String address, String city, String state, String country, String pinCode) {
        this(address, city, state, country, pinCode, null, null);
    }
}
```

`PaymentInfo.java`:

```java
package com.sixpack.order.domain;

import java.time.Instant;

/**
 * @param status "PAID" once the provider's signature has been verified; null on older orders
 * @param amount rupees charged
 */
public record PaymentInfo(String provider, String orderId, String paymentId, String status, Double amount,
                          String currency, Instant paidAt) {

    /** Pre-status shape, kept so existing callers compile unchanged. */
    public PaymentInfo(String provider, String orderId, String paymentId) {
        this(provider, orderId, paymentId, null, null, null, null);
    }
}
```

`Order.java` (replace; keep the existing `java.time.Instant` and `java.util.List` imports):

```java
package com.sixpack.order.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record Order(
        String id,
        String userId,
        ShippingInfo shippingInfo,
        List<OrderItem> items,
        Quote quote,
        OrderStatus status,
        PaymentInfo payment,
        String termsVersion,
        Instant createdAt,
        OrderDetails details) {

    public Order {
        details = details == null ? OrderDetails.EMPTY : details;
    }

    /** Pre-details shape, kept so existing callers compile unchanged. */
    public Order(String id, String userId, ShippingInfo shippingInfo, List<OrderItem> items, Quote quote,
                 OrderStatus status, PaymentInfo payment, String termsVersion, Instant createdAt) {
        this(id, userId, shippingInfo, items, quote, status, payment, termsVersion, createdAt, OrderDetails.EMPTY);
    }

    public Order withDetails(OrderDetails newDetails) {
        return new Order(id, userId, shippingInfo, items, quote, status, payment, termsVersion, createdAt,
                newDetails);
    }

    /** Moves to {@code next} and records who did it and when. The previous history is never edited. */
    public Order withStatus(OrderStatus next, String actor, Instant at) {
        List<StatusEntry> history = new ArrayList<>(details.statusHistory());
        history.add(new StatusEntry(next, at, actor));
        return new Order(id, userId, shippingInfo, items, quote, next, payment, termsVersion, createdAt,
                new OrderDetails(details.orderNumber(), details.billingInfo(), details.customer(), history,
                        details.couponCode(), details.notes()));
    }
}
```

`OrderService.java`: the old one-argument `withStatus` is gone, so make `advance` compile with a placeholder actor now and fix it properly in Task 5. Replace the body of `advance` with:

```java
    public Order advance(String id) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        Order saved = orders.save(order.withStatus(order.status().next(), "system", java.time.Instant.now()));
        events.publishEvent(new OrderChangedEvent(id));
        return saved;
    }
```

(Task 5 replaces this with the real actor.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=OrderDomainTest,PriceCalculatorTest,OrderStatusTest test`
Expected: PASS (new tests plus the existing order domain tests, proving the legacy constructors keep callers compiling).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(order): add OrderDetails, order numbers and optional order/payment/item fields to the domain"
```

---

### Task 2: Tax per product rate (separable)

**Files:**
- Modify: `backend/src/main/java/com/sixpack/order/domain/PriceCalculator.java`
- Test: `backend/src/test/java/com/sixpack/order/domain/PriceCalculatorTest.java` (add tests)

**Interfaces:**
- Consumes: `OrderItem.taxRate()` (null = 18).
- Produces: `PriceCalculator.quote(List<OrderItem>, double)` keeps its signature. Tax = sum over rate groups of `round(groupSubtotal × rate%)` to whole rupees. A cart where every item uses the default (or an explicit 18) gives exactly the previous result.

- [ ] **Step 1: Write the failing tests**

Add to `PriceCalculatorTest` (imports: `java.util.stream.LongStream`):

```java
    private static OrderItem item(double price, int qty, Double taxRate) {
        return new OrderItem("p", "Item", "", price, qty, null, taxRate);
    }

    /** The calculation as it was before per-product rates, used as the oracle for the parity test. */
    private static double legacyTax(double subtotalRupees) {
        long subtotalPaise = Math.round(subtotalRupees * 100);
        return (Math.round(subtotalPaise * 0.18 / 100.0) * 100) / 100.0;
    }

    @Test
    void defaultRateCartsPriceExactlyAsBefore() {
        for (long rupees = 1; rupees <= 5000; rupees += 7) {
            double price = rupees + 0.35;
            Quote q = PriceCalculator.quote(List.of(item(price, 1, null)), 0);
            assertThat(q.tax()).as("subtotal %s", price).isEqualTo(legacyTax(price));
            Quote explicit = PriceCalculator.quote(List.of(item(price, 1, 18.0)), 0);
            assertThat(explicit.tax()).isEqualTo(q.tax());
        }
    }

    @Test
    void mixedRatesAreTaxedPerRateGroup() {
        // 1000 at 5% -> 50, 500 at 18% -> 90
        Quote q = PriceCalculator.quote(List.of(item(1000, 1, 5.0), item(250, 2, null)), 0);
        assertThat(q.subtotal()).isEqualTo(1500.0);
        assertThat(q.tax()).isEqualTo(140.0);
        assertThat(q.shippingCharges()).isZero();
        assertThat(q.total()).isEqualTo(1640.0);
    }

    @Test
    void itemsWithTheSameRateAreGroupedBeforeRounding() {
        // 2 x 0.5 at 18% is 0.18 -> rounds to 0 once for the group, not twice.
        Quote q = PriceCalculator.quote(List.of(item(0.5, 1, null), item(0.5, 1, 18.0)), 0);
        assertThat(q.tax()).isEqualTo(0.0);
    }

    @Test
    void zeroRateMeansNoTax() {
        Quote q = PriceCalculator.quote(List.of(item(1200, 1, 0.0)), 0);
        assertThat(q.tax()).isZero();
        assertThat(q.total()).isEqualTo(1200.0);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=PriceCalculatorTest test`
Expected: FAIL (`mixedRatesAreTaxedPerRateGroup` gets tax 270 instead of 140; `zeroRateMeansNoTax` gets 216).

- [ ] **Step 3: Write minimal implementation**

In `PriceCalculator.java`: replace `static final double TAX_RATE = 0.18;` with `static final double DEFAULT_TAX_RATE_PERCENT = 18.0;`, add imports `java.util.Map` and `java.util.TreeMap`, and change `quote`:

```java
    public static Quote quote(List<OrderItem> items, double couponAmount) {
        long subtotal = 0;
        Map<Double, Long> subtotalByRate = new TreeMap<>();
        for (OrderItem item : items) {
            long line = toPaise(item.price()) * item.quantity();
            subtotal += line;
            double rate = item.taxRate() == null ? DEFAULT_TAX_RATE_PERCENT : item.taxRate();
            subtotalByRate.merge(rate, line, Long::sum);
        }
        long shipping = subtotal > FREE_SHIPPING_ABOVE_PAISE ? 0 : SHIPPING_PAISE;
        long tax = 0;
        for (Map.Entry<Double, Long> group : subtotalByRate.entrySet()) {
            // Whole rupees per rate group, like the old UI. At 18% this is the previous formula, bit for bit.
            tax += Math.round(group.getValue() * (group.getKey() / 100.0) / 100.0) * 100;
        }
        long discount = Math.min(Math.max(toPaise(couponAmount), 0), subtotal);
        long total = subtotal + tax + shipping - discount;
        return new Quote(rupees(subtotal), rupees(tax), rupees(shipping), rupees(discount), rupees(total));
    }
```

Update the class javadoc line "18% tax" to "tax per product rate (default 18%)".

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=PriceCalculatorTest,CheckoutIntegrationTest test`
Expected: PASS (the existing checkout IT still prices ₹1316, proving parity end to end).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(order): tax per product rate, default 18% priced exactly as before"
```

---

### Task 3: Persist the new order fields (and legacy orders)

**Files:**
- Modify: `backend/src/main/java/com/sixpack/order/infra/mongo/OrderDocument.java`, `MongoOrderRepository.java`
- Modify: `backend/src/main/java/com/sixpack/order/domain/PaymentIntent.java`, `infra/mongo/PaymentIntentDocument.java`, `MongoPaymentIntentRepository.java`
- Modify: `backend/src/main/java/com/sixpack/order/application/CheckoutService.java` (only the `new PaymentIntent(...)` call)
- Test: `backend/src/test/java/com/sixpack/it/OrderDetailsPersistenceIntegrationTest.java` (create)

**Interfaces:**
- Consumes: Task 1 types.
- Produces: `PaymentIntent(String id, String userId, String provider, String providerOrderId, List<OrderItem> items, String coupon, Quote quote, String currency)` (currency added last). `OrderRepository.save/findById` round-trip every new field. `orderNumber` has a sparse unique index. `anonymiseShippingForUser` is widened in Task 6.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/OrderDetailsPersistenceIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.order.domain.Customer;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderDetails;
import com.sixpack.order.domain.OrderItem;
import com.sixpack.order.domain.OrderRepository;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.order.domain.PaymentInfo;
import com.sixpack.order.domain.Quote;
import com.sixpack.order.domain.ShippingInfo;
import com.sixpack.order.domain.StatusEntry;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

class OrderDetailsPersistenceIntegrationTest extends IntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    @Autowired
    OrderRepository orders;

    private static Order order(String number) {
        String productId = new ObjectId().toHexString();
        return new Order(null, new ObjectId().toHexString(),
                new ShippingInfo("1 MG Road", "Pune", "MH", "India", "411001", "Asha", "9876543210"),
                List.of(new OrderItem(productId, "Tee", "uploads/a.png", 250.0, 2, "TEE-1", 5.0)),
                new Quote(500, 25, 200, 0, 725), OrderStatus.PROCESSING,
                new PaymentInfo("fake", "po1", "pay1", "PAID", 725.0, "INR", T0), "2026-10-16", null,
                new OrderDetails(number,
                        new ShippingInfo("9 Park St", "Mumbai", "MH", "India", "400001", "Asha K", "9123456780"),
                        new Customer("Asha", "asha@test.dev", "9876543210"),
                        List.of(new StatusEntry(OrderStatus.PROCESSING, T0, "u1")), "SAVE100", "Leave at door"));
    }

    @Test
    void roundTripsEveryNewField() {
        Order saved = orders.save(order("ORD-20261017-ABCDEF"));

        Order loaded = orders.findById(saved.id()).orElseThrow();

        assertThat(loaded.details()).isEqualTo(saved.details());
        assertThat(loaded.details().billingInfo().city()).isEqualTo("Mumbai");
        assertThat(loaded.shippingInfo().name()).isEqualTo("Asha");
        assertThat(loaded.shippingInfo().phone()).isEqualTo("9876543210");
        assertThat(loaded.payment()).isEqualTo(saved.payment());
        assertThat(loaded.items().get(0).sku()).isEqualTo("TEE-1");
        assertThat(loaded.items().get(0).taxRate()).isEqualTo(5.0);
    }

    @Test
    void legacyOrderWithoutNewFieldsLoadsWithDefaults() {
        ObjectId id = new ObjectId();
        mongo.getCollection("orders").insertOne(new Document("_id", id).append("user", "u1")
                .append("status", "Processing").append("subtotal", 500.0).append("tax", 90.0)
                .append("shippingCharges", 200.0).append("discount", 0.0).append("total", 790.0)
                .append("shippingInfo", new Document("address", "1 MG Road").append("city", "Pune")
                        .append("state", "MH").append("country", "India").append("pinCode", 411001L))
                .append("orderItems", List.of(new Document("name", "Tee").append("photo", "").append("price", 250.0)
                        .append("quantity", 2).append("productId", new ObjectId())))
                .append("payment", new Document("provider", "razorpay").append("orderId", "o").append("paymentId", "p"))
                .append("createdAt", new Date()));

        Order o = orders.findById(id.toHexString()).orElseThrow();

        assertThat(o.details()).isEqualTo(OrderDetails.EMPTY);
        assertThat(o.payment().status()).isNull();
        assertThat(o.payment().provider()).isEqualTo("razorpay");
        assertThat(o.items().get(0).sku()).isNull();
        assertThat(o.items().get(0).taxRate()).isNull();
        assertThat(o.shippingInfo().name()).isNull();
    }

    @Test
    void orderNumbersAreUniqueButOptional() {
        orders.save(order("ORD-20261017-AAAAAA"));
        assertThatThrownBy(() -> orders.save(order("ORD-20261017-AAAAAA")))
                .isInstanceOf(DuplicateKeyException.class);

        orders.save(order(null));
        orders.save(order(null)); // legacy-style orders without a number never collide
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=OrderDetailsPersistenceIntegrationTest test`
Expected: FAIL (new fields are dropped on save, so `roundTripsEveryNewField` and `orderNumbersAreUniqueButOptional` fail).

- [ ] **Step 3: Write minimal implementation**

`OrderDocument.java`: add the imports `java.time.Instant` (already present) and `java.util.List` (present), then:
- after `String termsVersion;` add
  ```java
      @Indexed(unique = true, sparse = true)
      String orderNumber;
      ShippingInfoDoc billingInfo;
      CustomerDoc customer;
      List<StatusEntryDoc> statusHistory;
      String couponCode;
      String notes;
  ```
- in `ShippingInfoDoc` add `String name;` and `String phone;`
- in `OrderItemDoc` add `String sku;` and `Double taxRate;`
- in `PaymentDoc` add `String status; Double amount; String currency; Instant paidAt;`
- add the nested classes
  ```java
      static class CustomerDoc {
          String name;
          String email;
          String phone;
      }

      static class StatusEntryDoc {
          String status;
          Instant at;
          String actor;
      }
  ```

`MongoOrderRepository.java`:
- In `save`, after `doc.termsVersion = order.termsVersion();` add
  ```java
          OrderDetails d = order.details();
          doc.orderNumber = d.orderNumber();
          doc.billingInfo = d.billingInfo() == null ? null : toDoc(d.billingInfo());
          doc.customer = d.customer() == null ? null : toDoc(d.customer());
          doc.statusHistory = d.statusHistory().isEmpty() ? null
                  : d.statusHistory().stream().map(MongoOrderRepository::toDoc).toList();
          doc.couponCode = d.couponCode();
          doc.notes = d.notes();
  ```
  and extend the payment block with
  ```java
              doc.payment.status = order.payment().status();
              doc.payment.amount = order.payment().amount();
              doc.payment.currency = order.payment().currency();
              doc.payment.paidAt = order.payment().paidAt();
  ```
- In `toDoc(ShippingInfo)` add `d.name = s.name(); d.phone = s.phone();`
- In `toDoc(OrderItem)` add `d.sku = i.sku(); d.taxRate = i.taxRate();`
- Add
  ```java
      private static OrderDocument.CustomerDoc toDoc(Customer c) {
          OrderDocument.CustomerDoc d = new OrderDocument.CustomerDoc();
          d.name = c.name();
          d.email = c.email();
          d.phone = c.phone();
          return d;
      }

      private static OrderDocument.StatusEntryDoc toDoc(StatusEntry e) {
          OrderDocument.StatusEntryDoc d = new OrderDocument.StatusEntryDoc();
          d.status = e.status().wireValue();
          d.at = e.at();
          d.actor = e.actor();
          return d;
      }

      private static ShippingInfo toDomain(OrderDocument.ShippingInfoDoc s) {
          return s == null ? null : new ShippingInfo(s.address, s.city, s.state, s.country,
                  s.pinCode == null ? null : String.valueOf(s.pinCode), s.name, s.phone);
      }
  ```
- In `toDomain(OrderItemDoc)`: `new OrderItem(d.productId, d.name, d.photo, nz(d.price), d.quantity == null ? 0 : d.quantity, d.sku, d.taxRate)`.
- In `toDomain(OrderDocument)`: replace the shipping construction with `ShippingInfo shipping = toDomain(d.shippingInfo);`, build the payment as
  ```java
          PaymentInfo payment = d.payment == null ? null
                  : new PaymentInfo(d.payment.provider, d.payment.orderId, d.payment.paymentId, d.payment.status,
                          d.payment.amount, d.payment.currency, d.payment.paidAt);
  ```
  and build the details and pass them as the last `Order` argument:
  ```java
          OrderDetails details = new OrderDetails(d.orderNumber, toDomain(d.billingInfo),
                  d.customer == null ? null : new Customer(d.customer.name, d.customer.email, d.customer.phone),
                  d.statusHistory == null ? null : d.statusHistory.stream()
                          .map(e -> new StatusEntry(OrderStatus.fromWire(e.status), e.at, e.actor)).toList(),
                  d.couponCode, d.notes);
  ```
- Add imports `Customer`, `OrderDetails`, `StatusEntry`.

`PaymentIntent.java`: add a final component `String currency`. `PaymentIntentDocument.java`: add `String currency;`. `MongoPaymentIntentRepository`: in `save` add `d.currency = intent.currency();`, and in `findByProviderOrderId` pass `doc.currency` as the new last constructor argument. `CheckoutService.checkout`: pass `providerOrder.currency()` as the new last argument of `new PaymentIntent(...)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=OrderDetailsPersistenceIntegrationTest,CheckoutIntegrationTest,DashboardIntegrationTest,ConsentIntegrationTest,ModularityTests test`
Expected: PASS (existing checkout, dashboard and erasure tests prove old and new shapes coexist).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(order): persist order number, billing, customer snapshot, history, notes and payment details"
```

---

### Task 4: Checkout fills the new fields

**Files:**
- Create: `backend/src/main/java/com/sixpack/user/UserContact.java`
- Modify: `backend/src/main/java/com/sixpack/user/UserDirectory.java`, `application/UserDirectoryService.java`
- Modify: `backend/src/main/java/com/sixpack/product/ProductSnapshot.java`, `application/ProductCatalogService.java`
- Modify: `backend/src/main/java/com/sixpack/order/application/CheckoutService.java`, `web/OrderController.java`
- Test: `backend/src/test/java/com/sixpack/it/OrderCheckoutDetailsIntegrationTest.java` (create)

**Interfaces:**
- Consumes: Tasks 1–3, plan 1 (`Product.details().sku()/taxRate()`), plan 2 (`PUT /user/me` for the phone).
- Produces:
  - `record UserContact(String id, String name, String email, String phone)` in `com.sixpack.user`; `UserDirectory.contactById(String id) : Optional<UserContact>`
  - `ProductSnapshot(String id, String name, String photo, double price, int stock, String sku, Double taxRate)`
  - `CheckoutService.confirm(String userId, String providerOrderId, String paymentId, String signature, ConfirmDetails details)` with `record ConfirmDetails(ShippingInfo shipping, ShippingInfo billing, String notes)`
  - `POST /order/new` accepts optional `shippingInfo.name/phone`, optional `billingInfo` (same shape), optional `notes` (≤500). 400 on a malformed phone, oversized notes or invalid billing; the payment intent is **not** consumed on a 400.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/OrderCheckoutDetailsIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class OrderCheckoutDetailsIntegrationTest extends IntegrationTest {

    static Map<String, Object> shipping() {
        Map<String, Object> s = new HashMap<>();
        s.put("address", "1 MG Road");
        s.put("city", "Bengaluru");
        s.put("state", "KA");
        s.put("country", "India");
        s.put("pinCode", "560001");
        s.put("name", "Asha Recipient");
        s.put("phone", "9123456780");
        return s;
    }

    private String productWith(String admin, String sku, String taxRate) throws Exception {
        var req = multipart("/api/v1/product/new")
                .file(new MockMultipartFile("photo", "p.png", "image/png", com.sixpack.storage.TestImages.png()))
                .param("name", "Shoe").param("price", "1200").param("stock", "5").param("category", "footwear")
                .param("sku", sku).param("taxRate", taxRate).header("Authorization", bearer(admin));
        return body(mvc.perform(req).andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
    }

    private JsonNode checkout(String token, String productId, String coupon) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        if (coupon != null) {
            req.put("coupon", coupon);
        }
        return body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("checkout");
    }

    private ResultActions confirm(String token, String providerOrderId, Map<String, Object> extra) throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("providerOrderId", providerOrderId,
                "paymentId", "fake_pay_1", "signature", "fake", "shippingInfo", shipping()));
        req.putAll(extra);
        return mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)));
    }

    @Test
    void confirmedOrderCarriesTheNewDetails() throws Exception {
        String admin = adminToken();
        String product = productWith(admin, "SH-1", "5");
        mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"coupon\":\"SAVE100\",\"amount\":100}"))
                .andExpect(status().isCreated());
        String user = register("buyer@test.dev");
        mvc.perform(put("/api/v1/user/me").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"+919876543210\"}"))
                .andExpect(status().isOk());

        JsonNode quote = checkout(user, product, "SAVE100");
        assertThat(quote.get("tax").asDouble()).isEqualTo(60.0); // 5% of 1200, not the default 18%
        assertThat(quote.get("total").asDouble()).isEqualTo(1160.0);

        Map<String, Object> billing = shipping();
        billing.put("city", "Mumbai");
        String orderId = body(confirm(user, quote.get("providerOrderId").asText(),
                Map.of("billingInfo", billing, "notes", "Leave at the door")).andExpect(status().isCreated())
                .andReturn()).get("orderId").asText();

        ResultActions order = mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)));
        order.andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderNumber").value(org.hamcrest.Matchers.matchesPattern(
                        "ORD-\\d{8}-[A-HJKMNP-Z2-9]{6}")))
                .andExpect(jsonPath("$.order.shippingInfo.name").value("Asha Recipient"))
                .andExpect(jsonPath("$.order.shippingInfo.phone").value("9123456780"))
                .andExpect(jsonPath("$.order.billingInfo.city").value("Mumbai"))
                .andExpect(jsonPath("$.order.customer.email").value("buyer@test.dev"))
                .andExpect(jsonPath("$.order.customer.phone").value("+919876543210"))
                .andExpect(jsonPath("$.order.customer.name").value("Test User"))
                .andExpect(jsonPath("$.order.couponCode").value("SAVE100"))
                .andExpect(jsonPath("$.order.notes").value("Leave at the door"))
                .andExpect(jsonPath("$.order.orderItems[0].sku").value("SH-1"))
                .andExpect(jsonPath("$.order.orderItems[0].taxRate").value(5.0))
                .andExpect(jsonPath("$.order.tax").value(60.0))
                .andExpect(jsonPath("$.order.total").value(1160.0))
                .andExpect(jsonPath("$.order.payment.status").value("PAID"))
                .andExpect(jsonPath("$.order.payment.amount").value(1160.0))
                .andExpect(jsonPath("$.order.payment.currency").value("INR"))
                .andExpect(jsonPath("$.order.payment.paidAt").exists())
                .andExpect(jsonPath("$.order.payment.paymentId").doesNotExist())
                .andExpect(jsonPath("$.order.statusHistory.length()").value(1))
                .andExpect(jsonPath("$.order.statusHistory[0].status").value("Processing"));
    }

    @Test
    void billingDefaultsToNothingAndOrderNumbersDiffer() throws Exception {
        String admin = adminToken();
        String product = productWith(admin, "SH-2", "18");
        String user = register("two@test.dev");

        String first = body(confirm(user, checkout(user, product, null).get("providerOrderId").asText(), Map.of())
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
        String second = body(confirm(user, checkout(user, product, null).get("providerOrderId").asText(), Map.of())
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();

        JsonNode a = body(mvc.perform(get("/api/v1/order/" + first).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.order.billingInfo").doesNotExist()).andReturn()).get("order");
        JsonNode b = body(mvc.perform(get("/api/v1/order/" + second).header("Authorization", bearer(user)))
                .andReturn()).get("order");
        assertThat(a.get("orderNumber").asText()).isNotEqualTo(b.get("orderNumber").asText());
    }

    @Test
    void invalidInputIsRejectedWithoutConsumingThePayment() throws Exception {
        String product = productWith(adminToken(), "SH-3", "18");
        String user = register("retry@test.dev");
        String intent = checkout(user, product, null).get("providerOrderId").asText();

        Map<String, Object> badPhone = shipping();
        badPhone.put("phone", "call me");
        confirm(user, intent, Map.of("shippingInfo", badPhone)).andExpect(status().isBadRequest());
        confirm(user, intent, Map.of("notes", "x".repeat(501))).andExpect(status().isBadRequest());
        Map<String, Object> badBilling = shipping();
        badBilling.put("pinCode", "12");
        confirm(user, intent, Map.of("billingInfo", badBilling)).andExpect(status().isBadRequest());

        // The same payment intent still works once the input is fixed.
        confirm(user, intent, Map.of()).andExpect(status().isCreated());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=OrderCheckoutDetailsIntegrationTest test`
Expected: FAIL (no `orderNumber`, no `customer`, tax is 216 instead of 60, invalid phone is accepted).

- [ ] **Step 3: Write minimal implementation**

`user/UserContact.java`:

```java
package com.sixpack.user;

/** Contact details other modules may snapshot (for example onto an order). */
public record UserContact(String id, String name, String email, String phone) {
}
```

`UserDirectory.java`: add `java.util.Optional` import and

```java
    /** @return the user's contact details, or empty if the account no longer exists */
    Optional<UserContact> contactById(String id);
```

`UserDirectoryService.java`: add

```java
    @Override
    public Optional<UserContact> contactById(String id) {
        return users.findById(id)
                .map(u -> new UserContact(u.id(), u.name(), u.email(), u.details().phone()));
    }
```

(with imports `java.util.Optional` and `com.sixpack.user.UserContact`).

`ProductSnapshot.java`:

```java
package com.sixpack.product;

public record ProductSnapshot(String id, String name, String photo, double price, int stock, String sku,
                              Double taxRate) {
}
```

`ProductCatalogService.findSnapshots`: replace the `.map(...)` with
`.map(p -> new ProductSnapshot(p.id(), p.name(), p.photo(), p.price(), p.stock(), p.details().sku(), p.details().taxRate()))`.

`CheckoutService.java`:
- add `public record ConfirmDetails(ShippingInfo shipping, ShippingInfo billing, String notes) { }`
- inject `UserDirectory users` (constructor parameter and field; import `com.sixpack.user.UserDirectory`, `com.sixpack.user.UserContact`)
- in `checkout`, build items with `new OrderItem(p.id(), p.name(), p.photo(), p.price(), line.getValue(), p.sku(), p.taxRate())`
- change `confirm` to the new signature and body. After the intent is found and the signature verified, but **before** `markConsumed`, load the contact so a deleted account fails early:

```java
    public String confirm(String userId, String providerOrderId, String paymentId, String signature,
                          ConfirmDetails details) {
        PaymentIntent intent = intents.findByProviderOrderId(providerOrderId)
                .filter(i -> i.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Checkout session not found"));
        if (!gateway.verifySignature(providerOrderId, paymentId, signature)) {
            meters.counter("sixpack.checkout.failed", "reason", "bad_signature").increment();
            throw ApiException.badRequest("Payment verification failed");
        }
        UserContact contact = users.contactById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        if (!intents.markConsumed(providerOrderId)) {
            throw ApiException.conflict("This payment has already been used for an order");
        }
        // ... stock reservation block unchanged ...
        Instant now = Instant.now();
        Order order;
        try {
            order = orders.save(new Order(null, userId, details.shipping(), intent.items(), intent.quote(),
                    OrderStatus.PROCESSING,
                    new PaymentInfo(intent.provider(), providerOrderId, paymentId, "PAID", intent.quote().total(),
                            intent.currency() == null ? "INR" : intent.currency(), now),
                    consents.currentVersion(Purpose.TERMS), null,
                    new OrderDetails(OrderNumbers.generate(now, ThreadLocalRandom.current()), details.billing(),
                            new Customer(contact.name(), contact.email(), contact.phone()),
                            List.of(new StatusEntry(OrderStatus.PROCESSING, now, userId)), intent.coupon(),
                            details.notes())));
        } catch (RuntimeException e) {
            // unchanged: release stock, re-open the intent, rethrow
        }
        // ... metrics and event unchanged ...
    }
```

(Keep the existing stock-reservation `try/catch` and the rollback `catch` exactly as they are; only the `new Order(...)` expression and the method head change. A duplicate order number surfaces as `DuplicateKeyException` in that existing rollback path, which is the intended behaviour. Add imports `Customer`, `OrderDetails`, `OrderNumbers`, `StatusEntry`, `java.time.Instant`, `java.util.concurrent.ThreadLocalRandom`.)

`OrderController.java`:
- `ShippingRequest` gains `@Size(max = 80) String name` and `@Pattern(regexp = "^\\+?[0-9]{10,13}$|", message = "must be 10-13 digits, optionally starting with +") String phone`.
- `ConfirmRequest` gains `@Valid ShippingRequest billingInfo` and `@Size(max = 500) String notes`.
- Add a mapper and use it in `confirm`:

```java
    private static ShippingInfo toDomain(ShippingRequest s) {
        if (s == null) {
            return null;
        }
        return new ShippingInfo(s.address().trim(), s.city().trim(), s.state().trim(), s.country().trim(),
                s.pinCode(), blankToNull(s.name()), blankToNull(s.phone()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
```

```java
        String orderId = checkout.confirm(AuthUser.from(jwt).id(), request.providerOrderId(), request.paymentId(),
                request.signature(), new ConfirmDetails(toDomain(request.shippingInfo()),
                        toDomain(request.billingInfo()), blankToNull(request.notes())));
```

(Import `CheckoutService.ConfirmDetails`.) The view fields the test reads are added in Task 5; until then run only the non-view assertions, or do Tasks 4 and 5 back to back and run the full class at the end of Task 5. **This task's own gate is `invalidInputIsRejectedWithoutConsumingThePayment` plus the persistence/ModularityTests below; the remaining two tests go green in Task 5.**

- [ ] **Step 4: Run tests**

Run: `cd backend && mvn -q -Dtest=OrderCheckoutDetailsIntegrationTest#invalidInputIsRejectedWithoutConsumingThePayment,CheckoutIntegrationTest,OrderDetailsPersistenceIntegrationTest,ModularityTests test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(order): checkout records order number, customer snapshot, billing, notes and payment status"
```

---

### Task 5: Status history, actor, and the richer order view

**Files:**
- Modify: `backend/src/main/java/com/sixpack/order/application/OrderService.java`, `web/OrderController.java`, `web/OrderView.java`
- Test: `backend/src/test/java/com/sixpack/it/OrderHistoryIntegrationTest.java` (create); `OrderCheckoutDetailsIntegrationTest` now passes in full.

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: `OrderService.advance(String id, String actorId) : Order` (no history entry and no event when the order is already delivered). `OrderView` adds `orderNumber, billingInfo, customer {name,email,phone}, statusHistory [{status,at,actor}], couponCode, notes, payment {provider,status,amount,currency,paidAt}`. Provider order/payment ids are not exposed.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/OrderHistoryIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OrderHistoryIntegrationTest extends IntegrationTest {

    private String placeOrder(String user, String productId) throws Exception {
        String intent = body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items",
                                List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        Map<String, Object> req = new HashMap<>(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping()));
        return body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    @Test
    void advancingRecordsWhoAndWhenAndStopsAtDelivered() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        String product = createProduct(admin, "Mug", 300, 5, "home");
        String user = register("hist@test.dev");
        String orderId = placeOrder(user, product);

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Shipped"));
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("Delivered"));
        // A third click on a delivered order changes nothing and adds no entry.
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Delivered"));

        mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.order.statusHistory.length()").value(3))
                .andExpect(jsonPath("$.order.statusHistory[0].status").value("Processing"))
                .andExpect(jsonPath("$.order.statusHistory[0].actor").value(userId(user)))
                .andExpect(jsonPath("$.order.statusHistory[1].status").value("Shipped"))
                .andExpect(jsonPath("$.order.statusHistory[1].actor").value(adminId))
                .andExpect(jsonPath("$.order.statusHistory[2].status").value("Delivered"))
                .andExpect(jsonPath("$.order.statusHistory[2].at").exists());
    }

    @Test
    void customersCannotAdvanceOrders() throws Exception {
        String admin = adminToken();
        String user = register("nope@test.dev");
        String orderId = placeOrder(user, createProduct(admin, "Pen", 100, 5, "office"));

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.order.statusHistory.length()").value(1));
    }

    @Test
    void legacyOrdersDisplayAndAdvance() throws Exception {
        String admin = adminToken();
        String user = register("old@test.dev");
        ObjectId id = new ObjectId();
        mongo.getCollection("orders").insertOne(new Document("_id", id).append("user", userId(user))
                .append("status", "Processing").append("subtotal", 500.0).append("tax", 90.0)
                .append("shippingCharges", 200.0).append("discount", 0.0).append("total", 790.0)
                .append("shippingInfo", new Document("address", "1 MG Road").append("city", "Pune")
                        .append("state", "MH").append("country", "India").append("pinCode", 411001L))
                .append("orderItems", List.of(new Document("name", "Tee").append("photo", "").append("price", 250.0)
                        .append("quantity", 2).append("productId", new ObjectId())))
                .append("createdAt", new Date()));

        mvc.perform(get("/api/v1/order/" + id.toHexString()).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderNumber").doesNotExist())
                .andExpect(jsonPath("$.order.payment").doesNotExist())
                .andExpect(jsonPath("$.order.statusHistory.length()").value(0))
                .andExpect(jsonPath("$.order.total").value(790.0));
        mvc.perform(get("/api/v1/order/my").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.orders.length()").value(1));

        mvc.perform(put("/api/v1/order/" + id.toHexString()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("Shipped"));
        mvc.perform(get("/api/v1/order/" + id.toHexString()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.order.statusHistory.length()").value(1))
                .andExpect(jsonPath("$.order.statusHistory[0].status").value("Shipped"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=OrderHistoryIntegrationTest,OrderCheckoutDetailsIntegrationTest test`
Expected: FAIL (`statusHistory`, `orderNumber`, `customer`, `payment` are missing from the view; actor is "system").

- [ ] **Step 3: Write minimal implementation**

`OrderService.advance`:

```java
    public Order advance(String id, String actorId) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        OrderStatus next = order.status().next();
        if (next == order.status()) {
            return order; // already delivered: nothing to record
        }
        Order saved = orders.save(order.withStatus(next, actorId, Instant.now()));
        events.publishEvent(new OrderChangedEvent(id));
        return saved;
    }
```

(import `java.time.Instant`).

`OrderController.process`:

```java
    @PutMapping("/{id}")
    Map<String, Object> process(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Order order = orders.advance(id, AuthUser.from(jwt).id());
        return ApiResponse.of("message", "Order marked as " + order.status().wireValue(),
                "status", order.status().wireValue());
    }
```

`OrderView.java` (replace):

```java
package com.sixpack.order.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.order.domain.Customer;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderItem;
import com.sixpack.order.domain.PaymentInfo;
import com.sixpack.order.domain.ShippingInfo;
import com.sixpack.order.domain.StatusEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;

record OrderView(
        @JsonProperty("_id") String id,
        UserRef user,
        ShippingInfo shippingInfo,
        List<OrderItem> orderItems,
        double subtotal,
        double tax,
        double shippingCharges,
        double discount,
        double total,
        String status,
        Instant createdAt,
        String orderNumber,
        ShippingInfo billingInfo,
        Customer customer,
        List<HistoryView> statusHistory,
        String couponCode,
        String notes,
        PaymentView payment) {

    record UserRef(@JsonProperty("_id") String id, String name) {
    }

    record HistoryView(String status, Instant at, String actor) {
        static HistoryView from(StatusEntry e) {
            return new HistoryView(e.status().wireValue(), e.at(), e.actor());
        }
    }

    /** Provider order and payment ids stay server-side. */
    record PaymentView(String provider, String status, Double amount, String currency, Instant paidAt) {
        static PaymentView from(PaymentInfo p) {
            return p == null ? null : new PaymentView(p.provider(), p.status(), p.amount(), p.currency(), p.paidAt());
        }
    }

    static OrderView from(Order o, Map<String, String> userNames) {
        return new OrderView(o.id(), new UserRef(o.userId(), userNames.getOrDefault(o.userId(), "Deleted user")),
                o.shippingInfo(), o.items(), o.quote().subtotal(), o.quote().tax(), o.quote().shippingCharges(),
                o.quote().discount(), o.quote().total(), o.status().wireValue(), o.createdAt(),
                o.details().orderNumber(), o.details().billingInfo(), o.details().customer(),
                o.details().statusHistory().stream().map(HistoryView::from).toList(), o.details().couponCode(),
                o.details().notes(), PaymentView.from(o.payment()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=OrderHistoryIntegrationTest,OrderCheckoutDetailsIntegrationTest,CheckoutIntegrationTest,AuthorizationIntegrationTest,DashboardIntegrationTest test`
Expected: PASS (all of Task 4's tests now pass too).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(order): status history with actor and the richer order view"
```

---

### Task 6: Erase the new personal data with the account

**Files:**
- Modify: `backend/src/main/java/com/sixpack/order/infra/mongo/MongoOrderRepository.java` (`anonymiseShippingForUser`), `domain/OrderRepository.java` (javadoc)
- Test: `backend/src/test/java/com/sixpack/it/OrderErasureIntegrationTest.java` (create)

**Interfaces:**
- Consumes: the existing `OrderAccountPolicy` (`UserDeletedEvent` → `anonymiseShippingForUser`) and `AccountDeletionGuard` (blocks while any order is undelivered).
- Produces: after account deletion the user's orders keep `orderNumber`, amounts, items, `couponCode`, `statusHistory`, `payment`, `shippingInfo.city/state/country`, and lose `shippingInfo.address/pinCode/name/phone`, `billingInfo`, `customer` and `notes`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/OrderErasureIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OrderErasureIntegrationTest extends IntegrationTest {

    @Test
    void deletingAnAccountStripsEveryPersonalFieldButKeepsTheTaxRecord() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Lamp", 500, 5, "home");
        String user = register("erase@test.dev");

        String intent = body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items",
                                List.of(Map.of("productId", product, "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        Map<String, Object> billing = OrderCheckoutDetailsIntegrationTest.shipping();
        billing.put("city", "Mumbai");
        Map<String, Object> req = new HashMap<>(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping(),
                "billingInfo", billing, "notes", "Gate code 4711"));
        String orderId = body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();

        // Deletion is blocked until the order is delivered.
        mvc.perform(delete("/api/v1/user/me").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/user/me").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        Document stored = mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
        assertThat(stored).isNotNull();
        Document shipping = stored.get("shippingInfo", Document.class);
        assertThat(shipping.get("city")).isEqualTo("Bengaluru");
        assertThat(shipping.get("state")).isEqualTo("KA");
        assertThat(shipping).doesNotContainKeys("address", "pinCode", "name", "phone");
        assertThat(stored).doesNotContainKeys("billingInfo", "customer", "notes");

        // The tax record survives.
        assertThat(stored.getString("orderNumber")).startsWith("ORD-");
        assertThat(stored.getDouble("total")).isEqualTo(790.0); // 500 + 90 tax + 200 shipping
        assertThat(stored.get("statusHistory", List.class)).hasSize(3);
        assertThat(stored.get("payment", Document.class).getString("status")).isEqualTo("PAID");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=OrderErasureIntegrationTest test`
Expected: FAIL (`name`, `phone`, `billingInfo`, `customer` and `notes` are still present after deletion).

- [ ] **Step 3: Write minimal implementation**

`MongoOrderRepository.anonymiseShippingForUser`:

```java
    @Override
    public void anonymiseShippingForUser(String userId) {
        mongo.updateMulti(Query.query(Criteria.where("user").is(userId)),
                new Update()
                        .unset("shippingInfo.address").unset("shippingInfo.pinCode")
                        .unset("shippingInfo.name").unset("shippingInfo.phone")
                        .unset("billingInfo").unset("customer").unset("notes"),
                OrderDocument.class);
    }
```

`OrderRepository` javadoc for the method: "Removes everything that identifies the person (street address, PIN code, recipient name and phone, billing address, contact snapshot, notes) from the user's orders; amounts, items, order number, history and payment status stay for tax records."

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=OrderErasureIntegrationTest,ConsentIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(order): erase billing, contact snapshot, recipient and notes with the account"
```

---

### Task 7: Frontend — checkout details, order number and timeline

**Files:**
- Modify: `ecommerce-frontend/src/types/types.ts`, `types/api-types.ts`, `utils/features.ts`
- Modify: `ecommerce-frontend/src/redux/reducer/cartReducer.ts`, `components/productCard.tsx`, `pages/home.tsx`, `pages/search.tsx` (tax rate mirror; skip these four if Task 2 is skipped)
- Modify: `ecommerce-frontend/src/pages/shipping.tsx`, `pages/orders.tsx`, `pages/order-details.tsx`, `pages/admin/management/transactionmanagement.tsx`

**Interfaces:**
- Consumes: Task 4–5 API shapes.
- Produces: `orderLabel(o)` helper; `ShippingInfo.name/phone`; `Order` new fields; `CartItem.taxRate`.

- [ ] **Step 1: Types and helper**

`types.ts`:
- `Product` (from plan 1): add `taxRate?: number;`
- `ShippingInfo`: add `name?: string; phone?: string;`
- `CartItem`: add `taxRate?: number;`
- `OrderItem`: add `sku?: string; taxRate?: number;`
- add
  ```ts
  export type OrderStatusEntry = { status: OrderStatus; at: string; actor: string };
  export type OrderCustomer = { name?: string; email?: string; phone?: string };
  export type OrderPayment = {
    provider: string;
    status?: string;
    amount?: number;
    currency?: string;
    paidAt?: string;
  };
  ```
- `Order`: add
  ```ts
    orderNumber?: string;
    billingInfo?: ShippingInfo | null;
    customer?: OrderCustomer | null;
    statusHistory: OrderStatusEntry[];
    couponCode?: string;
    notes?: string;
    payment?: OrderPayment | null;
  ```

`api-types.ts`: in `ConfirmOrderRequest` add `billingInfo?: ShippingInfo; notes?: string;`.

`utils/features.ts`: add

```ts
/** The reference customers and admins quote: the order number, or the short id for older orders. */
export const orderLabel = (o: { _id: string; orderNumber?: string }) =>
  o.orderNumber ?? o._id.slice(-8).toUpperCase();

export const formatDateTime = (iso?: string) =>
  iso ? new Date(iso).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" }) : "—";
```

(If `formatDateTime` already exists in this file, keep the existing one.)

- [ ] **Step 2: Tax-rate mirror in the cart (skip with Task 2)**

`cartReducer.ts`: replace the `state.tax = ...` line in `recalculate` with

```ts
  const DEFAULT_TAX_RATE = 18;
  const taxableByRate = new Map<number, number>();
  for (const item of state.cartItems) {
    const rate = item.taxRate ?? DEFAULT_TAX_RATE;
    taxableByRate.set(rate, (taxableByRate.get(rate) ?? 0) + item.price * item.quantity);
  }
  state.tax = [...taxableByRate].reduce((sum, [rate, amount]) => sum + Math.round((amount * rate) / 100), 0);
```

`components/productCard.tsx`: add `taxRate?: number;` to `ProductsProps`, destructure it, and change the handler call to `handler({ productId, photo, name, price, quantity: 1, stock, taxRate })`.

`pages/home.tsx` and `pages/search.tsx`: add `taxRate={i.taxRate}` to the `<ProductCard ... />` element.

- [ ] **Step 3: Checkout page**

Replace `ecommerce-frontend/src/pages/shipping.tsx` with (this keeps the existing pay flow and the saved-address picker from plan 2, and adds recipient name/phone, optional billing address and notes):

```tsx
import { ChangeEvent, FormEvent, useEffect, useState } from "react";
import toast from "react-hot-toast";
import { LuArrowLeft, LuLock } from "react-icons/lu";
import { useDispatch, useSelector } from "react-redux";
import { Link, useNavigate } from "react-router-dom";
import { policyPath } from "../legal/policies";
import { errorMessage } from "../redux/api/baseQuery";
import { useCheckoutMutation, useConfirmOrderMutation } from "../redux/api/orderAPI";
import { useGetMyAddressesQuery } from "../redux/api/userAPI";
import { resetCart, saveShippingInfo } from "../redux/reducer/cartReducer";
import { RootState } from "../redux/store";
import { SavedAddress, ShippingInfo } from "../types/types";
import { formatINR } from "../utils/features";
import { payWithRazorpay } from "../utils/razorpay";

const emptyAddress: ShippingInfo = { address: "", city: "", state: "", country: "India", pinCode: "" };

const Shipping = () => {
  const navigate = useNavigate();
  const dispatch = useDispatch();
  const { cartItems, coupon, shippingInfo: saved } = useSelector((s: RootState) => s.cartReducer);
  const user = useSelector((s: RootState) => s.userReducer.user);

  const [shippingInfo, setShippingInfo] = useState<ShippingInfo>({
    ...saved,
    name: saved.name ?? user?.name ?? "",
    phone: saved.phone ?? user?.phone ?? "",
  });
  const [sameBilling, setSameBilling] = useState(true);
  const [billing, setBilling] = useState<ShippingInfo>(emptyAddress);
  const [notes, setNotes] = useState("");
  const [checkout] = useCheckoutMutation();
  const [confirmOrder] = useConfirmOrderMutation();
  const [busy, setBusy] = useState(false);
  const { data: book } = useGetMyAddressesQuery();
  const [picked, setPicked] = useState<string>("");
  const savedAddresses = book?.addresses ?? [];

  const applySaved = (a: SavedAddress) => {
    setPicked(a.id);
    setShippingInfo({
      address: a.line2 ? `${a.line1}, ${a.line2}` : a.line1,
      city: a.city,
      state: a.state,
      country: a.country,
      pinCode: a.pinCode,
      name: a.name,
      phone: a.phone,
    });
  };

  // Pre-select the default address once, unless the user already typed something.
  useEffect(() => {
    const def = book?.addresses.find((a) => a.isDefault);
    if (def && !picked && !saved.address) applySaved(def);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [book]);

  useEffect(() => {
    if (cartItems.length === 0) navigate("/cart");
  }, [cartItems, navigate]);

  const changeHandler = (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setShippingInfo((prev) => ({ ...prev, [e.target.name]: e.target.value }));

  const changeBilling = (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setBilling((prev) => ({ ...prev, [e.target.name]: e.target.value }));

  const submitHandler = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    dispatch(saveShippingInfo(shippingInfo));
    try {
      // 1. Server prices the cart and opens a payment order.
      const quote = await checkout({
        items: cartItems.map((i) => ({ productId: i.productId, quantity: i.quantity })),
        coupon: coupon || undefined,
      }).unwrap();
      const c = quote.checkout;

      // 2. Collect payment (fake gateway in local dev, Razorpay otherwise).
      let paymentId = "fake_pay";
      let signature = "fake";
      if (c.provider === "razorpay") {
        const paid = await payWithRazorpay({
          key: c.key,
          orderId: c.providerOrderId,
          amount: c.amount,
          currency: c.currency,
          name: user?.name ?? "",
          email: user?.email ?? "",
        });
        paymentId = paid.razorpay_payment_id;
        signature = paid.razorpay_signature;
      } else if (!confirm(`Test mode: pay ${formatINR(c.total)} with the fake gateway?`)) {
        return;
      }

      // 3. Server verifies the payment, reserves stock and creates the order.
      const res = await confirmOrder({
        providerOrderId: c.providerOrderId,
        paymentId,
        signature,
        shippingInfo,
        billingInfo: sameBilling ? undefined : billing,
        notes: notes.trim() || undefined,
      }).unwrap();
      dispatch(resetCart());
      toast.success(res.message);
      navigate(`/order/${res.orderId}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : errorMessage(err, "Checkout failed"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="shipping">
      <button className="back-btn" onClick={() => navigate("/cart")}>
        <LuArrowLeft /> Back to cart
      </button>

      <form onSubmit={submitHandler}>
        <h1>Shipping address</h1>
        <p>Where should we deliver your order?</p>

        {savedAddresses.length > 0 && (
          <label className="span-2">
            Saved addresses
            <select
              value={picked}
              onChange={(e) => {
                const a = savedAddresses.find((x) => x.id === e.target.value);
                if (a) applySaved(a);
                else setPicked("");
              }}
            >
              <option value="">Enter a new address</option>
              {savedAddresses.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.label || a.name} — {a.line1}, {a.city}
                </option>
              ))}
            </select>
          </label>
        )}

        <label>
          Recipient name
          <input type="text" name="name" value={shippingInfo.name ?? ""} onChange={changeHandler}
                 maxLength={80} required autoComplete="name" />
        </label>
        <label>
          Phone
          <input type="tel" name="phone" value={shippingInfo.phone ?? ""} onChange={changeHandler} required
                 pattern="\+?[0-9]{10,13}" title="10-13 digits, optionally starting with +" autoComplete="tel" />
        </label>
        <label className="span-2">
          Address
          <input type="text" name="address" placeholder="House no., street, area" value={shippingInfo.address}
                 onChange={changeHandler} maxLength={200} required autoComplete="street-address" />
        </label>
        <label>
          City
          <input type="text" name="city" value={shippingInfo.city}
                 onChange={changeHandler} maxLength={80} required autoComplete="address-level2" />
        </label>
        <label>
          State
          <input type="text" name="state" value={shippingInfo.state}
                 onChange={changeHandler} maxLength={80} required autoComplete="address-level1" />
        </label>
        <label>
          Country
          <select name="country" required value={shippingInfo.country} onChange={changeHandler}>
            <option value="">Choose a country</option>
            <option value="India">India</option>
          </select>
        </label>
        <label>
          Pin code
          <input type="text" inputMode="numeric" pattern="\d{4,10}" title="4-10 digits" name="pinCode"
                 value={shippingInfo.pinCode} onChange={changeHandler} required autoComplete="postal-code" />
        </label>

        <label className="span-2 address-book__default">
          <input type="checkbox" checked={sameBilling} onChange={(e) => setSameBilling(e.target.checked)} />
          Billing address is the same as the shipping address
        </label>
        {!sameBilling && (
          <>
            <label className="span-2">
              Billing address
              <input type="text" name="address" value={billing.address} onChange={changeBilling}
                     maxLength={200} required />
            </label>
            <label>
              City
              <input type="text" name="city" value={billing.city} onChange={changeBilling} maxLength={80} required />
            </label>
            <label>
              State
              <input type="text" name="state" value={billing.state} onChange={changeBilling} maxLength={80} required />
            </label>
            <label>
              Country
              <select name="country" required value={billing.country} onChange={changeBilling}>
                <option value="India">India</option>
              </select>
            </label>
            <label>
              Pin code
              <input type="text" inputMode="numeric" pattern="\d{4,10}" title="4-10 digits" name="pinCode"
                     value={billing.pinCode} onChange={changeBilling} required />
            </label>
          </>
        )}

        <label className="span-2">
          Delivery notes (optional)
          <input type="text" value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={500}
                 placeholder="Gate code, preferred time…" />
        </label>

        <button type="submit" className="span-2" disabled={busy}>
          <LuLock /> {busy ? "Processing…" : "Pay & place order"}
        </button>
        <p className="shipping__terms span-2">
          By placing this order you agree to our <Link to={policyPath("terms")} target="_blank">Terms</Link>,{" "}
          <Link to={policyPath("refunds")} target="_blank">Cancellation &amp; Refund Policy</Link> and{" "}
          <Link to={policyPath("shipping")} target="_blank">Shipping Policy</Link>. Payment is processed securely
          by Razorpay.
        </p>
      </form>
    </div>
  );
};

export default Shipping;
```

(Before replacing, diff against the current file: anything in it that this listing does not contain was added after the plan was written and must be carried over.)

- [ ] **Step 4: Order pages**

`pages/orders.tsx`: import `orderLabel` from `../utils/features` and change the first column to

```tsx
  { Header: "Order", accessor: "orderNumber", width: 220,
    Cell: ({ row }: CellProps<Row>) => orderLabel(row.original) } as Column<Row>,
```

`pages/order-details.tsx`: import `orderLabel, formatDateTime`; replace the `<h1>` content with `Order {orderLabel(o)} <StatusBadge status={o.status} />`; in each item row show the SKU when present (`{i.sku && <small>SKU {i.sku}</small>}` after the name span); under "Shipping to" print the recipient when present:

```tsx
          {s && (
            <p>
              {s.name && <strong>{s.name} · {s.phone}<br /></strong>}
              {s.address}, {s.city}, {s.state}, {s.country} – {s.pinCode}
            </p>
          )}
          {o.billingInfo && (
            <>
              <h4>Billing address</h4>
              <p>{o.billingInfo.address}, {o.billingInfo.city}, {o.billingInfo.state}, {o.billingInfo.country} – {o.billingInfo.pinCode}</p>
            </>
          )}
          {o.notes && (
            <>
              <h4>Delivery notes</h4>
              <p>{o.notes}</p>
            </>
          )}
```

and add, after the Payment `<dl>`, a payment line and the timeline:

```tsx
          {o.payment?.status && (
            <p>
              Payment {o.payment.status.toLowerCase()}
              {o.payment.paidAt ? ` on ${formatDateTime(o.payment.paidAt)}` : ""}
              {o.couponCode ? ` · coupon ${o.couponCode}` : ""}
            </p>
          )}
          {o.statusHistory.length > 0 && (
            <>
              <h4>Order timeline</h4>
              <ol className="order-timeline">
                {o.statusHistory.map((h) => (
                  <li key={`${h.status}-${h.at}`}>
                    <strong>{h.status}</strong> <small>{formatDateTime(h.at)}</small>
                  </li>
                ))}
              </ol>
            </>
          )}
```

`pages/admin/management/transactionmanagement.tsx`: import `orderLabel, formatDateTime`; use `title={`Order ${orderLabel(order)}`}`; in the Customer section add contact lines and notes/billing/timeline exactly as above, plus

```tsx
          {order.customer && (
            <p>
              {order.customer.email}
              {order.customer.phone ? ` · ${order.customer.phone}` : ""}
            </p>
          )}
```

and show the recipient (`s.name`, `s.phone`) in the address block. Show `order.statusHistory` as the same `<ol className="order-timeline">`.

Append to `styles/_shipping.scss`:

```scss
.order-timeline {
  list-style: none;
  padding: 0;
  margin: 0 0 1rem;
  display: grid;
  gap: 0.35rem;

  li {
    display: flex;
    gap: 0.75rem;
    align-items: baseline;
  }
}
```

- [ ] **Step 5: Verify and commit**

Run: `cd ecommerce-frontend && npm run build && npm run lint`
Expected: both succeed with no type errors or warnings. Then with the stack up: place an order with a different billing address and a note; the order page shows the order number, recipient, billing address, note, payment line and a one-step timeline; an admin advancing it adds timeline steps; an old order still renders (short id, no timeline).

```bash
git add ecommerce-frontend/src
git commit -m "feat(checkout): recipient, billing address, notes, order numbers and timeline"
```

---

### Task 8: Docs and full verification

**Files:**
- Modify: `docs/foundations/contracts.md`, `requirements.md`, `architecture.md`, `compliance-consent.md`, `test-rollout.md`

- [ ] **Step 1: Contracts**

In `contracts.md` under **Order — `/order`**:
- `POST /new` request: `{providerOrderId, paymentId, signature, shippingInfo:{address, city, state, country, pinCode, name?, phone?}, billingInfo?:{same shape}, notes?}` (`phone` 10–13 digits with an optional `+`, `notes` ≤ 500). 400 on invalid input, and the payment intent is not consumed.
- `PUT /{id}`: records the acting admin and time in `statusHistory`; no entry or event when the order is already Delivered.
- Replace the order shape with `order = {_id, orderNumber?, user:{_id,name}, shippingInfo, billingInfo?, customer?:{name,email,phone}, orderItems:[{productId,name,photo,price,quantity,sku?,taxRate?}], subtotal, tax, shippingCharges, discount, total, status, statusHistory:[{status,at,actor}], couponCode?, notes?, payment?:{provider,status,amount,currency,paidAt}, createdAt}`. State that provider order and payment ids are never returned, that `orderNumber` looks like `ORD-20261017-K7M3QX`, and that orders placed before this change omit the new fields (`statusHistory` is `[]`).
- Pricing: tax is computed per product `taxRate` (default 18%), rounded to whole rupees per rate group.

- [ ] **Step 2: Requirements, ADR, compliance, rollout**

- `requirements.md`: update `FR5` to say tax uses each product's rate (default 18%); add `FR6a`: orders carry an order number, recipient, optional billing address, delivery notes and a status history with actor.
- `architecture.md`: add `ADR-017` dated 2026-10-17 above ADR-016: nested `OrderDetails`; the rulings from this plan's header (method/failureReason deferred, rate-not-amount per item, tax per rate group with exact legacy parity, no duplicate-number retry, contact snapshot at confirm); erasure extended to every new personal field. Rejected: storing a per-line tax amount and a separate invoice collection (premature until invoices exist).
- `compliance-consent.md`: in §7 Retention add a row "Order contact snapshot, billing address, recipient name/phone, delivery notes": kept until the account is deleted, then erased by `OrderAccountPolicy` (`UserDeletedEvent`); orders keep amounts, order number and history for tax. In §4 browser storage, note the `cart` entry now also holds the recipient name and phone.
- `test-rollout.md`: list `OrderDomainTest`, `PriceCalculatorTest` (tax parity), `OrderDetailsPersistenceIntegrationTest`, `OrderCheckoutDetailsIntegrationTest`, `OrderHistoryIntegrationTest`, `OrderErasureIntegrationTest`; rollout needs no migration, but note that the new tax rule changes the quote for any product that has a non-18% `taxRate`, so check product rates before deploying.

- [ ] **Step 3: Full verification**

```bash
cd backend && mvn verify
cd ../ecommerce-frontend && npm run build && npm run lint
```

Expected: all green, including `ModularityTests` and every pre-existing integration test.

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs: document order and payment fields and ADR-017"
```

---

## Self-Review

- **Spec coverage (§4 Order and PaymentInfo rows, Tier 1):** `orderNumber` → Tasks 1, 3, 4; `shippingAddress` (kept as `shippingInfo` for ADR-004) with recipient → Tasks 1, 3, 4; `billingAddress` → Tasks 3, 4; customer email/phone snapshot → Tasks 3, 4, 6; `statusHistory` → Tasks 1, 3, 5; `items[].sku/tax` → Tasks 1–4 (rate, per ruling 2); `discount` was already in `Quote`; `couponCode` → Tasks 3, 4; `notes` → Tasks 3, 4, 6; `PaymentInfo` `status/amount/currency/paidAt` → Tasks 1, 3, 4, 5, while `method` and `failureReason` are deferred (ruling 1). Frontend → Task 7. Docs and compliance → Task 8. Not here: shipments/tracking, refunds/returns, invoice numbers (Tier 2), an admin note field.
- **Placeholder scan:** none.
- **Type consistency:** `OrderItem` 7 components, `ShippingInfo` 7, `PaymentInfo` 7, `OrderDetails` 6, `Order` 10 (Task 1) match `MongoOrderRepository` mapping (Task 3), `CheckoutService` (Task 4) and `OrderView` (Task 5). `PaymentIntent` gains `currency` last (Task 3) and is read in Task 4. `ProductSnapshot` 7 components is used only by `ProductCatalogService` and `CheckoutService`. `OrderService.advance` goes from one argument (Task 1 placeholder) to `(id, actorId)` (Task 5), and `OrderController.process` is updated in the same task.
- **Review Focus coverage:** 1 → Task 6; 2 → Task 3 `legacyOrderWithoutNewFieldsLoadsWithDefaults` and Task 5 `legacyOrdersDisplayAndAdvance`; 3 → Task 2 `defaultRateCartsPriceExactlyAsBefore` and `CheckoutIntegrationTest`; 4 → Task 4 `invalidInputIsRejectedWithoutConsumingThePayment`; 5 → Task 5 `advancingRecordsWhoAndWhenAndStopsAtDelivered` and `customersCannotAdvanceOrders`.
