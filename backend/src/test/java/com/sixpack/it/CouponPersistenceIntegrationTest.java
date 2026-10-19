package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRepository;
import com.sixpack.coupon.domain.CouponRules;
import com.sixpack.coupon.domain.CouponType;
import java.time.Instant;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CouponPersistenceIntegrationTest extends IntegrationTest {

    @Autowired
    CouponRepository coupons;

    @Test
    void roundTripsEveryRule() {
        Instant from = Instant.parse("2026-10-18T10:00:00Z");
        Instant to = Instant.parse("2026-12-31T18:30:00Z");
        Coupon saved = coupons.save(new Coupon(null, "FESTIVE", 15, new CouponRules(CouponType.PERCENT, 200.0, 999.0,
                from, to, 100, 2, false), 0));

        Coupon loaded = coupons.findById(saved.id()).orElseThrow();

        assertThat(loaded.rules()).isEqualTo(saved.rules());
        assertThat(loaded.amount()).isEqualTo(15.0);
        assertThat(coupons.findByCode("FESTIVE")).map(Coupon::id).contains(saved.id());
    }

    @Test
    void legacyDocumentLoadsAsAnActiveFlatCoupon() {
        ObjectId id = new ObjectId();
        mongo.getCollection("coupons").insertOne(new Document("_id", id).append("coupon", "OLD").append("amount", 100.0));
        backfillLegacy();

        Coupon c = coupons.findById(id.toHexString()).orElseThrow();

        assertThat(c.rules()).isEqualTo(CouponRules.LEGACY);
        assertThat(c.usedCount()).isZero();
        assertThat(c.rules().active()).isTrue();
    }

    @Test
    void redemptionsAreCountedPerCouponAndPerUserAndNeverGoNegative() {
        Coupon c = coupons.save(new Coupon(null, "COUNT", 10));

        coupons.recordRedemption(c.id(), "alice");
        coupons.recordRedemption(c.id(), "alice");
        coupons.recordRedemption(c.id(), "bob");

        assertThat(coupons.findById(c.id()).orElseThrow().usedCount()).isEqualTo(3);
        assertThat(coupons.redemptionsBy(c.id(), "alice")).isEqualTo(2);
        assertThat(coupons.redemptionsBy(c.id(), "bob")).isEqualTo(1);
        assertThat(coupons.redemptionsBy(c.id(), "carol")).isZero();

        coupons.releaseRedemption(c.id(), "alice");
        assertThat(coupons.redemptionsBy(c.id(), "alice")).isEqualTo(1);
        assertThat(coupons.findById(c.id()).orElseThrow().usedCount()).isEqualTo(2);

        coupons.releaseRedemption(c.id(), "carol"); // never redeemed: nothing happens
        coupons.releaseRedemption(c.id(), "bob");
        coupons.releaseRedemption(c.id(), "bob");
        assertThat(coupons.redemptionsBy(c.id(), "bob")).isZero();
        assertThat(coupons.findById(c.id()).orElseThrow().usedCount()).isEqualTo(1);
    }

    @Test
    void savingAnEditedCouponKeepsItsUsage() {
        Coupon c = coupons.save(new Coupon(null, "KEEP", 10));
        coupons.recordRedemption(c.id(), "alice");

        Coupon edited = coupons.findById(c.id()).orElseThrow().withRules(20,
                new CouponRules(CouponType.FLAT, null, null, null, null, 5, null, true));
        coupons.save(edited);

        Coupon reloaded = coupons.findById(c.id()).orElseThrow();
        assertThat(reloaded.amount()).isEqualTo(20.0);
        assertThat(reloaded.rules().usageLimit()).isEqualTo(5);
        assertThat(reloaded.usedCount()).isEqualTo(1);
        assertThat(coupons.redemptionsBy(c.id(), "alice")).isEqualTo(1);
    }

    @Test
    void deletingAUsersRedemptionsKeepsTheCouponsTotal() {
        Coupon c = coupons.save(new Coupon(null, "ERASE", 10));
        coupons.recordRedemption(c.id(), "alice");
        coupons.recordRedemption(c.id(), "bob");

        coupons.deleteRedemptionsOfUser("alice");

        assertThat(coupons.redemptionsBy(c.id(), "alice")).isZero();
        assertThat(coupons.redemptionsBy(c.id(), "bob")).isEqualTo(1);
        assertThat(coupons.findById(c.id()).orElseThrow().usedCount()).isEqualTo(2);
    }
}
