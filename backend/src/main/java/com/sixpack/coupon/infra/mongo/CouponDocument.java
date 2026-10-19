package com.sixpack.coupon.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("coupons")
@CompoundIndex(name = "tenant_coupon", def = "{'tenantId': 1, 'coupon': 1}", unique = true)
class CouponDocument extends TenantDocument {

    @Id
    String id;
    String coupon;
    Double amount;
    String type;
    Double maxDiscount;
    Double minOrderValue;
    Instant startsAt;
    Instant expiresAt;
    Integer usageLimit;
    Integer usedCount;
    Integer perUserLimit;
    Boolean active;
}
