package com.sixpack.order.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record Order(
        String id,
        String userId,
        ShippingInfo shippingInfo,
        List<OrderItem> items,
        Quote quote,
        OrderStatus status,
        PaymentInfo payment,
        String termsVersion,
        Instant createdAt,
        OrderDetails details) {

    public Order {
        details = details == null ? OrderDetails.EMPTY : details;
    }

    /** Pre-details shape, kept so existing callers compile unchanged. */
    public Order(String id, String userId, ShippingInfo shippingInfo, List<OrderItem> items, Quote quote,
                 OrderStatus status, PaymentInfo payment, String termsVersion, Instant createdAt) {
        this(id, userId, shippingInfo, items, quote, status, payment, termsVersion, createdAt, OrderDetails.EMPTY);
    }

    public Order withDetails(OrderDetails newDetails) {
        return new Order(id, userId, shippingInfo, items, quote, status, payment, termsVersion, createdAt,
                newDetails);
    }

    /** Moves to {@code next} and records who did it and when. The previous history is never edited. */
    public Order withStatus(OrderStatus next, String actor, Instant at) {
        List<StatusEntry> history = new ArrayList<>(details.statusHistory());
        history.add(new StatusEntry(next, at, actor));
        return new Order(id, userId, shippingInfo, items, quote, next, payment, termsVersion, createdAt,
                new OrderDetails(details.orderNumber(), details.billingInfo(), details.customer(), history,
                        details.couponCode(), details.notes()));
    }
}
