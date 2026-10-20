package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;

class CouponScopeIntegrationTest extends IntegrationTest {

    private ResultActions createCoupon(String admin, String code, double amount, Object... extra) throws Exception {
        Map<String, Object> m = new HashMap<>(Map.of("coupon", code, "amount", amount));
        for (int i = 0; i < extra.length; i += 2) {
            m.put((String) extra[i], extra[i + 1]);
        }
        return mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(m)));
    }

    private ResultActions checkout(String token, String coupon, String... productIds) throws Exception {
        List<Map<String, Object>> items = java.util.Arrays.stream(productIds)
                .map(id -> Map.<String, Object>of("productId", id, "quantity", 1)).toList();
        Map<String, Object> req = new HashMap<>();
        req.put("items", items);
        req.put("coupon", coupon);
        return mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)));
    }

    private ResultActions quote(String token, String coupon, String... productIds) throws Exception {
        List<Map<String, Object>> items = java.util.Arrays.stream(productIds)
                .map(id -> Map.<String, Object>of("productId", id, "quantity", 1)).toList();
        Map<String, Object> req = new HashMap<>();
        req.put("items", items);
        req.put("coupon", coupon);
        var builder = post("/api/v1/order/quote").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req));
        return mvc.perform(token == null ? builder : builder.header("Authorization", bearer(token)));
    }

    @Test
    void scopeFieldsRoundTripThroughTheAdminApiAndAreAudited() throws Exception {
        String admin = adminToken();
        String id = body(createCoupon(admin, "SCOPED", 10, "type", "PERCENT",
                "productIds", List.of("abc123"), "categories", List.of("Clothing "), "firstOrderOnly", true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.productIds[0]").value("abc123"))
                .andExpect(jsonPath("$.coupon.categories[0]").value("clothing"))
                .andExpect(jsonPath("$.coupon.firstOrderOnly").value(true)).andReturn()).get("coupon").get("_id")
                .asText();
        assertThat(changeOf(auditEntries("COUPON_CREATED").get(0), "categories").get("after").toString())
                .contains("clothing");

        // Null keeps the scope; an empty list clears it.
        mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":15}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.categories[0]").value("clothing"))
                .andExpect(jsonPath("$.coupon.firstOrderOnly").value(true));
        mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productIds\":[],\"categories\":[],\"firstOrderOnly\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.coupon.productIds").isEmpty())
                .andExpect(jsonPath("$.coupon.categories").isEmpty())
                .andExpect(jsonPath("$.coupon.firstOrderOnly").value(false));
    }

    @Test
    void aProductScopedCouponDiscountsOnlyThatProductAtCheckout() throws Exception {
        String admin = adminToken();
        String shirt = createProduct(admin, "Shirt", 800, 5, "clothing");
        String mug = createProduct(admin, "Mug", 200, 5, "home");
        createCoupon(admin, "MUGS", 50, "type", "PERCENT", "productIds", List.of(mug)).andExpect(status().isCreated());
        String user = register("scope1@test.dev");

        JsonNode both = body(checkout(user, "MUGS", shirt, mug).andExpect(status().isCreated()).andReturn())
                .get("checkout");
        assertThat(both.get("discount").asDouble()).isEqualTo(100.0);
        checkout(user, "MUGS", shirt).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon doesn't apply to the items in your cart"));
    }

    @Test
    void aCategoryScopedCouponMatchesProductsInThatCategory() throws Exception {
        String admin = adminToken();
        String shirt = createProduct(admin, "Shirt", 800, 5, "Clothing");
        String mug = createProduct(admin, "Mug", 200, 5, "home");
        createCoupon(admin, "CLOTHES", 25, "type", "PERCENT", "categories", List.of("clothing"))
                .andExpect(status().isCreated());
        String user = register("scope2@test.dev");

        JsonNode both = body(checkout(user, "CLOTHES", shirt, mug).andExpect(status().isCreated()).andReturn())
                .get("checkout");
        assertThat(both.get("discount").asDouble()).isEqualTo(200.0);
    }

    @Test
    void aFirstOrderOnlyCouponStopsWorkingOnceTheCustomerHasOrdered() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Lamp", 500, 5, "home");
        createCoupon(admin, "WELCOME", 100, "firstOrderOnly", true).andExpect(status().isCreated());
        String user = register("first@test.dev");

        checkout(user, "WELCOME", product).andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkout.discount").value(100.0));
        placeOrder(user, product);

        checkout(user, "WELCOME", product).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon is only for your first order"));
        String newcomer = register("newcomer@test.dev");
        checkout(newcomer, "WELCOME", product).andExpect(status().isCreated());
    }

    @Test
    void theQuoteEndpointPricesTheCartLikeCheckoutAndWorksWithoutLogin() throws Exception {
        String admin = adminToken();
        String shirt = createProduct(admin, "Shirt", 800, 5, "clothing");
        String mug = createProduct(admin, "Mug", 200, 5, "home");
        createCoupon(admin, "MUGS", 50, "type", "PERCENT", "productIds", List.of(mug)).andExpect(status().isCreated());
        createCoupon(admin, "WELCOME", 100, "firstOrderOnly", true).andExpect(status().isCreated());

        quote(null, "MUGS", shirt, mug).andExpect(status().isOk())
                .andExpect(jsonPath("$.quote.subtotal").value(1000.0))
                .andExpect(jsonPath("$.quote.discount").value(100.0))
                .andExpect(jsonPath("$.quote.total").value(1280.0)); // 1000 + 180 tax + 200 shipping - 100
        quote(null, null, shirt).andExpect(status().isOk()).andExpect(jsonPath("$.quote.discount").value(0.0));
        quote(null, "NOPE", shirt).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid coupon"));

        String user = register("quote@test.dev");
        placeOrder(user, mug);
        quote(user, "WELCOME", shirt).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon is only for your first order"));
        // Anonymous callers can't be checked for a previous order; checkout is where it is enforced.
        quote(null, "WELCOME", shirt).andExpect(status().isOk());
    }

    @Test
    void theOldSubtotalPreviewRefusesScopedCouponsInsteadOfOverDiscounting() throws Exception {
        String admin = adminToken();
        createCoupon(admin, "MUGS", 50, "type", "PERCENT", "categories", List.of("home"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "MUGS").param("subtotal", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon only applies to selected items"));
    }
}
