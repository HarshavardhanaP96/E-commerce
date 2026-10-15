package com.sixpack.coupon;

import java.util.Optional;

/** Public API of the coupon module. */
public interface CouponLookup {

    /** @return the flat ₹ discount for a code, or empty if the code does not exist */
    Optional<Double> discountFor(String code);
}
