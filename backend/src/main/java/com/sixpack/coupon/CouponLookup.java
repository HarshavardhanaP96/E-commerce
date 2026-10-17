package com.sixpack.coupon;

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

    /** Counts one use. A no-op if the code no longer exists. */
    void redeem(String code, String userId);

    /** Undoes {@link #redeem} when the order could not be created. */
    void release(String code, String userId);
}
