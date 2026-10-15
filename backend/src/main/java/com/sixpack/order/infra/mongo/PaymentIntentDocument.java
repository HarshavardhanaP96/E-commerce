package com.sixpack.order.infra.mongo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("payment_intents")
class PaymentIntentDocument {

    static final String CREATED = "CREATED";
    static final String CONSUMED = "CONSUMED";

    @Id
    String id;
    String userId;
    String provider;
    @Indexed(unique = true)
    String providerOrderId;
    List<OrderDocument.OrderItemDoc> items;
    String coupon;
    Double subtotal;
    Double tax;
    Double shippingCharges;
    Double discount;
    Double total;
    String status;
    @CreatedDate
    @Indexed(expireAfter = "24h")
    Instant createdAt;
}
