package com.sixpack.coupon.infra.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("coupons")
class CouponDocument {

    @Id
    String id;
    @Indexed(unique = true)
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
