package com.sixpack.coupon.infra.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** How often one customer has used one coupon. */
@Document("coupon_redemptions")
@CompoundIndex(name = "coupon_user", def = "{'couponId': 1, 'userId': 1}", unique = true)
class CouponRedemptionDocument {
    @Id
    String id;
    String couponId;
    String userId;
    Integer count;
}
