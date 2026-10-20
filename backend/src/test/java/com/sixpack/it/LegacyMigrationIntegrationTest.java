package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.tenant.application.LegacyIndexCleanup;
import com.sixpack.tenant.application.TenantBackfill;
import com.sixpack.tenant.domain.Tenant;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.http.MediaType;

/**
 * A rehearsal of the production migration: a database as the single-tenant app left it (no tenant on any document,
 * global unique indexes) is upgraded in place, and the store keeps working for its owner and customers.
 */
class LegacyMigrationIntegrationTest extends IntegrationTest {

    @Autowired
    LegacyIndexCleanup cleanup;

    @Autowired
    TenantBackfill backfill;

    private static final List<String> COLLECTIONS = List.of("users", "products", "categories", "coupons",
            "coupon_redemptions", "orders", "payment_intents", "password_reset_tokens", "consents", "audit_log");

    private void legacyIndex(String collection, String field, boolean sparse) {
        Index index = new Index().on(field, Sort.Direction.ASC).unique();
        mongo.indexOps(collection).ensureIndex(sparse ? index.sparse() : index);
    }

    private void insert(String collection, Document doc) {
        mongo.getCollection(collection).insertOne(doc);
    }

    @Test
    void anExistingDatabaseIsUpgradedInPlaceAndKeepsWorking() throws Exception {
        ObjectId productId = new ObjectId();
        insert("users", new Document("name", "Legacy").append("email", "legacy@test.dev")
                .append("password", "$2b$10$Tn6i.ynqKLyP3kXR5Jzje.b6IApynXF8TdZgKoKbCRtwOKgQXdZnC")
                .append("photo", "").append("role", "user").append("gender", "male")
                .append("dob", Date.from(Instant.parse("1996-01-01T00:00:00Z"))));
        insert("products", new Document("_id", productId).append("name", "Legacy Lamp").append("photo", "uploads/o.png")
                .append("price", 100.0).append("stock", 3).append("category", "misc").append("slug", "legacy-lamp")
                .append("createdAt", new Date()));
        insert("categories", new Document("name", "Legacy").append("slug", "legacy"));
        insert("coupons", new Document("coupon", "LEGACY10").append("amount", 10.0));
        insert("coupon_redemptions", new Document("couponId", "c1").append("userId", "u1").append("count", 1));
        insert("orders", new Document("user", "u1").append("status", "Processing").append("subtotal", 100.0)
                .append("tax", 18.0).append("shippingCharges", 200.0).append("discount", 0.0).append("total", 318.0)
                .append("orderNumber", "ORD-LEGACY1").append("orderItems", List.of())
                .append("shippingInfo", new Document("city", "Pune").append("state", "MH").append("country", "India"))
                .append("createdAt", new Date()));
        insert("payment_intents", new Document("userId", "u1").append("provider", "fake")
                .append("providerOrderId", "fake_order_legacy").append("status", "CREATED").append("items", List.of())
                .append("total", 118.0).append("createdAt", new Date()));
        insert("password_reset_tokens", new Document("userId", "u1").append("tokenHash", "h1")
                .append("expiresAt", Date.from(Instant.now().plusSeconds(600))));
        insert("consents", new Document("userId", "u1").append("purpose", "terms").append("granted", true)
                .append("policyVersion", "2026-10-16").append("source", "registration").append("createdAt", new Date()));
        insert("audit_log", new Document("at", new Date()).append("actorRole", "SYSTEM")
                .append("action", "USER_REGISTERED").append("outcome", "SUCCESS"));

        // The indexes the single-tenant app created.
        legacyIndex("users", "email", false);
        legacyIndex("products", "slug", true);
        legacyIndex("categories", "slug", false);
        legacyIndex("coupons", "coupon", false);
        legacyIndex("orders", "orderNumber", true);
        legacyIndex("payment_intents", "providerOrderId", false);
        legacyIndex("password_reset_tokens", "tokenHash", false);
        mongo.indexOps("coupon_redemptions").ensureIndex(new Index().on("couponId", Sort.Direction.ASC)
                .on("userId", Sort.Direction.ASC).unique().named("coupon_user"));

        // The upgrade.
        assertThat(cleanup.cleanup()).isEqualTo(8);
        assertThat(backfill.backfill()).isEqualTo(10);
        assertThat(cleanup.cleanup()).isZero();
        assertThat(backfill.backfill()).isZero();

        // Everything now belongs to the default tenant.
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        for (String collection : COLLECTIONS) {
            assertThat(mongo.getCollection(collection).countDocuments(new Document("tenantId", def.id())))
                    .as(collection).isEqualTo(1);
            assertThat(mongo.getCollection(collection).countDocuments(new Document("tenantId",
                    new Document("$exists", false)))).as(collection + " left behind").isZero();
        }

        // And the store works for the people who were already using it.
        mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"legacy@test.dev\",\"password\":\"legacy-pass\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/product/all")).andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].name").value("Legacy Lamp"));
        mvc.perform(get("/api/v1/product/" + productId.toHexString())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "LEGACY10")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/category")).andExpect(jsonPath("$.categories.length()").value(1));
        mvc.perform(get("/api/v1/order/all").header("Authorization", bearer(adminToken())))
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNumber").value("ORD-LEGACY1"));
    }
}
