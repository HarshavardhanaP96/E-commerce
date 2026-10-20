package com.sixpack.it;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What a platform administrator does to a store is written to that store's own audit log, where the store's admins
 * can see it, and platform administrators can read every store's log from one read-only endpoint.
 */
@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123"})
class PlatformAuditIntegrationTest extends TenantIntegrationTest {

    private static final String API = "/api/v1/platform";

    private JsonNode platformLogin() throws Exception {
        return body(mvc.perform(post(API + "/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn());
    }

    private String platformToken() throws Exception {
        return platformLogin().get("token").asText();
    }

    private String platformAdminId() throws Exception {
        String token = platformToken();
        return json.readTree(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1])).get("sub").asText();
    }

    private Tenant createAcme(String token) throws Exception {
        mvc.perform(post(API + "/tenants").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("slug", "acme", "name", "Acme Shop",
                                "domains", List.of("shop.acme.test")))))
                .andExpect(status().isCreated());
        return tenantRepository.findBySlug("acme").orElseThrow();
    }

    private ResultActions storeLog(Tenant tenant, String... params) throws Exception {
        var request = get("/api/v1/admin/audit").with(host(hostOf(tenant)))
                .header("Authorization", bearer(adminTokenFor(tenant)));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions platformLog(String token, String... params) throws Exception {
        var request = get(API + "/audit").header("Authorization", bearer(token));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    @Test
    void creatingAStoreIsRecordedInThatStoresLog() throws Exception {
        String token = platformToken();
        Tenant acme = createAcme(token);

        storeLog(acme, "action", "tenant_created")
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].actorRole").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.entries[0].actorId").value(platformAdminId()))
                .andExpect(jsonPath("$.entries[0].entityType").value("tenant"))
                .andExpect(jsonPath("$.entries[0].entityId").value(acme.id()))
                .andExpect(jsonPath("$.entries[0].changes[?(@.field == 'slug')].after").value(hasItem("acme")));
    }

    @Test
    void paymentAccountAndFeatureEditsRecordWhatChanged() throws Exception {
        String token = platformToken();
        Tenant acme = createAcme(token);

        mvc.perform(put(API + "/tenants/" + acme.id()).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "payment", Map.of("provider", "razorpay", "keyId", "rzp_live_acme",
                                        "secretRef", "ACME"),
                                "features", Map.of("variants", true)))))
                .andExpect(status().isOk());

        storeLog(acme, "action", "tenant_updated")
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].actorRole").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.entries[0].changes[?(@.field == 'payment.keyId')].after")
                        .value(hasItem("rzp_live_acme")))
                .andExpect(jsonPath("$.entries[0].changes[?(@.field == 'features.variants')].after")
                        .value(hasItem(true)));
    }

    @Test
    void suspendingAndReactivatingAreRecorded() throws Exception {
        String token = platformToken();
        Tenant acme = createAcme(token);

        mvc.perform(post(API + "/tenants/" + acme.id() + "/suspend").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(post(API + "/tenants/" + acme.id() + "/activate").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        storeLog(acme, "action", "tenant_status_changed")
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].changes[0].field").value("status"))
                .andExpect(jsonPath("$.entries[0].changes[0].before").value("SUSPENDED"))
                .andExpect(jsonPath("$.entries[0].changes[0].after").value("ACTIVE"))
                .andExpect(jsonPath("$.entries[1].changes[0].after").value("SUSPENDED"));
    }

    @Test
    void anotherStoresLogDoesNotSeeIt() throws Exception {
        createAcme(platformToken());

        storeLog(tenantRepository.findBySlug("default").orElseThrow(), "entityType", "tenant")
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    void thePlatformReadsEveryStoresLogAndCanNarrowToOne() throws Exception {
        String token = platformToken();
        Tenant acme = createAcme(token);
        Tenant defaultTenant = tenantRepository.findBySlug("default").orElseThrow();
        // A no-op edit (the default tenant is shared by every test class): it is still recorded.
        mvc.perform(put(API + "/tenants/" + defaultTenant.id()).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locale\":\"" + defaultTenant.locale() + "\"}"))
                .andExpect(status().isOk());

        platformLog(token, "entityType", "tenant")
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].tenantId").value(defaultTenant.id()))
                .andExpect(jsonPath("$.entries[1].tenantId").value(acme.id()));
        platformLog(token, "entityType", "tenant", "tenantId", acme.id())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].action").value("TENANT_CREATED"));
    }

    @Test
    void onlyPlatformAdminsCanReadThePlatformLog() throws Exception {
        mvc.perform(get(API + "/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/audit").header("Authorization", bearer(adminToken())))
                .andExpect(status().isForbidden());
    }
}
