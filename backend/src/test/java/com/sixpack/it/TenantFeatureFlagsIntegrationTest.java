package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.storage.TestImages;
import com.sixpack.tenant.domain.Tenant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** A platform administrator can switch a feature on or off for one store; the platform setting is the default. */
@TestPropertySource(properties = {
        "app.products.variants-enabled=false",
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123"})
class TenantFeatureFlagsIntegrationTest extends TenantIntegrationTest {

    private String platformToken() throws Exception {
        return body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private ResultActions setFeatures(Tenant tenant, Map<String, Object> features) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("features", features);
        return mvc.perform(put("/api/v1/platform/tenants/" + tenant.id()).header("Authorization", bearer(platformToken()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions putVariants(Tenant tenant, String token) throws Exception {
        String id = body(mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .with(host(hostOf(tenant)))
                        .param("name", "T-Shirt").param("price", "1").param("stock", "1")
                        .param("category", "clothing").header("Authorization", bearer(token)))
                .andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
        return mvc.perform(put("/api/v1/product/" + id + "/variants").with(host(hostOf(tenant)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("axes", List.of(Map.of("name", "Size", "values",
                                List.of(Map.of("label", "S")))),
                        "variants", List.of(Map.of("price", 10, "options",
                                List.of(Map.of("name", "Size", "value", "S")))))))
                .header("Authorization", bearer(token)));
    }

    private Tenant defaultTenant() {
        return tenantRepository.findBySlug("default").orElseThrow();
    }

    @Test
    void aFeatureCanBeTurnedOnForOneStoreOnly() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");

        setFeatures(acme, Map.of("variants", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.features.variants").value(true));

        putVariants(acme, adminTokenFor(acme)).andExpect(status().isOk());
        putVariants(defaultTenant(), adminToken()).andExpect(status().isForbidden());
    }

    @Test
    void theStoreConfigShowsTheEffectiveFlags() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        setFeatures(acme, Map.of("variants", true)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.tenant.features.variants").value(true));
        mvc.perform(get("/api/v1/tenant/config"))
                .andExpect(jsonPath("$.tenant.features.variants").value(false));
    }

    @Test
    void clearingTheOverridesFallsBackToThePlatformSetting() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        setFeatures(acme, Map.of("variants", true)).andExpect(status().isOk());

        setFeatures(acme, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.features.variants").doesNotExist());

        putVariants(acme, adminTokenFor(acme)).andExpect(status().isForbidden());
    }

    @Test
    void anUpdateWithoutFeaturesKeepsThem() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        setFeatures(acme, Map.of("variants", true)).andExpect(status().isOk());

        mvc.perform(put("/api/v1/platform/tenants/" + acme.id()).header("Authorization", bearer(platformToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.features.variants").value(true));
    }

    @Test
    void unknownFeatureNamesAreRejected() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");

        setFeatures(acme, Map.of("varients", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("features: unknown feature 'varients'"));
    }

    @Test
    void marketingIsOffUntilAPlatformAdminTurnsItOnForAStore() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.tenant.features.marketing").value(false));

        setFeatures(acme, Map.of("marketing", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.features.marketing").value(true));

        mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.tenant.features.marketing").value(true));
        mvc.perform(get("/api/v1/tenant/config"))
                .andExpect(jsonPath("$.tenant.features.marketing").value(false));
    }
}
