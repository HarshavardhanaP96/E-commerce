package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CouponCheckoutIntegrationTest extends IntegrationTest {

    private void createCoupon(String admin, Map<String, Object> body) throws Exception {
        mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private static Map<String, Object> coupon(String code, double amount, Object... extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("coupon", code);
        m.put("amount", amount);
        for (int i = 0; i < extra.length; i += 2) {
            m.put((String) extra[i], extra[i + 1]);
        }
        return m;
    }

    private ResultActions checkout(String token, String productId, String coupon) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        req.put("coupon", coupon);
        return mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)));
    }

    private String placeOrder(String token, String productId, String coupon) throws Exception {
        String intent = body(checkout(token, productId, coupon).andExpect(status().isCreated()).andReturn())
                .get("checkout").get("providerOrderId").asText();
        Map<String, Object> req = new HashMap<>(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping()));
        return body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    private JsonNode couponRow(String admin, String code) throws Exception {
        JsonNode all = body(mvc.perform(get("/api/v1/payment/coupon/all").header("Authorization", bearer(admin)))
                .andReturn()).get("coupons");
        for (JsonNode c : all) {
            if (code.equals(c.get("coupon").asText())) {
                return c;
            }
        }
        throw new AssertionError("coupon not listed: " + code);
    }

    @Test
    void percentCouponIsCappedAndAppliedToTheRealSubtotal() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Shoe", 1200, 5, "footwear");
        createCoupon(admin, coupon("TEN", 10, "type", "PERCENT", "maxDiscount", 50));
        createCoupon(admin, coupon("TENUNCAPPED", 10, "type", "PERCENT"));
        String user = register("pct@test.dev");

        JsonNode capped = body(checkout(user, product, "TEN").andExpect(status().isCreated()).andReturn())
                .get("checkout");
        assertThat(capped.get("discount").asDouble()).isEqualTo(50.0);
        assertThat(capped.get("total").asDouble()).isEqualTo(1366.0); // 1200 + 216 tax - 50

        JsonNode full = body(checkout(user, product, "TENUNCAPPED").andExpect(status().isCreated()).andReturn())
                .get("checkout");
        assertThat(full.get("discount").asDouble()).isEqualTo(120.0);
        assertThat(full.get("total").asDouble()).isEqualTo(1296.0);
    }

    @Test
    void rulesAreEnforcedAtCheckoutWithReadableReasons() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Mug", 300, 5, "home");
        String user = register("rules@test.dev");
        createCoupon(admin, coupon("BIGONLY", 50, "minOrderValue", 1000));
        checkout(user, product, "BIGONLY").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Minimum order value for this coupon is ₹1000"));

        // Seed an expired and a not-yet-valid coupon directly: the API refuses to create an expired one.
        mongo.getCollection("coupons").insertOne(new Document("coupon", "OLDONE").append("amount", 10.0)
                .append("type", "FLAT").append("active", true)
                .append("expiresAt", Date.from(Instant.now().minusSeconds(3600))));
        mongo.getCollection("coupons").insertOne(new Document("coupon", "SOON").append("amount", 10.0)
                .append("type", "FLAT").append("active", true)
                .append("startsAt", Date.from(Instant.now().plusSeconds(3600))));
        checkout(user, product, "OLDONE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon has expired"));
        checkout(user, product, "SOON").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon is not valid yet"));

        createCoupon(admin, coupon("PAUSED", 10));
        String id = couponRow(admin, "PAUSED").get("_id").asText();
        mongo.getCollection("coupons").updateOne(new Document("_id", new ObjectId(id)),
                new Document("$set", new Document("active", false)));
        checkout(user, product, "PAUSED").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon is not active"));
    }

    @Test
    void oldStyleCouponsKeepWorking() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Pen", 500, 5, "office");
        mongo.getCollection("coupons").insertOne(new Document("coupon", "LEGACY").append("amount", 100.0));
        String user = register("legacy@test.dev");

        JsonNode quote = body(checkout(user, product, "LEGACY").andExpect(status().isCreated()).andReturn())
                .get("checkout");
        assertThat(quote.get("discount").asDouble()).isEqualTo(100.0);
        placeOrder(user, product, "LEGACY");
        assertThat(couponRow(admin, "LEGACY").get("usedCount").asInt()).isEqualTo(1);
    }

    @Test
    void totalUsageLimitStopsNewCheckoutsOnceReached() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Lamp", 500, 10, "home");
        createCoupon(admin, coupon("ONCE", 50, "usageLimit", 1));
        String alice = register("alice@test.dev");
        String bob = register("bob@test.dev");

        placeOrder(alice, product, "ONCE");

        JsonNode row = couponRow(admin, "ONCE");
        assertThat(row.get("usedCount").asInt()).isEqualTo(1);
        assertThat(row.get("status").asText()).isEqualTo("exhausted");
        checkout(bob, product, "ONCE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon's usage limit has been reached"));
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "ONCE").param("subtotal", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void perCustomerLimitIsPerCustomer() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Bag", 500, 10, "fashion");
        createCoupon(admin, coupon("ONEEACH", 50, "perUserLimit", 1));
        String alice = register("alice2@test.dev");
        String bob = register("bob2@test.dev");

        placeOrder(alice, product, "ONEEACH");

        checkout(alice, product, "ONEEACH").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You have already used this coupon"));
        // The preview knows who is asking when a token is sent, and stays generous to anonymous callers.
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "ONEEACH").param("subtotal", "500")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "ONEEACH").param("subtotal", "500"))
                .andExpect(status().isOk());
        placeOrder(bob, product, "ONEEACH");
        assertThat(couponRow(admin, "ONEEACH").get("usedCount").asInt()).isEqualTo(2);
    }

    @Test
    void reactivatingACouponWorksAgain() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Cap", 500, 10, "fashion");
        createCoupon(admin, coupon("TOGGLE", 50));
        String id = couponRow(admin, "TOGGLE").get("_id").asText();
        String user = register("toggle@test.dev");

        mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
        checkout(user, product, "TOGGLE").andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")).andExpect(status().isOk());
        checkout(user, product, "TOGGLE").andExpect(status().isCreated());
    }

    @Test
    void deletingAUserRemovesTheirCounterButKeepsTheCouponTotal() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Vase", 500, 10, "home");
        createCoupon(admin, coupon("KEEPTOTAL", 50, "perUserLimit", 5));
        String user = register("erasecoupon@test.dev");
        String orderId = placeOrder(user, product, "KEEPTOTAL");
        String couponId = couponRow(admin, "KEEPTOTAL").get("_id").asText();
        assertThat(mongo.getCollection("coupon_redemptions").countDocuments(new Document("couponId", couponId)))
                .isEqualTo(1);

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/user/me").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        assertThat(mongo.getCollection("coupon_redemptions").countDocuments(new Document("couponId", couponId)))
                .isZero();
        assertThat(couponRow(admin, "KEEPTOTAL").get("usedCount").asInt()).isEqualTo(1);
    }
}
