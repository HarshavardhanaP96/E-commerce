package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.shared.tenant.StoreSettings;
import com.sixpack.storage.TestImages;
import com.sixpack.tenant.domain.Tenant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123",
        "app.tenant.limits.products=50",
        "app.tenant.limits.requests-per-minute=100000"})
class PlatformStoreDetailsIntegrationTest extends TenantIntegrationTest {

    private String platformToken() throws Exception {
        return body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private ResultActions details(Tenant t) throws Exception {
        return mvc.perform(get("/api/v1/platform/tenants/" + t.id()).header("Authorization", bearer(platformToken())));
    }

    private void newProduct(Tenant tenant, String token, String name) throws Exception {
        mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .with(host(hostOf(tenant)))
                        .param("name", name).param("price", "10").param("stock", "1").param("category", "misc")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated());
    }

    @Test
    void detailsCarryTheOwnersSettingsAndThePlatformDefaults() throws Exception {
        Tenant acme = createTenant("acme", "Acme", "shop.acme.test");
        tenantRepository.save(acme.withOwnerSettings(new StoreSettings(
                new StoreSettings.Commerce(49.0, null, null, null, null, null, null), null, null, null),
                acme.storefront()));
        details(acme).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("acme"))
                .andExpect(jsonPath("$.settings.commerce.shippingFee").value(49.0))
                .andExpect(jsonPath("$.defaults.features.marketing").value(false))
                .andExpect(jsonPath("$.defaults.limits.products").value(50))
                .andExpect(jsonPath("$.defaults.limits.requestsPerMinute").value(100000))
                .andExpect(jsonPath("$.usage.products").value(0));
    }

    @Test
    void usageCountsOnlyThatStoresProductsAndFiles() throws Exception {
        Tenant acme = createTenant("acme", "Acme", "shop.acme.test");
        Tenant globex = createTenant("globex", "Globex", "shop.globex.test");
        String adminA = adminTokenFor(acme);
        newProduct(acme, adminA, "One");
        newProduct(acme, adminA, "Two");
        newProduct(globex, adminTokenFor(globex), "Solo");

        JsonNode a = body(details(acme).andReturn()).get("usage");
        JsonNode g = body(details(globex).andReturn()).get("usage");
        assertThat(a.get("products").asLong()).isEqualTo(2);
        assertThat(g.get("products").asLong()).isEqualTo(1);
        assertThat(g.get("uploadBytes").asLong()).isPositive();
        assertThat(a.get("uploadBytes").asLong()).isEqualTo(2 * g.get("uploadBytes").asLong());
    }

    @Test
    void theListCarriesEachStoresType() throws Exception {
        createTenant("acme", "Acme", "shop.acme.test");
        mvc.perform(get("/api/v1/platform/tenants").header("Authorization", bearer(platformToken())))
                .andExpect(jsonPath("$.tenants[?(@.slug == 'acme')].type").value("GENERAL"));
    }
}
