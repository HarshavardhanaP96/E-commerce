package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** PUT/GET of server settings: write-only secret, validation, ERROR status, audit without the secret. */
class MarketingServerSettingsIntegrationTest extends MarketingServerIntegrationTest {

    static final String TOKEN = "fake-meta-token-aaaaaaaa";

    Tenant acme;
    String admin;

    @BeforeEach
    void storeWithAPixel() throws Exception {
        acme = marketing(createTenant("acme", "Acme", "shop.acme.test"), true);
        admin = adminTokenFor(acme);
        save(acme, admin, "meta", browser("pixelId", "123456789012345")).andExpect(status().isOk());
    }

    private JsonNode metaCard() throws Exception {
        return body(list(acme, admin).andExpect(status().isOk()).andReturn()).get("integrations").get(1);
    }

    @Test
    void aSecretIsStoredEncryptedAndNeverReturned() throws Exception {
        String put = saveServer(acme, admin, "meta", server(true, TOKEN, "TEST123")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(put).doesNotContain(TOKEN);
        JsonNode card = json.readTree(put).get("integration");
        assertThat(card.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.convertValue(card.get("server"), Map.class)).isEqualTo(Map.of("enabled", true,
                "hasSecret", true, "testEventCode", "TEST123", "available", true));
        assertThat(card.get("lastError").isNull()).isTrue();
        assertThat(card.get("deliveries")).isEmpty();

        Document row = rows(acme).get(0);
        Document sealed = row.get("secrets", Document.class).get("server", Document.class);
        assertThat(sealed.getString("keyId")).isEqualTo("k1");
        assertThat(sealed.getString("ciphertext")).isNotBlank();
        assertThat(row.toJson()).doesNotContain(TOKEN);
        assertThat(list(acme, admin).andReturn().getResponse().getContentAsString()).doesNotContain(TOKEN);
    }

    @Test
    void aPhaseOneSaveKeepsTheServerSettingsAndAServerSaveKeepsTheIds() throws Exception {
        saveServer(acme, admin, "meta", server(true, TOKEN, "TEST123")).andExpect(status().isOk());
        String before = rows(acme).get(0).get("secrets", Document.class).toJson();

        save(acme, admin, "meta", browser("pixelId", "999999999999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.server.enabled").value(true))
                .andExpect(jsonPath("$.integration.server.hasSecret").value(true));
        assertThat(rows(acme).get(0).get("secrets", Document.class).toJson()).isEqualTo(before);

        saveServer(acme, admin, "meta", server(false, null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.browser.pixelId").value("999999999999"))
                .andExpect(jsonPath("$.integration.server.enabled").value(false))
                .andExpect(jsonPath("$.integration.server.hasSecret").value(true))
                .andExpect(jsonPath("$.integration.server.testEventCode").isEmpty());
    }

    @Test
    void turningOnNeedsTheIdAndASecretAndBadValuesAreRefusedNamingTheField() throws Exception {
        Tenant bare = marketing(createTenant("bare", "Bare", "shop.bare.test"), true);
        String bareAdmin = adminTokenFor(bare);
        saveServer(bare, bareAdmin, "meta", server(true, TOKEN, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server: save the Meta Pixel ID first"));
        saveServer(bare, bareAdmin, "google", server(false, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server: save the GA4 measurement ID first"));
        assertThat(rows(bare)).isEmpty();

        saveServer(acme, admin, "meta", server(true, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.secret: is required to turn on server events"));
        saveServer(acme, admin, "meta", server(true, "short", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.secret: use 16-512 letters, digits, _ or -"));
        saveServer(acme, admin, "meta", server(false, TOKEN, "TE ST")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.testEventCode: use 1-32 letters or digits"));
        assertThat(rows(acme).get(0)).doesNotContainKey("secrets");
        assertThat(auditEntries("INTEGRATION_UPDATED")).hasSize(1); // only the pixel save
    }

    @Test
    void googleIgnoresATestEventCode() throws Exception {
        save(acme, admin, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isOk());
        saveServer(acme, admin, "google", server(true, "fake-ga4-secret-aaaaaaa", "TEST123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.server.testEventCode").isEmpty())
                .andExpect(jsonPath("$.integration.server.enabled").value(true));
    }

    @Test
    void aFailingCardShowsErrorAndANewSecretClearsIt() throws Exception {
        saveServer(acme, admin, "meta", server(true, TOKEN, null)).andExpect(status().isOk());
        mongo.getCollection("marketing_integrations").updateOne(new Document("tenantId", acme.id()),
                new Document("$set", new Document("lastError", new Document("at", Date.from(Instant.now()))
                        .append("code", "AUTH_FAILED").append("message", "Meta rejected the saved secret."))));

        JsonNode failing = metaCard();
        assertThat(failing.get("status").asText()).isEqualTo("ERROR");
        assertThat(failing.get("lastError").get("code").asText()).isEqualTo("AUTH_FAILED");
        assertThat(failing.get("lastError").get("message").asText()).isEqualTo("Meta rejected the saved secret.");

        saveServer(acme, admin, "meta", server(true, "fake-meta-token-bbbbbbbb", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.status").value("ACTIVE"))
                .andExpect(jsonPath("$.integration.lastError").isEmpty());
        assertThat(rows(acme).get(0)).doesNotContainKey("lastError");
    }

    @Test
    void clearingTheIdsRemovesTheSecretToo() throws Exception {
        saveServer(acme, admin, "meta", server(true, TOKEN, null)).andExpect(status().isOk());
        save(acme, admin, "meta", browser("pixelId", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.integration.status").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.integration.server.hasSecret").value(false));
        assertThat(rows(acme)).isEmpty();
    }

    @Test
    void serverChangesAreAuditedWithoutTheSecret() throws Exception {
        saveServer(acme, admin, "meta", server(true, TOKEN, "TEST123")).andExpect(status().isOk());
        Document entry = auditEntries("INTEGRATION_UPDATED").get(0);
        assertThat(entry.toJson()).doesNotContain(TOKEN);
        assertThat(entry.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("serverEnabled", "testEventCode", "secret");
        assertThat(changeOf(entry, "serverEnabled").get("before")).isEqualTo(false);
        assertThat(changeOf(entry, "serverEnabled").get("after")).isEqualTo(true);
        assertThat(changeOf(entry, "testEventCode").get("after")).isEqualTo("TEST123");
        assertThat(changeOf(entry, "secret").getBoolean("redacted")).isTrue();
        assertThat(changeOf(entry, "secret")).doesNotContainKeys("before", "after");
    }

    @Test
    void theCardListsTheNewestTenDeliveriesWithoutPersonalData() throws Exception {
        for (int i = 0; i < 12; i++) {
            mongo.getCollection("marketing_outbox").insertOne(new Document("tenantId", acme.id())
                    .append("provider", "META").append("eventId", "o" + i).append("eventName", "purchase")
                    .append("orderId", "o" + i).append("orderNumber", "ORD-" + i).append("status", "SENT")
                    .append("attempts", 1).append("createdAt", Date.from(Instant.now().plusSeconds(i)))
                    .append("updatedAt", Date.from(Instant.now())).append("nextAttemptAt", Date.from(Instant.now())));
        }
        JsonNode deliveries = metaCard().get("deliveries");
        assertThat(deliveries).hasSize(10);
        assertThat(deliveries.get(0).get("eventId").asText()).isEqualTo("o11");
        assertThat(fieldNames(deliveries.get(0))).containsExactly("eventId", "orderNumber", "eventName", "status",
                "attempts", "createdAt", "updatedAt", "nextAttemptAt", "error");
        assertThat(deliveries.get(0).get("nextAttemptAt").isNull()).isTrue(); // only PENDING rows show it
    }

    @Test
    void anEmptyBodyIsStillRejectedAsInPhaseOne() throws Exception {
        mvc.perform(put(BASE + "/meta").with(host(hostOf(acme))).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("browser: is required"));
        assertThat(List.of(rows(acme).get(0).keySet())).isNotEmpty();
    }
}
