package com.sixpack.coupon.infra.mongo;

import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRules;
import com.sixpack.coupon.domain.CouponType;
import com.sixpack.coupon.domain.CouponRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoCouponRepository implements CouponRepository {

    private final MongoTemplate mongo;

    MongoCouponRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Coupon> findByCode(String code) {
        return Optional.ofNullable(mongo.findOne(byCode(code), CouponDocument.class)).map(MongoCouponRepository::toDomain);
    }

    @Override
    public List<Coupon> findAll() {
        return mongo.findAll(CouponDocument.class).stream().map(MongoCouponRepository::toDomain).toList();
    }

    @Override
    public boolean existsByCode(String code) {
        return mongo.exists(byCode(code), CouponDocument.class);
    }

    @Override
    public Coupon save(Coupon coupon) {
        CouponDocument doc = coupon.id() == null ? null : mongo.findById(coupon.id(), CouponDocument.class);
        if (doc == null) {
            doc = new CouponDocument();
            doc.usedCount = 0;
        }
        doc.id = coupon.id();
        doc.coupon = coupon.code();
        doc.amount = coupon.amount();
        CouponRules r = coupon.rules();
        doc.type = r.type().name();
        doc.maxDiscount = r.maxDiscount();
        doc.minOrderValue = r.minOrderValue();
        doc.startsAt = r.startsAt();
        doc.expiresAt = r.expiresAt();
        doc.usageLimit = r.usageLimit();
        doc.perUserLimit = r.perUserLimit();
        doc.active = r.active();
        doc.productIds = r.productIds().stream().sorted().toList();
        doc.categories = r.categories().stream().sorted().toList();
        doc.firstOrderOnly = r.firstOrderOnly();
        return toDomain(mongo.save(doc));
    }

    @Override
    public boolean deleteById(String id) {
        return mongo.remove(Query.query(Criteria.where("id").is(id)), CouponDocument.class).getDeletedCount() > 0;
    }

    @Override
    public Optional<Coupon> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, CouponDocument.class)).map(MongoCouponRepository::toDomain);
    }

    @Override
    public int redemptionsBy(String couponId, String userId) {
        CouponRedemptionDocument d = mongo.findOne(redemption(couponId, userId), CouponRedemptionDocument.class);
        return d == null || d.count == null ? 0 : d.count;
    }

    @Override
    public void recordRedemption(String couponId, String userId) {
        mongo.upsert(redemption(couponId, userId), new Update().inc("count", 1), CouponRedemptionDocument.class);
        mongo.updateFirst(Query.query(Criteria.where("id").is(couponId)), new Update().inc("usedCount", 1),
                CouponDocument.class);
    }

    @Override
    public void releaseRedemption(String couponId, String userId) {
        var released = mongo.updateFirst(redemption(couponId, userId).addCriteria(Criteria.where("count").gt(0)),
                new Update().inc("count", -1), CouponRedemptionDocument.class);
        if (released.getModifiedCount() == 1) {
            mongo.updateFirst(Query.query(Criteria.where("id").is(couponId).and("usedCount").gt(0)),
                    new Update().inc("usedCount", -1), CouponDocument.class);
        }
    }

    @Override
    public void deleteRedemptionsOfUser(String userId) {
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), CouponRedemptionDocument.class);
    }

    private static Query redemption(String couponId, String userId) {
        return Query.query(Criteria.where("couponId").is(couponId).and("userId").is(userId));
    }

    private static Query byCode(String code) {
        return Query.query(Criteria.where("coupon").is(code));
    }

    private static Coupon toDomain(CouponDocument d) {
        CouponRules rules = new CouponRules(d.type == null ? null : CouponType.valueOf(d.type), d.maxDiscount,
                d.minOrderValue, d.startsAt, d.expiresAt, d.usageLimit, d.perUserLimit,
                d.active == null || d.active,
                d.productIds == null ? null : new HashSet<>(d.productIds),
                d.categories == null ? null : new HashSet<>(d.categories), Boolean.TRUE.equals(d.firstOrderOnly));
        return new Coupon(d.id, d.coupon, d.amount == null ? 0 : d.amount, rules,
                d.usedCount == null ? 0 : d.usedCount);
    }
}
