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
