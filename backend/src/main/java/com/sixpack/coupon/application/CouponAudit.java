package com.sixpack.coupon.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the audit log may say about coupons. {@code usedCount} is deliberately absent: redemptions are not edits. */
final class CouponAudit {

    static final FieldPolicy POLICY = Changes.policy(List.of("code", "type", "amount", "maxDiscount",
            "minOrderValue", "startsAt", "expiresAt", "usageLimit", "perUserLimit", "active", "productIds", "categories",
            "firstOrderOnly"), List.of());

    private CouponAudit() {
    }

    static Map<String, Object> view(Coupon c) {
        CouponRules r = c.rules();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", c.code());
        m.put("type", r.type().name());
        m.put("amount", c.amount());
        m.put("maxDiscount", r.maxDiscount());
        m.put("minOrderValue", r.minOrderValue());
        m.put("startsAt", r.startsAt());
        m.put("expiresAt", r.expiresAt());
        m.put("usageLimit", r.usageLimit());
        m.put("perUserLimit", r.perUserLimit());
        m.put("active", r.active());
        m.put("productIds", r.productIds().stream().sorted().toList());
        m.put("categories", r.categories().stream().sorted().toList());
        m.put("firstOrderOnly", r.firstOrderOnly());
        return m;
    }
}
