package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class OrderErasureIntegrationTest extends IntegrationTest {

    @Test
    void deletingAnAccountStripsEveryPersonalFieldButKeepsTheTaxRecord() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Lamp", 500, 5, "home");
        String user = register("erase@test.dev");

        String intent = body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items",
                                List.of(Map.of("productId", product, "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        Map<String, Object> billing = OrderCheckoutDetailsIntegrationTest.shipping();
        billing.put("city", "Mumbai");
        Map<String, Object> req = new HashMap<>(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping(),
                "billingInfo", billing, "notes", "Gate code 4711"));
        String orderId = body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();

        // Deletion is blocked until the order is delivered.
        mvc.perform(delete("/api/v1/user/me").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin))).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/user/me").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        Document stored = mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
        assertThat(stored).isNotNull();
        Document shipping = stored.get("shippingInfo", Document.class);
        assertThat(shipping.get("city")).isEqualTo("Bengaluru");
        assertThat(shipping.get("state")).isEqualTo("KA");
        assertThat(shipping).doesNotContainKeys("address", "pinCode", "name", "phone");
        assertThat(stored).doesNotContainKeys("billingInfo", "customer", "notes");

        // The tax record survives.
        assertThat(stored.getString("orderNumber")).startsWith("ORD-");
        assertThat(stored.getDouble("total")).isEqualTo(790.0); // 500 + 90 tax + 200 shipping
        assertThat(stored.get("statusHistory", List.class)).hasSize(3);
        assertThat(stored.get("payment", Document.class).getString("status")).isEqualTo("PAID");
    }
}
