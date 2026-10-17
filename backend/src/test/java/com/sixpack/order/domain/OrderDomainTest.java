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
