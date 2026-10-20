package com.sixpack.coupon;

/** What the coupon module needs to know about a customer's past. Implemented by the order module. */
public interface CustomerHistory {

    boolean hasPlacedOrder(String userId);
}
