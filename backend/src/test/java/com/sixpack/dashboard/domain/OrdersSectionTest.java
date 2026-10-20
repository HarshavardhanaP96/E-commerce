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
