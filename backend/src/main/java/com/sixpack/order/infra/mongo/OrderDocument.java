package com.sixpack.order.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Mirrors the legacy `orders` schema; {@code payment} and {@code termsVersion} are new and optional. */
@Document("orders")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_order_number", def = "{'tenantId': 1, 'orderNumber': 1}", unique = true,
                partialFilter = "{'orderNumber': {$type: 'string'}}"),
        @CompoundIndex(name = "tenant_created", def = "{'tenantId': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "tenant_status_created", def = "{'tenantId': 1, 'status': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "tenant_user_created", def = "{'tenantId': 1, 'user': 1, 'createdAt': 1}")
})
class OrderDocument extends TenantDocument {

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
    String termsVersion;
    String orderNumber;
    ShippingInfoDoc billingInfo;
    CustomerDoc customer;
    List<StatusEntryDoc> statusHistory;
    String couponCode;
    String notes;
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
        String name;
        String phone;
    }

    static class OrderItemDoc {
        String name;
        String photo;
        Double price;
        Integer quantity;
        @Field(targetType = FieldType.OBJECT_ID)
        String productId;
        String sku;
        Double taxRate;
        String variantId;
        String variantLabel;
        List<OptionDoc> options;
    }

    static class OptionDoc {
        String name;
        String value;
    }

    static class PaymentDoc {
        String provider;
        String orderId;
        String paymentId;
        String status;
        Double amount;
        String currency;
        Instant paidAt;
        String method;
        String failureReason;
        Long refundedPaise;
        List<RefundDoc> refunds;
    }

    static class RefundDoc {
        String providerRefundId;
        Double amount;
        String reason;
        String status;
        String actor;
        Instant at;
    }

    static class CustomerDoc {
        String name;
        String email;
        String phone;
    }

    static class StatusEntryDoc {
        String status;
        Instant at;
        String actor;
    }
}
