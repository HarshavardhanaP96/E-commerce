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
