package com.sixpack.coupon.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** How often one customer has used one coupon. */
@Document("coupon_redemptions")
@CompoundIndex(name = "tenant_coupon_user", def = "{'tenantId': 1, 'couponId': 1, 'userId': 1}", unique = true)
class CouponRedemptionDocument extends TenantDocument {
    @Id
    String id;
    String couponId;
    String userId;
    Integer count;
}
