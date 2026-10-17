package com.sixpack.coupon.application;

import com.sixpack.coupon.CouponLookup;
import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRepository;
import com.sixpack.coupon.domain.CouponRules;
import com.sixpack.coupon.domain.CouponType;
import com.sixpack.shared.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CouponService implements CouponLookup {

    private static final double MAX_FLAT = 100_000;

    /** Everything an admin can set when creating a coupon. Null means "not set". */
    public record CreateCommand(String code, double amount, String type, Double maxDiscount, Double minOrderValue,
                                Instant startsAt, Instant expiresAt, Integer usageLimit, Integer perUserLimit,
                                Boolean active) {
    }

    /** What an admin can change later. Null means "keep the current value". */
    public record UpdateCommand(Double amount, Double maxDiscount, Double minOrderValue, Instant startsAt,
                                Instant expiresAt, Integer usageLimit, Integer perUserLimit, Boolean active) {
    }

    private final CouponRepository coupons;

    CouponService(CouponRepository coupons) {
        this.coupons = coupons;
    }

    @Override
    public double discountFor(String code, double subtotal, String userId) {
        Coupon coupon = find(code).orElseThrow(() -> ApiException.badRequest("Invalid coupon"));
        int used = userId == null ? 0 : coupons.redemptionsBy(coupon.id(), userId);
        var evaluation = coupon.evaluate(subtotal, used, Instant.now());
        if (!evaluation.accepted()) {
            throw ApiException.badRequest(evaluation.rejection());
        }
        return evaluation.discount();
    }

    @Override
    public void redeem(String code, String userId) {
        find(code).ifPresent(c -> coupons.recordRedemption(c.id(), userId));
    }

    @Override
    public void release(String code, String userId) {
        find(code).ifPresent(c -> coupons.releaseRedemption(c.id(), userId));
    }

    public List<Coupon> list() {
        return coupons.findAll();
    }

    public Coupon create(CreateCommand c) {
        String code = c.code().trim();
        if (coupons.existsByCode(code)) {
            throw ApiException.conflict("Coupon already exists");
        }
        CouponType type = CouponType.parse(c.type());
        Instant now = Instant.now();
        if (c.expiresAt() != null && !c.expiresAt().isAfter(now)) {
            throw ApiException.badRequest("expiresAt: must be in the future");
        }
        CouponRules rules = new CouponRules(type, c.maxDiscount(), c.minOrderValue(), c.startsAt(), c.expiresAt(),
                c.usageLimit(), c.perUserLimit(), c.active() == null || c.active());
        validate(c.amount(), rules);
        return coupons.save(new Coupon(null, code, c.amount(), rules, 0));
    }

    public Coupon update(String id, UpdateCommand u) {
        Coupon current = coupons.findById(id).orElseThrow(() -> ApiException.notFound("Coupon not found"));
        CouponRules r = current.rules();
        CouponRules merged = new CouponRules(r.type(),
                u.maxDiscount() != null ? u.maxDiscount() : r.maxDiscount(),
                u.minOrderValue() != null ? u.minOrderValue() : r.minOrderValue(),
                u.startsAt() != null ? u.startsAt() : r.startsAt(),
                u.expiresAt() != null ? u.expiresAt() : r.expiresAt(),
                u.usageLimit() != null ? u.usageLimit() : r.usageLimit(),
                u.perUserLimit() != null ? u.perUserLimit() : r.perUserLimit(),
                u.active() != null ? u.active() : r.active());
        double amount = u.amount() != null ? u.amount() : current.amount();
        validate(amount, merged);
        return coupons.save(current.withRules(amount, merged));
    }

    public void delete(String id) {
        if (!coupons.deleteById(id)) {
            throw ApiException.notFound("Coupon not found");
        }
    }

    private java.util.Optional<Coupon> find(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        return coupons.findByCode(code.trim());
    }

    private static void validate(double amount, CouponRules rules) {
        if (amount <= 0) {
            throw ApiException.badRequest("amount: must be positive");
        }
        if (rules.type() == CouponType.PERCENT && amount > 100) {
            throw ApiException.badRequest("amount: a percent coupon cannot exceed 100");
        }
        if (rules.type() == CouponType.FLAT && amount > MAX_FLAT) {
            throw ApiException.badRequest("amount: must be at most " + (long) MAX_FLAT);
        }
        if (rules.maxDiscount() != null && rules.type() != CouponType.PERCENT) {
            throw ApiException.badRequest("maxDiscount: only applies to percent coupons");
        }
        if (rules.startsAt() != null && rules.expiresAt() != null && !rules.startsAt().isBefore(rules.expiresAt())) {
            throw ApiException.badRequest("startsAt: must be before expiresAt");
        }
        if (rules.usageLimit() != null && rules.usageLimit() < 1) {
            throw ApiException.badRequest("usageLimit: must be at least 1");
        }
        if (rules.perUserLimit() != null && rules.perUserLimit() < 1) {
            throw ApiException.badRequest("perUserLimit: must be at least 1");
        }
    }
}
