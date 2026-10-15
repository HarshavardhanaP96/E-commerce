package com.sixpack.coupon.infra.mongo;

import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
        CouponDocument doc = new CouponDocument();
        doc.id = coupon.id();
        doc.coupon = coupon.code();
        doc.amount = coupon.amount();
        return toDomain(mongo.save(doc));
    }

    @Override
    public boolean deleteById(String id) {
        return mongo.remove(Query.query(Criteria.where("id").is(id)), CouponDocument.class).getDeletedCount() > 0;
    }

    private static Query byCode(String code) {
        return Query.query(Criteria.where("coupon").is(code));
    }

    private static Coupon toDomain(CouponDocument d) {
        return new Coupon(d.id, d.coupon, d.amount == null ? 0 : d.amount);
    }
}
