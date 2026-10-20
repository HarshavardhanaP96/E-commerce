package com.sixpack.coupon.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CouponBackfill {

    @Bean
    BackfillTarget couponsBackfill() {
        return () -> "coupons";
    }

    @Bean
    BackfillTarget couponRedemptionsBackfill() {
        return () -> "coupon_redemptions";
    }
}
