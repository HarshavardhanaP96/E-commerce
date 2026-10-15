package com.sixpack.coupon.application;

import com.sixpack.coupon.CouponLookup;
import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRepository;
import com.sixpack.shared.ApiException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CouponService implements CouponLookup {

    private final CouponRepository coupons;

    CouponService(CouponRepository coupons) {
        this.coupons = coupons;
    }

    @Override
    public Optional<Double> discountFor(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return coupons.findByCode(code.trim()).map(Coupon::amount);
    }

    public List<Coupon> list() {
        return coupons.findAll();
    }

    public Coupon create(String code, double amount) {
        String normalized = code.trim();
        if (coupons.existsByCode(normalized)) {
            throw ApiException.conflict("Coupon already exists");
        }
        return coupons.save(new Coupon(null, normalized, amount));
    }

    public void delete(String id) {
        if (!coupons.deleteById(id)) {
            throw ApiException.notFound("Coupon not found");
        }
    }
}
