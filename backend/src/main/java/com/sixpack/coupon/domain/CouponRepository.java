package com.sixpack.coupon.domain;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {

    Optional<Coupon> findByCode(String code);

    List<Coupon> findAll();

    boolean existsByCode(String code);

    Coupon save(Coupon coupon);

    boolean deleteById(String id);
}
