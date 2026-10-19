package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import org.junit.jupiter.api.Test;

class TenantResolutionIntegrationTest extends TenantIntegrationTest {

    @Test
    void theStoreConfigDescribesTheTenantForTheRequestHost() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");

        mvc.perform(get("/api/v1/tenant/config").with(host("shop.acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("acme"))
                .andExpect(jsonPath("$.tenant.name").value("Acme Shop"))
                .andExpect(jsonPath("$.tenant.theme['--accent']").value("#123456"))
                .andExpect(jsonPath("$.tenant.currency").value("INR"))
                .andExpect(jsonPath("$.tenant.domains").doesNotExist());

        mvc.perform(get("/api/v1/tenant/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("default"));
    }

    @Test
    void aSingleTenantDeploymentAnswersOnAnyHost() throws Exception {
        mvc.perform(get("/api/v1/tenant/config").with(host("whatever.example.org")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.slug").value("default"));
    }

    @Test
    void unknownHostsAreNotFoundOnceThereAreTwoTenants() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");

        mvc.perform(get("/api/v1/tenant/config").with(host("nope.example.org")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Store not found"));
        mvc.perform(get("/api/v1/product/latest").with(host("nope.example.org")))
                .andExpect(status().isNotFound());
    }

    @Test
    void suspendedTenantsAreUnavailable() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        suspend(acme);

        mvc.perform(get("/api/v1/product/latest").with(host("shop.acme.test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This store is currently unavailable"));
        mvc.perform(get("/api/v1/product/latest")).andExpect(status().isOk());
    }

    @Test
    void operationalEndpointsWorkForAnyHost() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");

        mvc.perform(get("/").with(host("nope.example.org"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").with(host("nope.example.org"))).andExpect(status().isOk());
    }

    @Test
    void theContextNeverOutlivesARequest() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");

        var before = TenantContext.current();
        mvc.perform(get("/api/v1/tenant/config").with(host("shop.acme.test"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenant/config").with(host("nope.example.org"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tenant/config")).andExpect(status().isOk());

        // Requests restore whatever was bound on the thread before them (here: the test's default tenant).
        assertThat(TenantContext.current()).isEqualTo(before);
    }

    @Test
    void consecutiveRequestsForDifferentTenantsDoNotMix() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");
        createTenant("globex", "Globex", "shop.globex.test");

        for (int i = 0; i < 20; i++) {
            String slug = i % 2 == 0 ? "acme" : "globex";
            String host = i % 2 == 0 ? "shop.acme.test" : "shop.globex.test";
            mvc.perform(get("/api/v1/tenant/config").with(host(host)))
                    .andExpect(jsonPath("$.tenant.slug").value(slug));
        }
    }
}
