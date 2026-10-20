package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.payment.PaymentGateways;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.LegalSettings;
import com.sixpack.shared.tenant.MailSettings;
import com.sixpack.shared.tenant.PaymentSettings;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.user.domain.MailSender;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Legal versions, payment accounts and email links are per tenant, with platform defaults as the fallback. */
class TenantConfigurationIntegrationTest extends TenantIntegrationTest {

    @MockitoBean
    MailSender mailSender;

    @Autowired
    PaymentGateways gateways;

    Tenant acme;
    Tenant globex;

    @BeforeEach
    void twoTenants() {
        acme = createTenant("acme", "Acme", "shop.acme.test");
        globex = createTenant("globex", "Globex", "shop.globex.test");
    }

    @AfterEach
    void clearSecrets() {
        System.clearProperty("TENANT_SECRET_ACME");
    }

    private Tenant configure(Tenant t, LegalSettings legal, PaymentSettings payment, MailSettings mail) {
        Tenant updated = tenantRepository.save(new Tenant(t.id(), t.slug(), t.name(), t.status(), t.domains(),
                t.theme(), t.locale(), t.currency(), t.createdAt(), legal, payment, mail));
        tenantRegistry.refresh();
        return updated;
    }

    private JsonNode versions(Tenant t) throws Exception {
        return body(mvc.perform(get("/api/v1/legal/versions").with(host(hostOf(t)))).andExpect(status().isOk())
                .andReturn()).get("versions");
    }

    private JsonNode pending(Tenant t, String token) throws Exception {
        return body(mvc.perform(get("/api/v1/consent/me").with(host(hostOf(t))).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).get("pending");
    }

    @Test
    void aTenantWithNoSettingsUsesThePlatformDefaults() throws Exception {
        JsonNode platform = versions(tenantRepository.findBySlug("default").orElseThrow());
        assertThat(versions(acme)).isEqualTo(platform);
    }

    @Test
    void policyVersionsAreTheTenantsOwn() throws Exception {
        String userA = registerOn(acme, "reader@test.dev");
        String userB = registerOn(globex, "reader@test.dev");
        assertThat(pending(acme, userA)).isEmpty();
        assertThat(pending(globex, userB)).isEmpty();

        configure(acme, new LegalSettings("2031-01-01", null, null), PaymentSettings.NONE, MailSettings.NONE);

        assertThat(versions(acme).get("terms").asText()).isEqualTo("2031-01-01");
        assertThat(versions(globex).get("terms").asText()).isNotEqualTo("2031-01-01");
        assertThat(pending(acme, userA).toString()).contains("terms");
        assertThat(pending(globex, userB)).isEmpty();
    }

    @Test
    void theAdminAgreementVersionIsPerTenant() throws Exception {
        String adminA = adminTokenFor(acme);
        String adminB = adminTokenFor(globex);
        mvc.perform(get("/api/v1/dashboard/stats").with(host(hostOf(acme))).header("Authorization", bearer(adminA)))
                .andExpect(status().isOk());

        configure(acme, new LegalSettings(null, null, "2032-02-02"), PaymentSettings.NONE, MailSettings.NONE);

        mvc.perform(get("/api/v1/dashboard/stats").with(host(hostOf(acme))).header("Authorization", bearer(adminA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dashboard/stats").with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(status().isOk());
    }

    private String productOn(Tenant t, String admin) throws Exception {
        var req = multipart("/api/v1/product/new")
                .file(new MockMultipartFile("photo", "p.png", "image/png", com.sixpack.storage.TestImages.png()));
        req.with(host(hostOf(t)));
        req.param("name", "Lamp").param("price", "500").param("stock", "10").param("category", "misc")
                .header("Authorization", bearer(admin));
        return body(mvc.perform(req).andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions checkout(Tenant t, String token, String productId)
            throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        return mvc.perform(post("/api/v1/order/checkout").with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)));
    }

    @Test
    void aTenantWithoutItsOwnPaymentAccountCannotTakePayments() throws Exception {
        String admin = adminTokenFor(acme);
        String product = productOn(acme, admin);
        String buyer = registerOn(acme, "buyer@test.dev");

        // Never route another store's money through the platform's account.
        checkout(acme, buyer, product).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Payments are not configured for this store"));

        configure(acme, LegalSettings.NONE, new PaymentSettings("fake", null, null), MailSettings.NONE);
        checkout(acme, buyer, product).andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkout.provider").value("fake"));
    }

    @Test
    void theDefaultTenantStillUsesThePlatformGateway() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Lamp", 500, 10, "misc");
        String buyer = register("buyer@test.dev");

        mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items", List.of(Map.of("productId", product, "quantity", 1))))))
                .andExpect(status().isCreated());
    }

    @Test
    void razorpayKeysComeFromTheTenant() {
        configure(acme, LegalSettings.NONE, new PaymentSettings("razorpay", "rzp_test_acme", "ACME"), MailSettings.NONE);

        // Without the secret in the environment the store is simply not configured.
        assertThatThrownBy(() -> TenantContext.callAs(ref(acme), gateways::current))
                .isInstanceOf(ApiException.class).hasMessageContaining("Payments are not configured");

        System.setProperty("TENANT_SECRET_ACME", "super-secret");
        var gateway = TenantContext.callAs(ref(acme), gateways::current);
        assertThat(gateway.provider()).isEqualTo("razorpay");
        assertThat(gateway.publicKey()).isEqualTo("rzp_test_acme");
        // Another store never receives these keys.
        assertThatThrownBy(() -> TenantContext.callAs(ref(globex), gateways::current)).isInstanceOf(ApiException.class);
    }

    @Test
    void resetLinksPointAtTheTenantsOwnStorefront() throws Exception {
        configure(globex, LegalSettings.NONE, PaymentSettings.NONE, new MailSettings("https://store.globex.example/"));
        registerOn(acme, "link@test.dev");
        registerOn(globex, "link@test.dev");

        for (Tenant t : List.of(acme, globex)) {
            mvc.perform(post("/api/v1/user/forgot-password").with(host(hostOf(t))).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"link@test.dev\"}")).andExpect(status().isOk());
        }
        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        verify(mailSender, org.mockito.Mockito.times(2)).sendPasswordReset(eq("link@test.dev"), anyString(), links.capture());

        assertThat(links.getAllValues().get(0)).startsWith("https://shop.acme.test/");   // derived from the domain
        assertThat(links.getAllValues().get(1)).startsWith("https://store.globex.example/"); // configured
    }
}
