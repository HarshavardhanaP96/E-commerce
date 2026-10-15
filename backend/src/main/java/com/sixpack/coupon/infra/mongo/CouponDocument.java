package com.sixpack.coupon.infra.mongo;

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
}
