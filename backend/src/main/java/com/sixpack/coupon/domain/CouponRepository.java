package com.sixpack.coupon.domain;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {

    Optional<Coupon> findByCode(String code);

    List<Coupon> findAll();

    boolean existsByCode(String code);

    Coupon save(Coupon coupon);

    boolean deleteById(String id);

    Optional<Coupon> findById(String id);

    int redemptionsBy(String couponId, String userId);

    /** Counts one use against the coupon and against this customer. */
    void recordRedemption(String couponId, String userId);

    /** Undoes {@link #recordRedemption}; counters never go below zero. */
    void releaseRedemption(String couponId, String userId);

    /** Erasure: removes the per-customer counters but leaves each coupon's total. */
    void deleteRedemptionsOfUser(String userId);
}
