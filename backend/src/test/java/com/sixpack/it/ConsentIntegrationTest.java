package com.sixpack.it;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ConsentIntegrationTest extends IntegrationTest {

    @Test
    void registrationRequiresTermsAndAnAdultAndRecordsConsent() throws Exception {
        mvc.perform(multipart("/api/v1/user/register").param("name", "N").param("email", "noterms@test.dev")
                        .param("password", "password123").param("dob", "2000-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("acceptTerms")));

        String seventeen = LocalDate.now().minusYears(17).toString();
        mvc.perform(multipart("/api/v1/user/register").param("name", "Kid").param("email", "kid@test.dev")
                        .param("password", "password123").param("dob", seventeen).param("acceptTerms", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("18 or older")));

        // Gender is optional; marketing defaults to off.
        String token = body(mvc.perform(multipart("/api/v1/user/register").param("name", "Adult")
                        .param("email", "adult@test.dev").param("password", "password123")
                        .param("dob", "1990-01-01").param("acceptTerms", "true")
                        .header("User-Agent", "JUnit"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.gender").doesNotExist())
                .andReturn()).get("token").asText();

        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending", hasSize(0)))
                .andExpect(jsonPath("$.consents.terms.granted").value(true))
                .andExpect(jsonPath("$.consents.terms.version").value("2026-10-16"))
                .andExpect(jsonPath("$.consents.marketing_email.granted").value(false))
                .andExpect(jsonPath("$.history", hasSize(3)));

        Document stored = mongo.getCollection("consents").find(new Document("purpose", "terms")).first();
        org.assertj.core.api.Assertions.assertThat(stored.getString("ipHash")).hasSize(64)
                .isNotEqualTo("127.0.0.1");
        org.assertj.core.api.Assertions.assertThat(stored.getString("userAgent")).isEqualTo("JUnit");
    }

    @Test
    void legalVersionsArePublic() throws Exception {
        mvc.perform(get("/api/v1/legal/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.terms").value("2026-10-16"))
                .andExpect(jsonPath("$.versions.privacy").value("2026-10-16"));
    }

    @Test
    void usersWithoutConsentArePromptedAndCannotCheckOutUntilTheyAccept() throws Exception {
        String token = register("legacy-consent@test.dev");
        mongo.getCollection("consents").deleteMany(new Document()); // like an account created before consents existed
        String product = createProduct(adminToken(), "Lamp", 400, 3, "home");

        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.pending", hasSize(2)));
        checkout(token, product).andExpect(status().isForbidden());

        decide(token, "terms", true, "reprompt").andExpect(status().isOk());
        decide(token, "privacy", true, "reprompt").andExpect(jsonPath("$.pending", hasSize(0)));
        checkout(token, product).andExpect(status().isCreated());
    }

    @Test
    void marketingCanBeWithdrawnButRequiredPoliciesCannot() throws Exception {
        String token = register("toggle@test.dev");
        decide(token, "marketing_email", true, "settings")
                .andExpect(jsonPath("$.consents.marketing_email.granted").value(true));
        decide(token, "marketing_email", false, "settings")
                .andExpect(jsonPath("$.consents.marketing_email.granted").value(false))
                .andExpect(jsonPath("$.history", hasSize(5))); // append-only: nothing was overwritten
        decide(token, "terms", false, "settings").andExpect(status().isBadRequest());
        decide(token, "terms", true, "register").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/consent/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void accountDeletionNeedsThePasswordWaitsForDeliveryAndAnonymisesOrders() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Book", 300, 5, "books");
        String token = register("erase@test.dev");
        String userId = userId(token);
        String providerOrderId = body(checkout(token, product).andReturn()).get("checkout").get("providerOrderId")
                .asText();
        String orderId = body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("providerOrderId", providerOrderId,
                                "paymentId", "fake_pay_1", "signature", "fake",
                                "shippingInfo", Map.of("address", "12 MG Road", "city", "Bengaluru",
                                        "state", "Karnataka", "country", "India", "pinCode", "560001")))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();

        deleteMe(token, "wrong-password").andExpect(status().isForbidden());
        deleteMe(token, "password123").andExpect(status().isConflict());
        // Admins are held to the same rule.
        mvc.perform(delete("/api/v1/user/" + userId).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());

        for (int i = 0; i < 2; i++) { // processing → shipped → delivered
            mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                    .andExpect(status().isOk());
        }
        deleteMe(token, "password123").andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(users.findById(userId)).isEmpty();
        Document shipping = mongo.getCollection("orders").find().first().get("shippingInfo", Document.class);
        org.assertj.core.api.Assertions.assertThat(shipping).doesNotContainKeys("address", "pinCode")
                .containsEntry("state", "Karnataka");
        org.assertj.core.api.Assertions.assertThat(mongo.getCollection("orders").find().first()
                .getString("termsVersion")).isEqualTo("2026-10-16");
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminsCannotDeleteThemselvesThroughSelfService() throws Exception {
        deleteMe(adminToken(), "admin-password-123").andExpect(status().isBadRequest());
    }

    private ResultActions decide(String token, String purpose, boolean granted, String source) throws Exception {
        return mvc.perform(post("/api/v1/consent").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("purpose", purpose, "granted", granted, "source", source))));
    }

    private ResultActions checkout(String token, String productId) throws Exception {
        return mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(Map.of("productId", productId,
                        "quantity", 1))))));
    }

    private ResultActions deleteMe(String token, String password) throws Exception {
        return mvc.perform(delete("/api/v1/user/me").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", password))));
    }
}
