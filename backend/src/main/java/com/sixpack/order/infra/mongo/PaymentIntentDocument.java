package com.sixpack.order.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("payment_intents")
@CompoundIndex(name = "tenant_provider_order", def = "{'tenantId': 1, 'providerOrderId': 1}", unique = true)
class PaymentIntentDocument extends TenantDocument {

    static final String CREATED = "CREATED";
    static final String CONSUMED = "CONSUMED";

    @Id
    String id;
    String userId;
    String provider;
    String providerOrderId;
    List<OrderDocument.OrderItemDoc> items;
    String coupon;
    String currency;
    OrderDocument.AttributionDoc attribution;
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
