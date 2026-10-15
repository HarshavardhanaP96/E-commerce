package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CheckoutIntegrationTest extends IntegrationTest {

    private static final Map<String, String> SHIPPING = Map.of("address", "1 MG Road", "city", "Bengaluru",
            "state", "KA", "country", "India", "pinCode", "560001");

    @Test
    void serverPricesCartAppliesCouponAndPlacesOrder() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Shoe", 1200, 2, "footwear");
        mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"coupon\":\"SAVE100\",\"amount\":100}"))
                .andExpect(status().isCreated());
        String user = register("buyer@test.dev");

        JsonNode checkout = checkout(user, product, 1, "SAVE100");
        assertThat(checkout.get("total").asDouble()).isEqualTo(1316.0);
        assertThat(checkout.get("amount").asLong()).isEqualTo(131_600);
        assertThat(checkout.get("provider").asText()).isEqualTo("fake");

        String orderId = body(confirm(user, checkout.get("providerOrderId").asText(), "fake")
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();

        mvc.perform(get("/api/v1/product/" + product)).andExpect(jsonPath("$.product.stock").value(1));
        mvc.perform(get("/api/v1/order/my").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].total").value(1316.0))
                .andExpect(jsonPath("$.orders[0].status").value("Processing"))
                .andExpect(jsonPath("$.orders[0].user.name").value("Test User"));

        // Replaying the same payment must not create a second order.
        confirm(user, checkout.get("providerOrderId").asText(), "fake").andExpect(status().isConflict());

        // Other customers cannot see the order; admins can, and can advance it.
        String other = register("other@test.dev");
        mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("Shipped"));
        mvc.perform(get("/api/v1/order/all").param("status", "shipped").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.orders.length()").value(1));
    }

    @Test
    void badSignatureAndOtherUsersIntentAreRejected() throws Exception {
        String product = createProduct(adminToken(), "Mug", 300, 5, "home");
        String user = register("sig@test.dev");
        String providerOrderId = checkout(user, product, 1, null).get("providerOrderId").asText();
        confirm(user, providerOrderId, "forged").andExpect(status().isBadRequest());
        confirm(register("thief@test.dev"), providerOrderId, "fake").andExpect(status().isNotFound());
    }

    @Test
    void stockIsNeverOversold() throws Exception {
        String product = createProduct(adminToken(), "Last One", 500, 1, "rare");
        String a = register("a@test.dev");
        String b = register("b@test.dev");
        String intentA = checkout(a, product, 1, null).get("providerOrderId").asText();
        String intentB = checkout(b, product, 1, null).get("providerOrderId").asText();

        confirm(a, intentA, "fake").andExpect(status().isCreated());
        confirm(b, intentB, "fake").andExpect(status().isConflict());
        mvc.perform(get("/api/v1/product/" + product)).andExpect(jsonPath("$.product.stock").value(0));

        // Asking for more than is in stock fails at checkout already.
        mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items",
                                List.of(Map.of("productId", product, "quantity", 1))))))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidCouponIsRejected() throws Exception {
        String product = createProduct(adminToken(), "Pen", 50, 5, "office");
        String user = register("coupon@test.dev");
        mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("coupon", "NOPE",
                                "items", List.of(Map.of("productId", product, "quantity", 1))))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payment/discount").param("coupon[$ne]", "x")).andExpect(status().isBadRequest());
    }

    private JsonNode checkout(String token, String productId, int qty, String coupon) throws Exception {
        Map<String, Object> req = new java.util.HashMap<>();
        req.put("items", List.of(Map.of("productId", productId, "quantity", qty)));
        if (coupon != null) {
            req.put("coupon", coupon);
        }
        return body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("checkout");
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String providerOrderId,
                                                                       String signature) throws Exception {
        return mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("providerOrderId", providerOrderId,
                        "paymentId", "fake_pay_1", "signature", signature, "shippingInfo", SHIPPING))));
    }
}
