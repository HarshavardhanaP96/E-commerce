package com.sixpack.coupon.domain;

import java.time.Instant;
import java.util.Set;

/**
 * Eligibility rules. Coupons stored before rules existed carry {@link #LEGACY}.
 *
 * @param maxDiscount   rupee cap for PERCENT coupons, or null
 * @param minOrderValue smallest cart subtotal that qualifies, or null
 * @param startsAt      first moment the coupon works (inclusive), or null
 * @param expiresAt     first moment it no longer works (exclusive), or null
 * @param usageLimit    total redemptions allowed, or null
 * @param perUserLimit  redemptions allowed per customer, or null
 * @param productIds    the coupon discounts only these products (alternative to {@code categories}); empty = all
 * @param categories    the coupon discounts only products in these categories; empty = all
 * @param firstOrderOnly only customers who have never placed an order can use it
 */
public record CouponRules(CouponType type, Double maxDiscount, Double minOrderValue, Instant startsAt,
                          Instant expiresAt, Integer usageLimit, Integer perUserLimit, boolean active, Set<String> productIds,
                          Set<String> categories, boolean firstOrderOnly) {

    public static final CouponRules LEGACY = new CouponRules(CouponType.FLAT, null, null, null, null, null, null, true);

    public CouponRules {
        type = type == null ? CouponType.FLAT : type;
        productIds = productIds == null ? Set.of() : Set.copyOf(productIds);
        categories = categories == null ? Set.of() : Set.copyOf(categories);
    }

    /** Pre-scoping shape, kept so existing callers compile unchanged. */
    public CouponRules(CouponType type, Double maxDiscount, Double minOrderValue, Instant startsAt,
                       Instant expiresAt, Integer usageLimit, Integer perUserLimit, boolean active) {
        this(type, maxDiscount, minOrderValue, startsAt, expiresAt, usageLimit, perUserLimit, active, null, null,
                false);
    }

    /** Whether the discount is limited to some products or categories. */
    public boolean scoped() {
        return !productIds.isEmpty() || !categories.isEmpty();
    }
}
