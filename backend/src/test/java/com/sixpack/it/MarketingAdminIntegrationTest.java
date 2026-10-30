package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import java.time.LocalDate;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Admin → Marketing API: feature switch, the two cards, validation, disconnect, roles and audit. */
class MarketingAdminIntegrationTest extends MarketingIntegrationTest {

    Tenant acme;
    String admin;

    @BeforeEach
    void storeWithMarketing() throws Exception {
        acme = marketing(createTenant("acme", "Acme", "shop.acme.test"), true);
        admin = adminTokenFor(acme);
    }

    @Test
    void withTheFeatureOffEveryEndpointIsNotFound() throws Exception {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        String token = adminToken();

        list(def, token).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        save(def, token, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isNotFound());
        save(def, token, "google", browser("ga4MeasurementId", "<script>")).andExpect(status().isNotFound());
        disconnect(def, token, "google").andExpect(status().isNotFound());
        assertThat(rows(def)).isEmpty();
    }

    @Test
    void bothCardsStartNotConnectedWithGoogleFirst() throws Exception {
        JsonNode body = body(list(acme, admin).andExpect(status().isOk()).andReturn());

        assertThat(body.get("success").asBoolean()).isTrue();
        JsonNode cards = body.get("integrations");
        assertThat(cards).hasSize(2);

        JsonNode google = cards.get(0);
        assertThat(fieldNames(google)).containsExactly("provider", "status", "browser", "updatedAt");
        assertThat(google.get("provider").asText()).isEqualTo("google");
        assertThat(google.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(fieldNames(google.get("browser")))
                .containsExactly("ga4MeasurementId", "adsConversionId", "adsPurchaseLabel", "gtmContainerId");
        google.get("browser").forEach(v -> assertThat(v.isNull()).isTrue());
        assertThat(google.get("updatedAt").isNull()).isTrue();

        JsonNode meta = cards.get(1);
        assertThat(meta.get("provider").asText()).isEqualTo("meta");
        assertThat(meta.get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(fieldNames(meta.get("browser"))).containsExactly("pixelId");
        assertThat(meta.get("browser").get("pixelId").isNull()).isTrue();
        assertThat(meta.get("updatedAt").isNull()).isTrue();
    }

    @Test
    void savingGoogleIdsActivatesOnlyTheGoogleCard() throws Exception {
        JsonNode card = body(save(acme, admin, "google", browser(
                        "ga4MeasurementId", "  G-AB12CD3 ", "adsConversionId", "AW-123456789",
                        "adsPurchaseLabel", "purchase_1", "gtmContainerId", "", "pixelId", "1234567890"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn())
                .get("integration");

        assertThat(card.get("provider").asText()).isEqualTo("google");
        assertThat(card.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(fieldNames(card.get("browser")))
                .containsExactly("ga4MeasurementId", "adsConversionId", "adsPurchaseLabel", "gtmContainerId");
        assertThat(card.get("browser").get("ga4MeasurementId").asText()).isEqualTo("G-AB12CD3");
        assertThat(card.get("browser").get("adsPurchaseLabel").asText()).isEqualTo("purchase_1");
        assertThat(card.get("browser").get("gtmContainerId").isNull()).isTrue();
        String updatedAt = card.get("updatedAt").asText();
        assertThat(updatedAt).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z");

        list(acme, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.integrations[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.integrations[0].browser.adsConversionId").value("AW-123456789"))
                .andExpect(jsonPath("$.integrations[0].updatedAt").value(updatedAt))
                .andExpect(jsonPath("$.integrations[1].status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.integrations[1].browser.pixelId").value(nullValue()));
    }

    @Test
    void metaKeepsOnlyThePixelAndIgnoresGoogleFields() throws Exception {
        save(acme, admin, "meta", browser("pixelId", "1234567890", "ga4MeasurementId", "<script>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.provider").value("meta"))
                .andExpect(jsonPath("$.integration.status").value("ACTIVE"))
                .andExpect(jsonPath("$.integration.browser.pixelId").value("1234567890"));

        list(acme, admin).andExpect(jsonPath("$.integrations[0].status").value("NOT_CONNECTED"));
    }

    @Test
    void anInvalidIdIsRejectedNamingTheFieldAndNothingIsStored() throws Exception {
        save(acme, admin, "google", browser("ga4MeasurementId", "G-x\"><script>alert(1)</script>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("ga4MeasurementId: must look like G-XXXXXXX"));
        save(acme, admin, "meta", browser("pixelId", "123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("pixelId: must be 10-20 digits"));

        assertThat(rows(acme)).isEmpty();
        assertThat(auditEntries("INTEGRATION_UPDATED")).isEmpty();
    }

    @Test
    void aPurchaseLabelNeedsAConversionId() throws Exception {
        save(acme, admin, "google", browser("ga4MeasurementId", "G-AB12CD3", "adsPurchaseLabel", "purchase"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("adsPurchaseLabel: needs adsConversionId"));
        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void aBodyWithoutBrowserIsRejected() throws Exception {
        mvc.perform(put(BASE + "/google").with(host(hostOf(acme))).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("browser: is required"));
    }

    @Test
    void anUnknownProviderIsNotFound() throws Exception {
        save(acme, admin, "tiktok", browser("pixelId", "1234567890")).andExpect(status().isNotFound());
        save(acme, admin, "GOOGLE", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isNotFound());
        disconnect(acme, admin, "tiktok").andExpect(status().isNotFound());
        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void clearingEveryIdDisconnects() throws Exception {
        save(acme, admin, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isOk());

        save(acme, admin, "google", browser("ga4MeasurementId", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.integration.browser.ga4MeasurementId").value(nullValue()))
                .andExpect(jsonPath("$.integration.updatedAt").value(nullValue()));
        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void deleteDisconnectsAndIsSafeToRepeat() throws Exception {
        save(acme, admin, "meta", browser("pixelId", "1234567890")).andExpect(status().isOk());

        disconnect(acme, admin, "meta").andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.integration.provider").value("meta"))
                .andExpect(jsonPath("$.integration.status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.integration.browser.pixelId").value(nullValue()))
                .andExpect(jsonPath("$.integration.updatedAt").value(nullValue()));
        disconnect(acme, admin, "meta").andExpect(status().isOk());

        list(acme, admin).andExpect(jsonPath("$.integrations[1].status").value("NOT_CONNECTED"));
        assertThat(rows(acme)).isEmpty();
        assertThat(auditEntries("INTEGRATION_DISCONNECTED")).hasSize(1);
    }

    @Test
    void onlyAStoreAdminWhoAcceptedTheAgreementGetsIn() throws Exception {
        mvc.perform(get(BASE).with(host(hostOf(acme)))).andExpect(status().isUnauthorized());

        String shopper = registerOn(acme, "shopper@acme.test");
        list(acme, shopper).andExpect(status().isForbidden());
        save(acme, shopper, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isForbidden());
        disconnect(acme, shopper, "google").andExpect(status().isForbidden());

        String email = "fresh-admin@acme.test";
        TenantContext.runAs(ref(acme), () -> users.save(new User(null, "Fresh Admin", email,
                encoder.encode("admin-password-123"), "", Role.ADMIN, Gender.MALE, LocalDate.of(1990, 1, 1), null)));
        String unaccepted = loginOn(acme, email, "admin-password-123");
        list(acme, unaccepted).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Please accept the admin acceptable-use agreement to continue"));

        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void savesAndDisconnectsAreAuditedWithActorEntityAndDiff() throws Exception {
        String adminId = TenantContext.callAs(ref(acme),
                () -> users.findByEmail("admin@acme.test").orElseThrow().id());

        save(acme, admin, "google", browser("ga4MeasurementId", "G-AB12CD3", "adsConversionId", "AW-123456789"))
                .andExpect(status().isOk());
        save(acme, admin, "google", browser("ga4MeasurementId", "G-NEW12345", "adsConversionId", "AW-123456789"))
                .andExpect(status().isOk());
        disconnect(acme, admin, "google").andExpect(status().isOk());

        List<Document> updates = auditEntries("INTEGRATION_UPDATED");
        assertThat(updates).hasSize(2);

        Document latest = updates.get(0);
        assertThat(latest.getString("tenantId")).isEqualTo(acme.id());
        assertThat(latest.getString("actorId")).isEqualTo(adminId);
        assertThat(latest.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(latest.getString("entityType")).isEqualTo("marketing_integration");
        assertThat(latest.getString("entityId")).isEqualTo("google");
        assertThat(latest.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("ga4MeasurementId");
        assertThat(changeOf(latest, "ga4MeasurementId").get("before")).isEqualTo("G-AB12CD3");
        assertThat(changeOf(latest, "ga4MeasurementId").get("after")).isEqualTo("G-NEW12345");

        Document first = updates.get(1);
        assertThat(changeOf(first, "adsConversionId").get("before")).isNull();
        assertThat(changeOf(first, "adsConversionId").get("after")).isEqualTo("AW-123456789");

        Document gone = auditEntries("INTEGRATION_DISCONNECTED").get(0);
        assertThat(gone.getString("actorId")).isEqualTo(adminId);
        assertThat(gone.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(gone.getString("entityType")).isEqualTo("marketing_integration");
        assertThat(gone.getString("entityId")).isEqualTo("google");
        assertThat(changeOf(gone, "ga4MeasurementId").get("before")).isEqualTo("G-NEW12345");
        assertThat(changeOf(gone, "ga4MeasurementId").get("after")).isNull();
    }
}
