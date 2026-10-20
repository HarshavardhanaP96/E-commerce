package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.storage.TestImages;
import com.sixpack.tenant.domain.Tenant;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** One busy or abusive store cannot take more than its share: products, upload storage and request rate. */
@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123",
        "app.tenant.limits.products=2",
        "app.tenant.limits.uploads=1MB",
        "app.tenant.limits.requests-per-minute=100000"})
class TenantLimitsIntegrationTest extends TenantIntegrationTest {

    @Autowired
    MeterRegistry meters;

    private String platformToken() throws Exception {
        return body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private ResultActions setLimits(Tenant tenant, Map<String, Object> limits) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("limits", limits);
        return mvc.perform(put("/api/v1/platform/tenants/" + tenant.id()).header("Authorization", bearer(platformToken()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions newProduct(Tenant tenant, String token, String name) throws Exception {
        return mvc.perform(multipart("/api/v1/product/new")
                .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                .with(host(hostOf(tenant)))
                .param("name", name).param("price", "10").param("stock", "1").param("category", "misc")
                .header("Authorization", bearer(token)));
    }

    private long products(Tenant tenant) {
        return mongo.getCollection("products").countDocuments(new Document("tenantId", tenant.id()));
    }

    @Test
    void aStoreCannotCreateMoreProductsThanItsLimit() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        String admin = adminTokenFor(acme);
        newProduct(acme, admin, "One").andExpect(status().isCreated());
        newProduct(acme, admin, "Two").andExpect(status().isCreated());

        newProduct(acme, admin, "Three").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This store has reached its limit of 2 products"));
        assertThat(products(acme)).isEqualTo(2);
    }

    @Test
    void aStoresOwnLimitOverridesThePlatformDefault() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        setLimits(acme, Map.of("products", 3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.limits.products").value(3));
        String admin = adminTokenFor(acme);
        for (String name : new String[] {"One", "Two", "Three"}) {
            newProduct(acme, admin, name).andExpect(status().isCreated());
        }
        newProduct(acme, admin, "Four").andExpect(status().isForbidden());

        setLimits(acme, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.limits.products").doesNotExist());
    }

    @Test
    void uploadsStopAtTheStorageLimit() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        int image = TestImages.png().length;
        setLimits(acme, Map.of("uploadBytes", image + image / 2)).andExpect(status().isOk());
        String admin = adminTokenFor(acme);
        newProduct(acme, admin, "One").andExpect(status().isCreated());

        newProduct(acme, admin, "Two").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This store has used its upload storage"));
        assertThat(products(acme)).isEqualTo(1);
    }

    @Test
    void aStoreThatSendsTooManyRequestsIsSlowedDownAlone() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        setLimits(acme, Map.of("requestsPerMinute", 5)).andExpect(status().isOk());
        double before = meters.counter("sixpack.ratelimit.rejected", "tenant", "acme").count();

        // A fixed one-minute window: within 2 x limit + 1 requests at least one is refused even across a boundary.
        int refused = 0;
        for (int i = 0; i < 11; i++) {
            int code = mvc.perform(get("/api/v1/product/latest").with(host(hostOf(acme)))).andReturn()
                    .getResponse().getStatus();
            if (code == 429) {
                refused++;
            }
        }

        assertThat(refused).isPositive();
        assertThat(meters.counter("sixpack.ratelimit.rejected", "tenant", "acme").count()).isEqualTo(before + refused);
        mvc.perform(get("/api/v1/product/latest")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").with(host(hostOf(acme)))).andExpect(status().isOk());
    }

    @Test
    void limitsMustBePositive() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");

        setLimits(acme, Map.of("products", 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limits: values must be positive"));
        setLimits(acme, Map.of("requestsPerMinute", -1)).andExpect(status().isBadRequest());
    }
}
