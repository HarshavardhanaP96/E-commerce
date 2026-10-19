package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.user.domain.MailSender;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/** Accounts, logins, reset links, consents, the dashboard and the audit log never cross tenants. */
class TenantIdentityIsolationIntegrationTest extends TenantIntegrationTest {

    @MockitoBean
    MailSender mailSender;

    Tenant acme;
    Tenant globex;
    String adminA;
    String adminB;

    @BeforeEach
    void twoTenants() throws Exception {
        acme = createTenant("acme", "Acme", "shop.acme.test");
        globex = createTenant("globex", "Globex", "shop.globex.test");
        adminA = adminTokenFor(acme);
        adminB = adminTokenFor(globex);
    }

    private ResultActions loginAttempt(Tenant t, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/user/login").with(host(hostOf(t))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions registerAttempt(Tenant t, String email) throws Exception {
        var request = multipart("/api/v1/user/register");
        request.with(host(hostOf(t)));
        request.param("name", "Test User").param("email", email).param("password", "password123")
                .param("gender", "female").param("dob", "2000-05-01").param("acceptTerms", "true");
        return mvc.perform(request);
    }

    private String idOf(Tenant t, String token) throws Exception {
        return body(mvc.perform(get("/api/v1/user/me").with(host(hostOf(t))).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).get("user").get("_id").asText();
    }

    @Test
    void theSameEmailCanRegisterInBothTenantsAsSeparateAccounts() throws Exception {
        registerAttempt(acme, "same@test.dev").andExpect(status().isCreated());
        registerAttempt(globex, "same@test.dev").andExpect(status().isCreated());
        registerAttempt(acme, "same@test.dev").andExpect(status().isConflict());

        String a = loginOn(acme, "same@test.dev", "password123");
        String b = loginOn(globex, "same@test.dev", "password123");
        assertThat(idOf(acme, a)).isNotEqualTo(idOf(globex, b));
    }

    @Test
    void anAccountOnlyLogsInOnItsOwnTenant() throws Exception {
        registerAttempt(acme, "only.acme@test.dev").andExpect(status().isCreated());

        loginAttempt(acme, "only.acme@test.dev", "password123").andExpect(status().isOk());
        loginAttempt(globex, "only.acme@test.dev", "password123").andExpect(status().isUnauthorized());
        loginAttempt(globex, "only.acme@test.dev", "wrong").andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminOnlySeesAndManagesTheirOwnTenantsUsers() throws Exception {
        String customerA = registerAttempt(acme, "customer.a@test.dev").andReturn().getResponse().getContentAsString();
        String idA = json.readTree(customerA).get("user").get("_id").asText();
        registerAttempt(globex, "customer.b@test.dev").andExpect(status().isCreated());

        // Admin B sees B's users (the admin and one customer), never A's.
        JsonNode users = body(mvc.perform(get("/api/v1/user/all").with(host(hostOf(globex)))
                .header("Authorization", bearer(adminB))).andExpect(status().isOk()).andReturn()).get("users");
        assertThat(users).hasSize(2);
        users.forEach(u -> assertThat(u.get("email").asText()).doesNotContain("customer.a"));

        mvc.perform(get("/api/v1/user/" + idA).with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/user/" + idA).with(host(hostOf(globex))).header("Authorization", bearer(adminB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/user/" + idA).with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/user/" + idA).with(host(hostOf(acme))).header("Authorization", bearer(adminA)))
                .andExpect(status().isOk());
    }

    @Test
    void aResetLinkOnlyWorksOnTheTenantItWasIssuedFor() throws Exception {
        registerAttempt(acme, "reset@test.dev").andExpect(status().isCreated());
        registerAttempt(globex, "reset@test.dev").andExpect(status().isCreated());

        mvc.perform(post("/api/v1/user/forgot-password").with(host(hostOf(acme))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"reset@test.dev\"}")).andExpect(status().isOk());
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendPasswordReset(eq("reset@test.dev"), anyString(), link.capture());
        String token = link.getValue().substring(link.getValue().indexOf("token=") + 6);
        String reset = json.writeValueAsString(Map.of("token", token, "password", "brand-new-password"));

        mvc.perform(post("/api/v1/user/reset-password").with(host(hostOf(globex)))
                .contentType(MediaType.APPLICATION_JSON).content(reset)).andExpect(status().isBadRequest());
        loginAttempt(globex, "reset@test.dev", "password123").andExpect(status().isOk());

        mvc.perform(post("/api/v1/user/reset-password").with(host(hostOf(acme)))
                .contentType(MediaType.APPLICATION_JSON).content(reset)).andExpect(status().isOk());
        loginAttempt(acme, "reset@test.dev", "brand-new-password").andExpect(status().isOk());
        loginAttempt(globex, "reset@test.dev", "brand-new-password").andExpect(status().isUnauthorized());
    }

    @Test
    void erasingAnAccountLeavesTheSameEmailInAnotherTenantAlone() throws Exception {
        String a = registerAttempt(acme, "twin@test.dev").andReturn().getResponse().getContentAsString();
        registerAttempt(globex, "twin@test.dev").andExpect(status().isCreated());
        String tokenA = json.readTree(a).get("token").asText();

        mvc.perform(delete("/api/v1/user/me").with(host(hostOf(acme))).header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        loginAttempt(acme, "twin@test.dev", "password123").andExpect(status().isUnauthorized());
        loginAttempt(globex, "twin@test.dev", "password123").andExpect(status().isOk());
    }

    @Test
    void consentRecordsBelongToTheirTenant() throws Exception {
        registerAttempt(acme, "consent@test.dev").andExpect(status().isCreated());

        long inAcme = mongo.getCollection("consents")
                .countDocuments(new Document("tenantId", acme.id()).append("purpose", "terms"));
        long inGlobex = mongo.getCollection("consents")
                .countDocuments(new Document("tenantId", globex.id()).append("purpose", "terms"));
        assertThat(inAcme).isPositive();
        assertThat(inGlobex).isZero();
        assertThat(mongo.getCollection("consents").countDocuments(new Document("tenantId", new Document("$exists", false))))
                .isZero();
    }

    @Test
    void theDashboardCountsOnlyTheCurrentTenant() throws Exception {
        registerAttempt(acme, "d1@test.dev");
        registerAttempt(acme, "d2@test.dev");
        registerAttempt(acme, "d3@test.dev");
        registerAttempt(globex, "e1@test.dev");

        JsonNode statsA = body(mvc.perform(get("/api/v1/dashboard/stats").with(host(hostOf(acme)))
                .header("Authorization", bearer(adminA))).andExpect(status().isOk()).andReturn()).get("stats");
        JsonNode statsB = body(mvc.perform(get("/api/v1/dashboard/stats").with(host(hostOf(globex)))
                .header("Authorization", bearer(adminB))).andExpect(status().isOk()).andReturn()).get("stats");

        assertThat(statsA.get("count").get("user").asInt()).isEqualTo(4); // admin + 3 customers
        assertThat(statsB.get("count").get("user").asInt()).isEqualTo(2); // admin + 1 customer
    }

    @Test
    void auditEntriesCarryTheTenantAndStayInIt() throws Exception {
        registerAttempt(acme, "audited@test.dev").andExpect(status().isCreated());

        JsonNode inA = body(mvc.perform(get("/api/v1/admin/audit").param("action", "USER_REGISTERED")
                .with(host(hostOf(acme))).header("Authorization", bearer(adminA))).andExpect(status().isOk())
                .andReturn()).get("entries");
        JsonNode inB = body(mvc.perform(get("/api/v1/admin/audit").param("action", "USER_REGISTERED")
                .with(host(hostOf(globex))).header("Authorization", bearer(adminB))).andExpect(status().isOk())
                .andReturn()).get("entries");

        assertThat(inA).isNotEmpty();
        assertThat(inB).isEmpty();
        assertThat(mongo.getCollection("audit_log").countDocuments(new Document("tenantId", acme.id()))).isPositive();
        assertThat(mongo.getCollection("audit_log").countDocuments(new Document("tenantId", new Document("$exists", false))
                .append("action", "USER_REGISTERED"))).isZero();
    }
}
