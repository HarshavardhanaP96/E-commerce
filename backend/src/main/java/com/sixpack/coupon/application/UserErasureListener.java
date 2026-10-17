package com.sixpack.coupon.application;

import com.sixpack.coupon.domain.CouponRepository;
import com.sixpack.user.UserDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Account erasure: forget which coupons a deleted user used. Each coupon's total count stays. */
@Component
class UserErasureListener {

    private final CouponRepository coupons;

    UserErasureListener(CouponRepository coupons) {
        this.coupons = coupons;
    }

    @EventListener
    void onUserDeleted(UserDeletedEvent event) {
        coupons.deleteRedemptionsOfUser(event.userId());
    }
}
