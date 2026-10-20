package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.shared.security.TokenService;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class TenantTokenIntegrationTest extends TenantIntegrationTest {

    @Autowired
    TokenService tokens;

    private JsonNode claims(String token) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    @Test
    void tokensCarryTheTenantTheyWereIssuedFor() throws Exception {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        String token = register("tid@test.dev");

        assertThat(claims(token).get("tid").asText()).isEqualTo(def.id());
    }

    @Test
    void aTokenIsRejectedOnAnotherTenantsHost() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        String token = register("cross@test.dev");
        registerOn(acme, "cross@test.dev"); // the same person has a separate account on the other store

        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token)).with(host("shop.acme.test")))
                .andExpect(status().isUnauthorized());

        // A token minted on the second tenant works there and nowhere else.
        String acmeToken = body(mvc.perform(post("/api/v1/user/login").with(host("shop.acme.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "cross@test.dev", "password", "password123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
        assertThat(claims(acmeToken).get("tid").asText()).isEqualTo(acme.id());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(acmeToken)).with(host("shop.acme.test")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(acmeToken))).andExpect(status().isUnauthorized());
    }

    @Test
    void tokensIssuedBeforeTheChangeKeepWorkingOnTheDefaultTenantOnly() throws Exception {
        String userId = userId(register("legacy@test.dev"));
        // Issued outside any request, so it has no tid claim, like a token from before this change.
        String legacy;
        try (var none = TenantContext.open(null)) {
            legacy = tokens.issue(userId, "USER");
        }
        assertThat(claims(legacy).has("tid")).isFalse();

        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(legacy))).andExpect(status().isOk());

        createTenant("acme", "Acme Shop", "shop.acme.test");
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(legacy)).with(host("shop.acme.test")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(legacy))).andExpect(status().isOk());
    }
}
