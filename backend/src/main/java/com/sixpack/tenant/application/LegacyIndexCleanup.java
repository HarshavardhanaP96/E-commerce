package com.sixpack.tenant.application;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Component;

/**
 * Drops the global unique indexes that multi-tenancy replaced with per-tenant ones. Spring Data only ever adds
 * indexes, so without this a second tenant could not reuse a slug, email or coupon code. Safe to run on every
 * start: indexes that are already gone are skipped.
 */
@Component
@Order(3)
public class LegacyIndexCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyIndexCleanup.class);

    /** Collection to the names of single-tenant unique indexes that must not exist any more. */
    static final Map<String, List<String>> LEGACY = Map.of(
            "products", List.of("slug_1", "sku_1", "variants.items.sku_1"),
            "categories", List.of("slug_1"),
            "coupons", List.of("coupon_1"),
            "coupon_redemptions", List.of("coupon_user"),
            "orders", List.of("orderNumber_1"),
            "payment_intents", List.of("providerOrderId_1"));

    private final MongoTemplate mongo;

    LegacyIndexCleanup(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        cleanup();
    }

    /** @return how many indexes were dropped */
    public int cleanup() {
        int dropped = 0;
        for (Map.Entry<String, List<String>> entry : LEGACY.entrySet()) {
            List<String> present;
            try {
                present = mongo.indexOps(entry.getKey()).getIndexInfo().stream().map(IndexInfo::getName).toList();
            } catch (DataAccessException e) {
                continue; // the collection does not exist yet
            }
            for (String name : entry.getValue()) {
                if (present.contains(name)) {
                    mongo.indexOps(entry.getKey()).dropIndex(name);
                    log.info("Dropped legacy index {}.{}", entry.getKey(), name);
                    dropped++;
                }
            }
        }
        return dropped;
    }
}
