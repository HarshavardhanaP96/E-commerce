package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class IndianStateCheckoutIntegrationTest extends IntegrationTest {

    private String user;
    private String product;

    @BeforeEach
    void shop() throws Exception {
        product = createProduct(adminToken(), "Shoe", 500, 10, "footwear");
        user = register("buyer@test.dev");
    }

    @Test
    void theListIsPublic() throws Exception {
        mvc.perform(get("/api/v1/reference/indian-states"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.states.length()").value(36))
                .andExpect(jsonPath("$.states[0].code").value("AN"))
                .andExpect(jsonPath("$.states[0].name").value("Andaman and Nicobar Islands"))
                .andExpect(jsonPath("$.states[0].unionTerritory").value(true))
                .andExpect(jsonPath("$.states[?(@.code == 'OD')].aliases[*]", hasItems("OR", "Orissa")));
    }

    @Test
    void anIndianStateIsStoredUnderItsOfficialName() throws Exception {
        String orderId = body(confirm(address("KA", "India"), null).andExpect(status().isCreated()).andReturn())
                .get("orderId").asText();
        Document stored = mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
        assertThat(stored.get("shippingInfo", Document.class).getString("state")).isEqualTo("Karnataka");
    }

    @Test
    void anUnknownIndianStateIsRejected() throws Exception {
        confirm(address("Bangalore", "India"), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("shippingInfo.state: not an Indian state or union territory"));
        confirm(address("KA", "India"), address("Atlantis", "India")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("billingInfo.state: not an Indian state or union territory"));
    }

    @Test
    void otherCountriesKeepWhatWasTyped() throws Exception {
        String orderId = body(confirm(address("Bagmati", "Nepal"), null).andExpect(status().isCreated()).andReturn())
                .get("orderId").asText();
        Document stored = mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
        assertThat(stored.get("shippingInfo", Document.class).getString("state")).isEqualTo("Bagmati");
    }

    private static Map<String, Object> address(String state, String country) {
        return Map.of("address", "1 MG Road", "city", "Bengaluru", "state", state, "country", country,
                "pinCode", "560001");
    }

    private ResultActions confirm(Map<String, Object> shipping, Map<String, Object> billing) throws Exception {
        String providerOrderId = body(mvc.perform(post("/api/v1/order/checkout")
                        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items",
                                List.of(Map.of("productId", product, "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        Map<String, Object> request = new HashMap<>(Map.of("providerOrderId", providerOrderId,
                "paymentId", "fake_pay_1", "signature", "fake", "shippingInfo", shipping));
        if (billing != null) {
            request.put("billingInfo", billing);
        }
        return mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }
}
