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

    @Test
    void aStoresOwnShippingFeeAndThresholdAreUsed() {
        PricingRules rules = new PricingRules(49.0, 499.0, 18.0);
        assertThat(PriceCalculator.quote(List.of(item(400, 1, 0.0)), 0, rules).shippingCharges()).isEqualTo(49.0);
        assertThat(PriceCalculator.quote(List.of(item(499, 1, 0.0)), 0, rules).shippingCharges()).isEqualTo(49.0);
        assertThat(PriceCalculator.quote(List.of(item(500, 1, 0.0)), 0, rules).shippingCharges()).isZero();
    }

    @Test
    void aStoresDefaultRateAppliesOnlyToItemsWithoutTheirOwn() {
        PricingRules rules = new PricingRules(0.0, 0.0, 5.0);
        Quote q = PriceCalculator.quote(List.of(item(100, 1, null), item(100, 1, 12.0)), 0, rules);
        assertThat(q.tax()).isEqualTo(17.0);
    }

    @Test
    void theStandardRulesPriceExactlyAsBefore() {
        List<OrderItem> cart = List.of(new OrderItem("p1", "Tee", "", 250.0, 2));
        assertThat(PriceCalculator.quote(cart, 0, PricingRules.STANDARD)).isEqualTo(PriceCalculator.quote(cart, 0));
        assertThat(PricingRules.STANDARD).isEqualTo(new PricingRules(200.0, 1000.0, 18.0));
    }

    @Test
    void stampingGivesEveryItemTheRateItIsChargedAt() {
        List<OrderItem> stamped = new PricingRules(0.0, 0.0, 5.0).stamp(List.of(item(100, 1, null), item(100, 1, 12.0)));
        assertThat(stamped).extracting(OrderItem::taxRate).containsExactly(5.0, 12.0);
    }
}
