package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Coupons, redemptions, payment intents and orders never cross tenants. */
class TenantCommerceIsolationIntegrationTest extends TenantIntegrationTest {

    Tenant acme;
    Tenant globex;
    String adminA;
    String adminB;

    @BeforeEach
    void twoTenants() throws Exception {
        acme = withFakePayments(createTenant("acme", "Acme", "shop.acme.test"));
        globex = withFakePayments(createTenant("globex", "Globex", "shop.globex.test"));
        adminA = adminTokenFor(acme);
        adminB = adminTokenFor(globex);
    }

    private ResultActions createCoupon(Tenant t, String admin, String code, Object... extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("coupon", code, "amount", 50));
        for (int i = 0; i < extra.length; i += 2) {
            body.put((String) extra[i], extra[i + 1]);
        }
        return mvc.perform(post("/api/v1/payment/coupon/new").with(host(hostOf(t)))
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private String createProductOn(Tenant t, String admin, String name) throws Exception {
        var req = multipart("/api/v1/product/new")
                .file(new MockMultipartFile("photo", "p.png", "image/png", com.sixpack.storage.TestImages.png()));
        req.with(host(hostOf(t)));
        req.param("name", name).param("price", "500").param("stock", "10").param("category", "misc")
                .header("Authorization", bearer(admin));
        return body(mvc.perform(req).andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
    }

    private ResultActions checkoutOn(Tenant t, String token, String productId, String coupon) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        if (coupon != null) {
            req.put("coupon", coupon);
        }
        return mvc.perform(post("/api/v1/order/checkout").with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)));
    }

    private ResultActions confirmOn(Tenant t, String token, String intent) throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping()));
        return mvc.perform(post("/api/v1/order/new").with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)));
    }

    private String placeOrderOn(Tenant t, String token, String productId, String coupon) throws Exception {
        String intent = body(checkoutOn(t, token, productId, coupon).andExpect(status().isCreated()).andReturn())
                .get("checkout").get("providerOrderId").asText();
        return body(confirmOn(t, token, intent).andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    @Test
    void theSameCouponCodeCanExistInBothTenants() throws Exception {
        createCoupon(acme, adminA, "SAVE10").andExpect(status().isCreated());
        createCoupon(globex, adminB, "SAVE10").andExpect(status().isCreated());
        createCoupon(acme, adminA, "SAVE10").andExpect(status().isConflict());

        mvc.perform(get("/api/v1/payment/coupon/all").with(host(hostOf(acme))).header("Authorization", bearer(adminA)))
                .andExpect(jsonPath("$.coupons.length()").value(1));
    }

    @Test
    void aCouponIsOnlyValidInItsOwnTenant() throws Exception {
        createCoupon(acme, adminA, "ONLYACME").andExpect(status().isCreated());

        mvc.perform(get("/api/v1/payment/discount").param("coupon", "ONLYACME").with(host(hostOf(acme))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "ONLYACME").with(host(hostOf(globex))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payment/coupon/all").with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(jsonPath("$.coupons.length()").value(0));
    }

    @Test
    void usageLimitsAreCountedPerTenant() throws Exception {
        createCoupon(acme, adminA, "ONCE", "usageLimit", 1).andExpect(status().isCreated());
        createCoupon(globex, adminB, "ONCE", "usageLimit", 1).andExpect(status().isCreated());
        String productA = createProductOn(acme, adminA, "Lamp");
        String productB = createProductOn(globex, adminB, "Lamp");
        String buyerA = registerOn(acme, "buyer.a@test.dev");
        String buyerB = registerOn(globex, "buyer.b@test.dev");

        placeOrderOn(acme, buyerA, productA, "ONCE");
        placeOrderOn(globex, buyerB, productB, "ONCE"); // tenant B's own counter is still at zero

        checkoutOn(acme, buyerA, productA, "ONCE").andExpect(status().isBadRequest());
        checkoutOn(globex, buyerB, productB, "ONCE").andExpect(status().isBadRequest());
    }

    @Test
    void ordersAreInvisibleToTheOtherTenant() throws Exception {
        String product = createProductOn(acme, adminA, "Lamp");
        String buyer = registerOn(acme, "buyer@test.dev");
        String orderId = placeOrderOn(acme, buyer, product, null);

        mvc.perform(get("/api/v1/order/all").with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(jsonPath("$.orders.length()").value(0));
        mvc.perform(get("/api/v1/order/" + orderId).with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/order/" + orderId).with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/order/all").with(host(hostOf(acme))).header("Authorization", bearer(adminA)))
                .andExpect(jsonPath("$.orders.length()").value(1));
    }

    @Test
    void aPaymentIntentOfOneTenantCannotBeConfirmedInAnother() throws Exception {
        String product = createProductOn(acme, adminA, "Lamp");
        String buyer = registerOn(acme, "buyer@test.dev");
        String intent = body(checkoutOn(acme, buyer, product, null).andExpect(status().isCreated()).andReturn())
                .get("checkout").get("providerOrderId").asText();
        String sameUserOnB = registerOn(globex, "buyer@test.dev"); // same email, a different account

        confirmOn(globex, sameUserOnB, intent).andExpect(status().isNotFound());
        confirmOn(acme, buyer, intent).andExpect(status().isCreated());
    }

    @Test
    void aProductFromAnotherTenantCannotBeBought() throws Exception {
        String productA = createProductOn(acme, adminA, "Lamp");
        String buyerB = registerOn(globex, "buyer.b@test.dev");

        checkoutOn(globex, buyerB, productA, null).andExpect(status().isBadRequest());
    }

    @Test
    void uniqueIndexesAreScopedToTheTenantInTheDatabase() {
        mongo.getCollection("orders").insertOne(new Document("orderNumber", "ORD-1").append("tenantId", "t1"));
        mongo.getCollection("orders").insertOne(new Document("orderNumber", "ORD-1").append("tenantId", "t2"));
        mongo.getCollection("orders").insertOne(new Document("user", "u").append("tenantId", "t1")); // no number
        mongo.getCollection("orders").insertOne(new Document("user", "v").append("tenantId", "t1"));
        mongo.getCollection("payment_intents").insertOne(new Document("providerOrderId", "po-1").append("tenantId", "t1"));
        mongo.getCollection("payment_intents").insertOne(new Document("providerOrderId", "po-1").append("tenantId", "t2"));
        mongo.getCollection("coupons").insertOne(new Document("coupon", "X").append("tenantId", "t1"));
        mongo.getCollection("coupons").insertOne(new Document("coupon", "X").append("tenantId", "t2"));
        mongo.getCollection("coupon_redemptions").insertOne(
                new Document("couponId", "c").append("userId", "u").append("tenantId", "t1"));
        mongo.getCollection("coupon_redemptions").insertOne(
                new Document("couponId", "c").append("userId", "u").append("tenantId", "t2"));

        for (String[] dup : new String[][] {{"orders", "orderNumber", "ORD-1"}, {"payment_intents", "providerOrderId", "po-1"},
                {"coupons", "coupon", "X"}}) {
            assertThatThrownBy(() -> mongo.getCollection(dup[0]).insertOne(
                    new Document(dup[1], dup[2]).append("tenantId", "t1")))
                    .as(dup[0]).isInstanceOf(com.mongodb.MongoWriteException.class);
        }
        assertThatThrownBy(() -> mongo.getCollection("coupon_redemptions").insertOne(
                new Document("couponId", "c").append("userId", "u").append("tenantId", "t1")))
                .isInstanceOf(com.mongodb.MongoWriteException.class);
        assertThat(mongo.getCollection("orders").countDocuments()).isEqualTo(4);
    }
}
