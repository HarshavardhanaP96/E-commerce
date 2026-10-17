package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CouponAdminIntegrationTest extends IntegrationTest {

    private ResultActions create(String admin, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions update(String admin, String id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> coupon(String code, double amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("coupon", code);
        m.put("amount", amount);
        return m;
    }

    private String id(ResultActions created) throws Exception {
        return body(created.andReturn()).get("coupon").get("_id").asText();
    }

    @Test
    void legacyBodyStillCreatesAnActiveFlatCoupon() throws Exception {
        String admin = adminToken();
        create(admin, coupon("SAVE100", 100)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.type").value("FLAT"))
                .andExpect(jsonPath("$.coupon.active").value(true))
                .andExpect(jsonPath("$.coupon.status").value("active"))
                .andExpect(jsonPath("$.coupon.usedCount").value(0));
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "SAVE100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discount").value(100.0));
    }

    @Test
    void createsAPercentCouponWithEveryRule() throws Exception {
        String admin = adminToken();
        Map<String, Object> m = coupon("FEST10", 10);
        m.put("type", "percent");
        m.put("maxDiscount", 150);
        m.put("minOrderValue", 500);
        m.put("startsAt", Instant.now().minusSeconds(60).toString());
        m.put("expiresAt", Instant.now().plusSeconds(86_400).toString());
        m.put("usageLimit", 100);
        m.put("perUserLimit", 2);

        create(admin, m).andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.type").value("PERCENT"))
                .andExpect(jsonPath("$.coupon.maxDiscount").value(150.0))
                .andExpect(jsonPath("$.coupon.minOrderValue").value(500.0))
                .andExpect(jsonPath("$.coupon.usageLimit").value(100))
                .andExpect(jsonPath("$.coupon.perUserLimit").value(2))
                .andExpect(jsonPath("$.coupon.status").value("active"));

        mvc.perform(get("/api/v1/payment/coupon/all").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.coupons.length()").value(1))
                .andExpect(jsonPath("$.coupons[0].coupon").value("FEST10"));
    }

    @Test
    void invalidRulesAreBadRequest() throws Exception {
        String admin = adminToken();
        Map<String, Object> over100 = coupon("BAD1", 150);
        over100.put("type", "PERCENT");
        create(admin, over100).andExpect(status().isBadRequest());

        Map<String, Object> flatWithCap = coupon("BAD2", 50);
        flatWithCap.put("maxDiscount", 20);
        create(admin, flatWithCap).andExpect(status().isBadRequest());

        Map<String, Object> reversed = coupon("BAD3", 50);
        reversed.put("startsAt", Instant.now().plusSeconds(7200).toString());
        reversed.put("expiresAt", Instant.now().plusSeconds(3600).toString());
        create(admin, reversed).andExpect(status().isBadRequest());

        Map<String, Object> alreadyExpired = coupon("BAD4", 50);
        alreadyExpired.put("expiresAt", Instant.now().minusSeconds(60).toString());
        create(admin, alreadyExpired).andExpect(status().isBadRequest());

        Map<String, Object> zeroLimit = coupon("BAD5", 50);
        zeroLimit.put("usageLimit", 0);
        create(admin, zeroLimit).andExpect(status().isBadRequest());

        Map<String, Object> badType = coupon("BAD6", 50);
        badType.put("type", "bogus");
        create(admin, badType).andExpect(status().isBadRequest());

        create(admin, coupon("DUP", 10)).andExpect(status().isCreated());
        create(admin, coupon("DUP", 10)).andExpect(status().isConflict());
    }

    @Test
    void updateChangesOnlyWhatIsSentAndKeepsUsageAndType() throws Exception {
        String admin = adminToken();
        Map<String, Object> m = coupon("EDIT", 10);
        m.put("type", "PERCENT");
        m.put("usageLimit", 5);
        String id = id(create(admin, m));

        update(admin, id, Map.of("active", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.active").value(false))
                .andExpect(jsonPath("$.coupon.status").value("inactive"))
                .andExpect(jsonPath("$.coupon.amount").value(10.0))
                .andExpect(jsonPath("$.coupon.usageLimit").value(5))
                .andExpect(jsonPath("$.coupon.type").value("PERCENT"));

        update(admin, id, Map.of("amount", 20, "type", "FLAT", "active", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.amount").value(20.0))
                .andExpect(jsonPath("$.coupon.type").value("PERCENT")); // the type cannot be edited

        update(admin, id, Map.of("amount", 150)).andExpect(status().isBadRequest()); // percent over 100
        update(admin, "64b7f0000000000000000000", Map.of("active", false)).andExpect(status().isNotFound());
    }

    @Test
    void previewExplainsWhyACodeIsRefused() throws Exception {
        String admin = adminToken();
        Map<String, Object> percent = coupon("PCT", 10);
        percent.put("type", "PERCENT");
        percent.put("minOrderValue", 500);
        percent.put("maxDiscount", 100);
        String id = id(create(admin, percent));

        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT").param("subtotal", "1200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discount").value(100.0)); // 120 capped at 100
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT").param("subtotal", "800"))
                .andExpect(jsonPath("$.discount").value(80.0));
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT").param("subtotal", "200"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Minimum order value for this coupon is ₹500"));
        // An old client that sends no subtotal gets a harmless zero for a percent coupon.
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discount").value(0.0));

        update(admin, id, Map.of("active", false)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "PCT").param("subtotal", "1200"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This coupon is not active"));
        mvc.perform(get("/api/v1/payment/discount").param("coupon", "NOPE").param("subtotal", "1200"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid coupon"));
    }
}
