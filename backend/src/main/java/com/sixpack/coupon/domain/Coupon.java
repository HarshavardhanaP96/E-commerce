package com.sixpack.coupon.domain;

import com.sixpack.coupon.CouponLine;
import java.time.Instant;
import java.util.List;

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
     * Checks the coupon against a bare subtotal, for callers that cannot say what is in the cart. A scoped coupon
     * can't be priced that way, and "first order only" can't be checked, so that rule is left to checkout.
     *
     * @param subtotal        cart subtotal in rupees (0 when unknown, for example an old client previewing a code)
     * @param userRedemptions how many times this customer has already used the coupon
     */
    public Evaluation evaluate(double subtotal, int userRedemptions, Instant now) {
        if (rules.scoped()) {
            return Evaluation.reject("This coupon only applies to selected items");
        }
        return evaluate(List.of(new CouponLine(null, null, subtotal)), userRedemptions, false, now);
    }

    /**
     * @param lines            the priced cart lines
     * @param userRedemptions  how many times this customer has already used the coupon
     * @param hasPreviousOrder whether this customer has placed an order before
     */
    public Evaluation evaluate(List<CouponLine> lines, int userRedemptions, boolean hasPreviousOrder, Instant now) {
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
        if (rules.firstOrderOnly() && hasPreviousOrder) {
            return Evaluation.reject("This coupon is only for your first order");
        }
        double subtotal = lines.stream().mapToDouble(CouponLine::amount).sum();
        if (rules.minOrderValue() != null && subtotal > 0 && subtotal < rules.minOrderValue()) {
            return Evaluation.reject("Minimum order value for this coupon is ₹" + rupees(rules.minOrderValue()));
        }
        double eligible = subtotal;
        if (rules.scoped()) {
            eligible = lines.stream().filter(this::inScope).mapToDouble(CouponLine::amount).sum();
            if (eligible <= 0) {
                return Evaluation.reject("This coupon doesn't apply to the items in your cart");
            }
        }
        return Evaluation.accept(discountFor(eligible));
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

    private boolean inScope(CouponLine line) {
        return rules.productIds().contains(line.productId())
                || (line.category() != null && rules.categories().contains(line.category()));
    }

    private double discountFor(double eligible) {
        if (rules.type() == CouponType.FLAT) {
            return rules.scoped() ? Math.min(amount, eligible) : amount;
        }
        double percent = Math.round(eligible * 100 * amount / 100.0) / 100.0;
        return rules.maxDiscount() == null ? percent : Math.min(percent, rules.maxDiscount());
    }

    private static String rupees(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format("%.2f", value);
    }
}
