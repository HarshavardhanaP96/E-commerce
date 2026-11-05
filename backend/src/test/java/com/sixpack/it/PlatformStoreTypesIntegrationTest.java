package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.shared.tenant.StoreType;
import com.sixpack.tenant.domain.Tenant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123"})
class PlatformStoreTypesIntegrationTest extends TenantIntegrationTest {

    private static final String API = "/api/v1/platform/tenants";

    private String platformToken() throws Exception {
        return body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private ResultActions create(String slug, String type, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("slug", slug);
        body.put("name", slug + " store");
        body.put("domains", List.of("shop." + slug + ".test"));
        if (type != null) {
            body.put("type", type);
        }
        return mvc.perform(post(API).header("Authorization", bearer(platformToken()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private Tenant stored(String slug) {
        return tenantRepository.findBySlug(slug).orElseThrow();
    }

    @Test
    void aFashionStoreStartsWithItsPresetAsItsOwnSettings() throws Exception {
        create("acme", "fashion", Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.type").value("FASHION"))
                .andExpect(jsonPath("$.tenant.features.variants").value(true));
        Tenant acme = stored("acme");
        assertThat(acme.settings().commerce().defaultTaxRate()).isEqualTo(5.0);
        assertThat(acme.settings().catalog().lowStockThreshold()).isEqualTo(3);
        assertThat(acme.settings().commerce().shippingFee()).isNull();
    }

    @Test
    void anExplicitFeatureInTheRequestWinsOverThePreset() throws Exception {
        create("grocer", "GROCERY", Map.of("features", Map.of("variants", true))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.features.variants").value(true));
    }

    @Test
    void anUnknownTypeIsRejected() throws Exception {
        create("bakery", "bakery", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("type: unknown store type"));
        assertThat(tenantRepository.findBySlug("bakery")).isEmpty();
    }

    @Test
    void aStoreWithoutATypeIsGeneral() throws Exception {
        create("plain", null, Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.type").value("GENERAL"));
        Tenant legacy = createTenant("legacy", "Legacy", "shop.legacy.test");
        mvc.perform(get(API + "/" + legacy.id()).header("Authorization", bearer(platformToken())))
                .andExpect(jsonPath("$.tenant.type").value("GENERAL"));
    }

    @Test
    void changingTheTypeIsOnlyALabel() throws Exception {
        create("grocer", "grocery", Map.of()).andExpect(status().isCreated());
        Tenant grocer = stored("grocer");
        mvc.perform(put(API + "/" + grocer.id()).header("Authorization", bearer(platformToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("type", "fashion"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenant.type").value("FASHION"))
                .andExpect(jsonPath("$.tenant.features.variants").value(false));
        Tenant after = stored("grocer");
        assertThat(after.type()).isEqualTo(StoreType.FASHION);
        assertThat(after.settings().catalog().lowStockThreshold()).isEqualTo(10);
    }

    @Test
    void theCreationIsAuditedWithTheTypeAndThePresetValues() throws Exception {
        create("grocer", "grocery", Map.of()).andExpect(status().isCreated());
        Document entry = auditEntries("TENANT_CREATED").get(0);
        assertThat(changeOf(entry, "type").get("after")).isEqualTo("GROCERY");
        assertThat(changeOf(entry, "settings.commerce.shippingFee").get("after")).isEqualTo(40.0);
        assertThat(changeOf(entry, "settings.catalog.lowStockThreshold").get("after")).isEqualTo(10);
    }
}
