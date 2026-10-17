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
