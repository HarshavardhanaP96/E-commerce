package com.sixpack.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.tenant.application.TenantRegistry;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Base for tests that need more than one tenant. Adds helpers in a subclass on purpose: the shared
 * {@link IntegrationTest} base is edited by several workstreams. The {@code default} tenant is created by the
 * application at startup and is never removed here.
 */
abstract class TenantIntegrationTest extends IntegrationTest {

    protected Tenant createTenant(String slug, String name, String domain) {
        Tenant tenant = tenantRepository.save(new Tenant(null, slug, name, TenantStatus.ACTIVE, List.of(domain),
                Map.of("--accent", "#123456"), "en-IN", "INR", null));
        tenantRegistry.refresh();
        return tenant;
    }

    protected static TenantRef ref(Tenant tenant) {
        return new TenantRef(tenant.id(), tenant.slug());
    }

    /** Creates (once) an admin that belongs to {@code tenant} and returns a token valid on that tenant's host. */
    protected String adminTokenFor(Tenant tenant) throws Exception {
        String email = "admin@" + tenant.slug() + ".test";
        TenantContext.runAs(ref(tenant), () -> {
            if (users.findByEmail(email).isEmpty()) {
                users.save(new User(null, "Admin " + tenant.slug(), email, encoder.encode("admin-password-123"), "",
                        Role.ADMIN, Gender.MALE, LocalDate.of(1990, 1, 1), null));
            }
        });
        String token = loginOn(tenant, email, "admin-password-123");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/consent").with(host(hostOf(tenant)))
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("purpose", "admin_aup", "granted", true,
                                "source", "reprompt"))))
                .andExpect(MockMvcResultMatchers.status().isOk());
        return token;
    }

    protected String loginOn(Tenant tenant, String email, String password) throws Exception {
        JsonNode res = body(mvc.perform(MockMvcRequestBuilders.post("/api/v1/user/login")
                        .with(host(tenant.domains().get(0))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password))))
                .andExpect(MockMvcResultMatchers.status().isOk()).andReturn());
        return res.get("token").asText();
    }

    /** Registers a customer on the tenant's host and returns a token valid there. */
    protected String registerOn(Tenant tenant, String email) throws Exception {
        var request = MockMvcRequestBuilders.multipart("/api/v1/user/register");
        request.with(host(hostOf(tenant)));
        request.param("name", "Test User").param("email", email).param("password", "password123")
                .param("gender", "female").param("dob", "2000-05-01").param("acceptTerms", "true");
        return body(mvc.perform(request).andExpect(MockMvcResultMatchers.status().isCreated()).andReturn())
                .get("token").asText();
    }

    protected static String hostOf(Tenant tenant) {
        return tenant.domains().get(0);
    }

    protected Tenant suspend(Tenant tenant) {
        Tenant suspended = tenantRepository.save(new Tenant(tenant.id(), tenant.slug(), tenant.name(),
                TenantStatus.SUSPENDED, tenant.domains(), tenant.theme(), tenant.locale(), tenant.currency(),
                tenant.createdAt()));
        tenantRegistry.refresh();
        return suspended;
    }

    /** Makes the request look like it arrived on {@code host} (the port is ignored, as in production). */
    protected static RequestPostProcessor host(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }

    @AfterEach
    void removeExtraTenants() {
        mongo.getCollection("tenants").deleteMany(new Document("slug", new Document("$ne", "default")));
        tenantRegistry.refresh();
    }
}
