package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123"})
class PlatformAdminIntegrationTest extends TenantIntegrationTest {

    private static final String API = "/api/v1/platform";

    private ResultActions platformLogin(String email, String password) throws Exception {
        return mvc.perform(post(API + "/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private String platformToken() throws Exception {
        return body(platformLogin("root@platform.test", "platform-password-123").andExpect(status().isOk())
                .andReturn()).get("token").asText();
    }

    private ResultActions createTenant(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post(API + "/tenants").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> newTenant(String slug, String domain) {
        Map<String, Object> body = new HashMap<>();
        body.put("slug", slug);
        body.put("name", slug.substring(0, 1).toUpperCase() + slug.substring(1) + " Store");
        body.put("domains", List.of(domain));
        return body;
    }

    private JsonNode claims(String token) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    @Test
    void thePlatformAdminLogsInWithoutATenant() throws Exception {
        String token = platformToken();
        assertThat(claims(token).get("role").asText()).isEqualTo("PLATFORM_ADMIN");
        assertThat(claims(token).has("tid")).isFalse();

        platformLogin("root@platform.test", "wrong-password-123").andExpect(status().isUnauthorized());
        platformLogin("nobody@platform.test", "platform-password-123").andExpect(status().isUnauthorized());
    }

    @Test
    void platformAndTenantCredentialsDoNotMix() throws Exception {
        String platform = platformToken();
        String tenantAdmin = adminToken();
        String customer = register("customer@test.dev");

        // A platform token is worthless on a store, even a public page.
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(platform))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/product/all").header("Authorization", bearer(platform)))
                .andExpect(status().isUnauthorized());
        // Store tokens cannot run the platform.
        mvc.perform(get(API + "/tenants").header("Authorization", bearer(tenantAdmin))).andExpect(status().isForbidden());
        mvc.perform(get(API + "/tenants").header("Authorization", bearer(customer))).andExpect(status().isForbidden());
        mvc.perform(get(API + "/tenants")).andExpect(status().isUnauthorized());
        // Store credentials are not platform credentials.
        platformLogin("admin@test.dev", "admin-password-123").andExpect(status().isUnauthorized());
    }

    @Test
    void aTenantCanBeCreatedListedAndServed() throws Exception {
        String token = platformToken();

        createTenant(token, newTenant("acme", "shop.acme.test")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.slug").value("acme"))
                .andExpect(jsonPath("$.tenant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tenant.domains[0]").value("shop.acme.test"));

        mvc.perform(get(API + "/tenants").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenants.length()").value(2)); // default + acme
        mvc.perform(get("/api/v1/tenant/config").with(host("shop.acme.test")))
                .andExpect(jsonPath("$.tenant.name").value("Acme Store"));
        // From now on unknown hosts are refused: there are two stores.
        mvc.perform(get("/api/v1/tenant/config").with(host("nope.example.org"))).andExpect(status().isNotFound());
    }

    @Test
    void anInitialAdminIsCreatedInsideTheNewTenantOnly() throws Exception {
        String token = platformToken();
        Map<String, Object> body = newTenant("globex", "shop.globex.test");
        body.put("admin", Map.of("name", "Globex Owner", "email", "owner@globex.test", "password", "owner-password-123"));

        createTenant(token, body).andExpect(status().isCreated());

        assertThat(loginOn(tenantRepository.findBySlug("globex").orElseThrow(), "owner@globex.test", "owner-password-123"))
                .isNotBlank();
        mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "owner@globex.test",
                                "password", "owner-password-123"))))
                .andExpect(status().isUnauthorized()); // not an account on the default store
    }

    @Test
    void slugsAndDomainsMustBeValidAndUnique() throws Exception {
        String token = platformToken();
        createTenant(token, newTenant("acme", "shop.acme.test")).andExpect(status().isCreated());

        createTenant(token, newTenant("acme", "other.acme.test")).andExpect(status().isConflict());
        createTenant(token, newTenant("acme2", "shop.acme.test")).andExpect(status().isConflict());
        createTenant(token, newTenant("default", "x.example.test")).andExpect(status().isConflict());
        createTenant(token, newTenant("Bad Slug!", "bad.example.test")).andExpect(status().isBadRequest());
        createTenant(token, newTenant("good", "not a host")).andExpect(status().isBadRequest());
        Map<String, Object> none = newTenant("nodomain", "x.example.test");
        none.put("domains", List.of());
        createTenant(token, none).andExpect(status().isBadRequest());
        // Domains are case-insensitive, so this clashes with shop.acme.test.
        createTenant(token, newTenant("acme3", "SHOP.ACME.TEST")).andExpect(status().isConflict());
    }

    @Test
    void aTenantCanBeEditedSuspendedAndReactivated() throws Exception {
        String token = platformToken();
        String id = body(createTenant(token, newTenant("acme", "shop.acme.test")).andReturn()).get("tenant").get("id").asText();

        mvc.perform(put(API + "/tenants/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Acme Renamed",
                                "theme", Map.of("--accent", "#ff0000"),
                                "legal", Map.of("termsVersion", "2031-01-01"),
                                "payment", Map.of("provider", "razorpay", "keyId", "rzp_test_acme", "secretRef", "ACME"),
                                "mail", Map.of("baseUrl", "https://store.acme.example")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.name").value("Acme Renamed"))
                .andExpect(jsonPath("$.tenant.legal.termsVersion").value("2031-01-01"))
                .andExpect(jsonPath("$.tenant.payment.keyId").value("rzp_test_acme"))
                .andExpect(jsonPath("$.tenant.payment.secretRef").value("ACME"));
        mvc.perform(get("/api/v1/tenant/config").with(host("shop.acme.test")))
                .andExpect(jsonPath("$.tenant.name").value("Acme Renamed"))
                .andExpect(jsonPath("$.tenant.theme['--accent']").value("#ff0000"));

        mvc.perform(post(API + "/tenants/" + id + "/suspend").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenant.status").value("SUSPENDED"));
        mvc.perform(get("/api/v1/product/latest").with(host("shop.acme.test"))).andExpect(status().isForbidden());

        mvc.perform(post(API + "/tenants/" + id + "/activate").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/product/latest").with(host("shop.acme.test"))).andExpect(status().isOk());
    }

    @Test
    void storefrontDetailsAreValidatedAndServedPublicly() throws Exception {
        String token = platformToken();
        String id = body(createTenant(token, newTenant("acme", "shop.acme.test")).andReturn()).get("tenant").get("id").asText();

        mvc.perform(put(API + "/tenants/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storefront", Map.of(
                                "logoUrl", "https://cdn.acme.test/logo.png", "legalName", "Acme Retail Pvt Ltd",
                                "address", "1 MG Road, Pune", "email", "help@acme.test", "phone", "+91 98765 43210",
                                "grievanceOfficer", "A. Rao", "grievanceEmail", "grievance@acme.test")))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/tenant/config").with(host("shop.acme.test")))
                .andExpect(jsonPath("$.tenant.storefront.legalName").value("Acme Retail Pvt Ltd"))
                .andExpect(jsonPath("$.tenant.storefront.email").value("help@acme.test"))
                .andExpect(jsonPath("$.tenant.storefront.logoUrl").value("https://cdn.acme.test/logo.png"))
                .andExpect(jsonPath("$.tenant.storefront.grievanceOfficer").value("A. Rao"));
        // The default store has none, so the storefront shows its built-in placeholders.
        mvc.perform(get("/api/v1/tenant/config")).andExpect(jsonPath("$.tenant.storefront.legalName").doesNotExist());

        for (Map<String, String> bad : List.<Map<String, String>>of(
                Map.of("logoUrl", "javascript:alert(1)"), Map.of("logoUrl", "http://insecure.example/x.png"),
                Map.of("email", "not-an-email"), Map.of("grievanceEmail", "a b@c.d"),
                Map.of("legalName", "x".repeat(201)))) {
            mvc.perform(put(API + "/tenants/" + id).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("storefront", bad))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void theDefaultTenantCannotBeSuspendedAndUnknownIdsAre404() throws Exception {
        String token = platformToken();
        String defaultId = tenantRepository.findBySlug("default").orElseThrow().id();

        mvc.perform(post(API + "/tenants/" + defaultId + "/suspend").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/tenants/64b7f0000000000000000000").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mvc.perform(put(API + "/tenants/64b7f0000000000000000000").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
    }
}
