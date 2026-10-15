package com.sixpack.order.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderItem;
import com.sixpack.order.domain.ShippingInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;

record OrderView(
        @JsonProperty("_id") String id,
        UserRef user,
        ShippingInfo shippingInfo,
        List<OrderItem> orderItems,
        double subtotal,
        double tax,
        double shippingCharges,
        double discount,
        double total,
        String status,
        Instant createdAt) {

    record UserRef(@JsonProperty("_id") String id, String name) {
    }

    static OrderView from(Order o, Map<String, String> userNames) {
        return new OrderView(o.id(), new UserRef(o.userId(), userNames.getOrDefault(o.userId(), "Deleted user")),
                o.shippingInfo(), o.items(), o.quote().subtotal(), o.quote().tax(), o.quote().shippingCharges(),
                o.quote().discount(), o.quote().total(), o.status().wireValue(), o.createdAt());
    }
}
