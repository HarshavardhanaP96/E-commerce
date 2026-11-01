package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.order.PaidOrders;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** POST /order/checkout with an attribution snapshot (marketing spec §5.5). */
class CheckoutAttributionIntegrationTest extends IntegrationTest {

    @Autowired
    PaidOrders paidOrders;

    private String orderWith(String token, String product, Map<String, Object> attribution) throws Exception {
        Map<String, Object> checkout = new HashMap<>(Map.of("items", List.of(Map.of("productId", product, "quantity", 1))));
        checkout.put("attribution", attribution);
        String intent = body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                        .header("User-Agent", "Mozilla/5.0 test").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(checkout)))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        return body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping()))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    private Document stored(String orderId) {
        return mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
    }

    static Map<String, Object> attribution(boolean analytics, boolean marketing) {
        Map<String, Object> a = new HashMap<>(Map.of("utmSource", "google", "utmCampaign", "diwali",
                "gclid", "Cj0KCQ-abc", "fbp", "fb.1.1730000000000.1098115397", "gaClientId", "1234567890.1730000000",
                "landingPath", "/product/lamp"));
        a.put("consent", Map.of("analytics", analytics, "marketing", marketing, "version", "2026-10-31"));
        return a;
    }

    @Test
    void theAttributionTravelsFromCheckoutToTheOrder() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        String orderId = orderWith(register("a@test.dev"), product, attribution(true, true));

        Document a = stored(orderId).get("attribution", Document.class);
        assertThat(a.getString("utmSource")).isEqualTo("google");
        assertThat(a.getString("gclid")).isEqualTo("Cj0KCQ-abc");
        assertThat(a.getString("gaClientId")).isEqualTo("1234567890.1730000000");
        assertThat(a.getString("userAgent")).isEqualTo("Mozilla/5.0 test");
        assertThat(a.getString("sourceUrl")).isEqualTo("https://localhost/shipping");
        Document consent = a.get("consent", Document.class);
        assertThat(consent.getBoolean("marketing")).isTrue();
        assertThat(consent.getString("version")).isEqualTo("2026-10-31");
        assertThat(consent.getDate("at")).isNotNull();
        // Never shown on the order itself.
        String view = mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(view).doesNotContain("attribution", "gclid");
    }

    @Test
    void withoutAttributionCheckoutIsUnchanged() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        String orderId = placeOrder(register("b@test.dev"), product);
        assertThat(stored(orderId)).doesNotContainKey("attribution");
    }

    @Test
    void junkAttributionNeverFailsCheckout() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        Map<String, Object> junk = attribution(true, true);
        junk.put("utmSource", "x".repeat(1000));
        junk.put("gclid", "<script>alert(1)</script>");
        junk.put("landingPath", "https://evil.example");
        String orderId = orderWith(register("c@test.dev"), product, junk);

        Document a = stored(orderId).get("attribution", Document.class);
        assertThat(a.getString("utmSource")).hasSize(150);
        assertThat(a).doesNotContainKeys("gclid", "landingPath");
    }

    @Test
    void aDeclinedChoiceKeepsNoCampaignData() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        Document a = stored(orderWith(register("d@test.dev"), product, attribution(false, false)))
                .get("attribution", Document.class);
        assertThat(a).doesNotContainKeys("utmSource", "gclid", "fbp", "gaClientId", "landingPath", "userAgent");
        assertThat(a.get("consent", Document.class).getBoolean("marketing")).isFalse();
    }

    @Test
    void paidOrdersShowsAPaidOrderAndListsOnlyConsentedOnes() throws Exception {
        String product = createProduct(adminToken(), "Lamp", 500, 5, "home");
        String withConsent = orderWith(register("p@test.dev"), product, attribution(true, false));
        String declined = orderWith(register("q@test.dev"), product, attribution(false, false));
        String without = placeOrder(register("r@test.dev"), product);

        var paid = paidOrders.find(withConsent).orElseThrow();
        assertThat(paid.orderNumber()).startsWith("ORD-");
        assertThat(paid.total()).isEqualTo(790.0);
        assertThat(paid.tax()).isEqualTo(90.0);
        assertThat(paid.shipping()).isEqualTo(200.0);
        assertThat(paid.currency()).isEqualTo("INR");
        assertThat(paid.email()).isEqualTo("p@test.dev");
        assertThat(paid.lines()).singleElement().satisfies(l -> {
            assertThat(l.productId()).isEqualTo(product);
            assertThat(l.quantity()).isEqualTo(1);
        });
        assertThat(paid.attribution().consent().analytics()).isTrue();
        assertThat(paid.placedAt()).isNotNull();
        assertThat(paidOrders.find("6720f0c2a1b2c3d4e5f60718")).isEmpty();

        Instant now = Instant.now();
        assertThat(paidOrders.consentedBetween(now.minusSeconds(600), now.plusSeconds(60), 10))
                .containsExactly(withConsent).doesNotContain(declined, without);
        assertThat(paidOrders.consentedBetween(now.plusSeconds(60), now.plusSeconds(120), 10)).isEmpty();
    }
}
