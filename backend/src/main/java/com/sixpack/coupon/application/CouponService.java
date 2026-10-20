package com.sixpack.coupon.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.coupon.CouponLine;
import com.sixpack.coupon.CouponLookup;
import com.sixpack.coupon.CustomerHistory;
import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRepository;
import com.sixpack.coupon.domain.CouponRules;
import com.sixpack.coupon.domain.CouponType;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.StoreConfig;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class CouponService implements CouponLookup {


    /** Everything an admin can set when creating a coupon. Null means "not set". */
    public record CreateCommand(String code, double amount, String type, Double maxDiscount, Double minOrderValue,
                                Instant startsAt, Instant expiresAt, Integer usageLimit, Integer perUserLimit,
                                Boolean active, List<String> productIds, List<String> categories,
                                Boolean firstOrderOnly) {
    }

    /** What an admin can change later. Null means "keep the current value". */
    public record UpdateCommand(Double amount, Double maxDiscount, Double minOrderValue, Instant startsAt,
                                Instant expiresAt, Integer usageLimit, Integer perUserLimit, Boolean active,
                                List<String> productIds, List<String> categories, Boolean firstOrderOnly) {
    }

    private final CouponRepository coupons;
    private final AuditTrail audit;
    private final CustomerHistory history;

    private final StoreConfig store;

    CouponService(CouponRepository coupons, AuditTrail audit, CustomerHistory history, StoreConfig store) {
        this.store = store;
        this.coupons = coupons;
        this.audit = audit;
        this.history = history;
    }

    @Override
    public double discountFor(String code, double subtotal, String userId) {
        Coupon coupon = find(code).orElseThrow(() -> ApiException.badRequest("Invalid coupon"));
        return accepted(coupon.evaluate(subtotal, redemptionsBy(coupon, userId), Instant.now()));
    }

    @Override
    public double discountFor(String code, List<CouponLine> lines, String userId) {
        Coupon coupon = find(code).orElseThrow(() -> ApiException.badRequest("Invalid coupon"));
        // An anonymous preview can't be checked for a previous order; checkout always has a user.
        boolean returning = coupon.rules().firstOrderOnly() && userId != null && history.hasPlacedOrder(userId);
        return accepted(coupon.evaluate(lines, redemptionsBy(coupon, userId), returning, Instant.now()));
    }

    private int redemptionsBy(Coupon coupon, String userId) {
        return userId == null ? 0 : coupons.redemptionsBy(coupon.id(), userId);
    }

    private static double accepted(com.sixpack.coupon.domain.Evaluation evaluation) {
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
                c.usageLimit(), c.perUserLimit(), c.active() == null || c.active(), ids(c.productIds()),
                categories(c.categories()), Boolean.TRUE.equals(c.firstOrderOnly()));
        validate(c.amount(), rules);
        Coupon saved = coupons.save(new Coupon(null, code, c.amount(), rules, 0));
        audit.record(AuditEvent.of(AuditAction.COUPON_CREATED).on("coupon", saved.id())
                .changing(Changes.diff(Map.of(), CouponAudit.view(saved), CouponAudit.POLICY)));
        return saved;
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
                u.active() != null ? u.active() : r.active(),
                u.productIds() != null ? ids(u.productIds()) : r.productIds(),
                u.categories() != null ? categories(u.categories()) : r.categories(),
                u.firstOrderOnly() != null ? u.firstOrderOnly() : r.firstOrderOnly());
        double amount = u.amount() != null ? u.amount() : current.amount();
        validate(amount, merged);
        Coupon saved = coupons.save(current.withRules(amount, merged));
        audit.record(AuditEvent.of(AuditAction.COUPON_UPDATED).on("coupon", id)
                .changing(Changes.diff(CouponAudit.view(current), CouponAudit.view(saved), CouponAudit.POLICY)));
        return saved;
    }

    public void delete(String id) {
        Coupon current = coupons.findById(id).orElseThrow(() -> ApiException.notFound("Coupon not found"));
        if (!coupons.deleteById(id)) {
            throw ApiException.notFound("Coupon not found");
        }
        audit.record(AuditEvent.of(AuditAction.COUPON_DELETED).on("coupon", id)
                .changing(Changes.diff(CouponAudit.view(current), Map.of(), CouponAudit.POLICY)));
    }

    private static Set<String> ids(List<String> values) {
        return values == null ? Set.of() : values.stream().filter(v -> v != null && !v.isBlank())
                .map(String::trim).collect(Collectors.toSet());
    }

    /** Matches {@code Product.normalizeCategory}: products store their category trimmed and lower-case. */
    private static Set<String> categories(List<String> values) {
        return values == null ? Set.of() : values.stream().filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private java.util.Optional<Coupon> find(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        return coupons.findByCode(code.trim());
    }

    private void validate(double amount, CouponRules rules) {
        if (amount <= 0) {
            throw ApiException.badRequest("amount: must be positive");
        }
        if (rules.type() == CouponType.PERCENT && amount > 100) {
            throw ApiException.badRequest("amount: a percent coupon cannot exceed 100");
        }
        double maxFlat = store.effective().maxCouponAmount();
        if (rules.type() == CouponType.FLAT && amount > maxFlat) {
            throw ApiException.badRequest("amount: must be at most " + (long) maxFlat);
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
