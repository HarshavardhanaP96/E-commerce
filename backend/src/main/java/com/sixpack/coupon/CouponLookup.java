package com.sixpack.coupon;

import java.util.List;

/** Public API of the coupon module. */
public interface CouponLookup {

    /**
     * Checks a code against a cart and returns the ₹ discount.
     *
     * @param subtotal cart subtotal in rupees (0 if unknown)
     * @param userId   the customer, or null for an anonymous preview (per-customer limits are then skipped)
     * @throws com.sixpack.shared.ApiException 400 with a readable reason if the code is unknown or not usable
     */
    double discountFor(String code, double subtotal, String userId);

    /**
     * Like {@link #discountFor(String, double, String)} for a cart whose lines are known, so a coupon limited to
     * some products or categories discounts only those, and "first order only" can be checked.
     */
    double discountFor(String code, List<CouponLine> lines, String userId);

    /** Counts one use. A no-op if the code no longer exists. */
    void redeem(String code, String userId);

    /** Undoes {@link #redeem} when the order could not be created. */
    void release(String code, String userId);
}
