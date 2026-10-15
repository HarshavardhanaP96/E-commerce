package com.sixpack.order.infra.mongo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Mirrors the legacy `orders` schema; {@code payment} is new and optional. */
@Document("orders")
class OrderDocument {

    @Id
    String id;
    ShippingInfoDoc shippingInfo;
    @Indexed
    String user;
    Double subtotal;
    Double shippingCharges;
    Double tax;
    Double discount;
    Double total;
    @Indexed
    String status;
    List<OrderItemDoc> orderItems;
    PaymentDoc payment;
    @CreatedDate
    @Indexed
    Instant createdAt;
    @LastModifiedDate
    Instant updatedAt;

    static class ShippingInfoDoc {
        String address;
        String city;
        String state;
        String country;
        Long pinCode;
    }

    static class OrderItemDoc {
        String name;
        String photo;
        Double price;
        Integer quantity;
        @Field(targetType = FieldType.OBJECT_ID)
        String productId;
    }

    static class PaymentDoc {
        String provider;
        String orderId;
        String paymentId;
    }
}
