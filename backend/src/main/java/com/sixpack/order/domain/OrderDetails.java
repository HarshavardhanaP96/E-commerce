package com.sixpack.order.domain;

import java.util.List;

/** Optional order fields. Orders placed before they existed carry {@link #EMPTY}. */
public record OrderDetails(
        String orderNumber,
        ShippingInfo billingInfo,
        Customer customer,
        List<StatusEntry> statusHistory,
        String couponCode,
        String notes) {

    public static final OrderDetails EMPTY = new OrderDetails(null, null, null, null, null, null);

    public OrderDetails {
        statusHistory = statusHistory == null ? List.of() : List.copyOf(statusHistory);
    }
}
