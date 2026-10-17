package com.sixpack.order.domain;

import java.time.Instant;
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
        Instant createdAt) {

    public Order withStatus(OrderStatus newStatus) {
        return new Order(id, userId, shippingInfo, items, quote, newStatus, payment, termsVersion, createdAt);
    }
}
