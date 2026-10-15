package com.sixpack.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

    @Test
    void chargesShippingAndTaxBelowThreshold() {
        Quote q = PriceCalculator.quote(List.of(new OrderItem("p1", "Tee", "", 250.0, 2)), 0);
        assertThat(q.subtotal()).isEqualTo(500.0);
        assertThat(q.shippingCharges()).isEqualTo(200.0);
        assertThat(q.tax()).isEqualTo(90.0);
        assertThat(q.total()).isEqualTo(790.0);
        assertThat(q.totalPaise()).isEqualTo(79_000);
    }

    @Test
    void freeShippingAboveThousandAndCouponApplied() {
        Quote q = PriceCalculator.quote(List.of(new OrderItem("p1", "Shoe", "", 1200.0, 1)), 100);
        assertThat(q.shippingCharges()).isZero();
        assertThat(q.tax()).isEqualTo(216.0);
        assertThat(q.discount()).isEqualTo(100.0);
        assertThat(q.total()).isEqualTo(1316.0);
    }

    @Test
    void discountNeverExceedsSubtotal() {
        Quote q = PriceCalculator.quote(List.of(new OrderItem("p1", "Pin", "", 10.0, 1)), 5000);
        assertThat(q.discount()).isEqualTo(10.0);
        assertThat(q.total()).isEqualTo(202.0); // tax 2 + shipping 200
    }

    @Test
    void handlesFractionalPricesWithoutDrift() {
        Quote q = PriceCalculator.quote(List.of(new OrderItem("p1", "A", "", 0.1, 3), new OrderItem("p2", "B", "", 0.2, 1)), 0);
        assertThat(q.subtotal()).isEqualTo(0.5);
    }
}
