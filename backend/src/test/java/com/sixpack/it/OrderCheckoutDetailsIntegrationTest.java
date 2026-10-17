package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class OrderCheckoutDetailsIntegrationTest extends IntegrationTest {

    static Map<String, Object> shipping() {
        Map<String, Object> s = new HashMap<>();
        s.put("address", "1 MG Road");
        s.put("city", "Bengaluru");
        s.put("state", "KA");
        s.put("country", "India");
        s.put("pinCode", "560001");
        s.put("name", "Asha Recipient");
        s.put("phone", "9123456780");
        return s;
    }

    private String productWith(String admin, String sku, String taxRate) throws Exception {
        var req = multipart("/api/v1/product/new")
                .file(new MockMultipartFile("photo", "p.png", "image/png", com.sixpack.storage.TestImages.png()))
                .param("name", "Shoe").param("price", "1200").param("stock", "5").param("category", "footwear")
                .param("sku", sku).param("taxRate", taxRate).header("Authorization", bearer(admin));
        return body(mvc.perform(req).andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
    }

    private JsonNode checkout(String token, String productId, String coupon) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        if (coupon != null) {
            req.put("coupon", coupon);
        }
        return body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("checkout");
    }

    private ResultActions confirm(String token, String providerOrderId, Map<String, Object> extra) throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("providerOrderId", providerOrderId,
                "paymentId", "fake_pay_1", "signature", "fake", "shippingInfo", shipping()));
        req.putAll(extra);
        return mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)));
    }

    @Test
    void confirmedOrderCarriesTheNewDetails() throws Exception {
        String admin = adminToken();
        String product = productWith(admin, "SH-1", "5");
        mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"coupon\":\"SAVE100\",\"amount\":100}"))
                .andExpect(status().isCreated());
        String user = register("buyer@test.dev");
        mvc.perform(put("/api/v1/user/me").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"+919876543210\"}"))
                .andExpect(status().isOk());

        JsonNode quote = checkout(user, product, "SAVE100");
        assertThat(quote.get("tax").asDouble()).isEqualTo(60.0); // 5% of 1200, not the default 18%
        assertThat(quote.get("total").asDouble()).isEqualTo(1160.0);

        Map<String, Object> billing = shipping();
        billing.put("city", "Mumbai");
        String orderId = body(confirm(user, quote.get("providerOrderId").asText(),
                Map.of("billingInfo", billing, "notes", "Leave at the door")).andExpect(status().isCreated())
                .andReturn()).get("orderId").asText();

        ResultActions order = mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)));
        order.andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderNumber").value(org.hamcrest.Matchers.matchesPattern(
                        "ORD-\\d{8}-[A-HJKMNP-Z2-9]{6}")))
                .andExpect(jsonPath("$.order.shippingInfo.name").value("Asha Recipient"))
                .andExpect(jsonPath("$.order.shippingInfo.phone").value("9123456780"))
                .andExpect(jsonPath("$.order.billingInfo.city").value("Mumbai"))
                .andExpect(jsonPath("$.order.customer.email").value("buyer@test.dev"))
                .andExpect(jsonPath("$.order.customer.phone").value("+919876543210"))
                .andExpect(jsonPath("$.order.customer.name").value("Test User"))
                .andExpect(jsonPath("$.order.couponCode").value("SAVE100"))
                .andExpect(jsonPath("$.order.notes").value("Leave at the door"))
                .andExpect(jsonPath("$.order.orderItems[0].sku").value("SH-1"))
                .andExpect(jsonPath("$.order.orderItems[0].taxRate").value(5.0))
                .andExpect(jsonPath("$.order.tax").value(60.0))
                .andExpect(jsonPath("$.order.total").value(1160.0))
                .andExpect(jsonPath("$.order.payment.status").value("PAID"))
                .andExpect(jsonPath("$.order.payment.amount").value(1160.0))
                .andExpect(jsonPath("$.order.payment.currency").value("INR"))
                .andExpect(jsonPath("$.order.payment.paidAt").exists())
                .andExpect(jsonPath("$.order.payment.paymentId").doesNotExist())
                .andExpect(jsonPath("$.order.statusHistory.length()").value(1))
                .andExpect(jsonPath("$.order.statusHistory[0].status").value("Processing"));
    }

    @Test
    void billingDefaultsToNothingAndOrderNumbersDiffer() throws Exception {
        String admin = adminToken();
        String product = productWith(admin, "SH-2", "18");
        String user = register("two@test.dev");

        String first = body(confirm(user, checkout(user, product, null).get("providerOrderId").asText(), Map.of())
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
        String second = body(confirm(user, checkout(user, product, null).get("providerOrderId").asText(), Map.of())
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();

        JsonNode a = body(mvc.perform(get("/api/v1/order/" + first).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.order.billingInfo").doesNotExist()).andReturn()).get("order");
        JsonNode b = body(mvc.perform(get("/api/v1/order/" + second).header("Authorization", bearer(user)))
                .andReturn()).get("order");
        assertThat(a.get("orderNumber").asText()).isNotEqualTo(b.get("orderNumber").asText());
    }

    @Test
    void invalidInputIsRejectedWithoutConsumingThePayment() throws Exception {
        String product = productWith(adminToken(), "SH-3", "18");
        String user = register("retry@test.dev");
        String intent = checkout(user, product, null).get("providerOrderId").asText();

        Map<String, Object> badPhone = shipping();
        badPhone.put("phone", "call me");
        confirm(user, intent, Map.of("shippingInfo", badPhone)).andExpect(status().isBadRequest());
        confirm(user, intent, Map.of("notes", "x".repeat(501))).andExpect(status().isBadRequest());
        Map<String, Object> badBilling = shipping();
        badBilling.put("pinCode", "12");
        confirm(user, intent, Map.of("billingInfo", badBilling)).andExpect(status().isBadRequest());

        // The same payment intent still works once the input is fixed.
        confirm(user, intent, Map.of()).andExpect(status().isCreated());
    }
}
