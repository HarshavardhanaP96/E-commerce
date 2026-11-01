package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** POST /{provider}/test and GET /{provider}/deliveries. */
class MarketingServerEventsIntegrationTest extends MarketingServerIntegrationTest {

    static final String TOKEN = "fake-meta-token-aaaaaaaa";

    Store acme;

    @BeforeEach
    void store() throws Exception {
        acme = configuredStore("acme", "123456789012345", TOKEN, "G-AB12CD3", "fake-ga4-secret-aaaaaaa");
        STUB.reset();
    }

    private ResultActions test(Tenant t, String token, String provider) throws Exception {
        return mvc.perform(post(BASE + "/" + provider + "/test").with(host(hostOf(t)))
                .header("Authorization", bearer(token)));
    }

    private ResultActions deliveries(Tenant t, String token, String provider, String page) throws Exception {
        var request = get(BASE + "/" + provider + "/deliveries").with(host(hostOf(t)))
                .header("Authorization", bearer(token));
        return mvc.perform(page == null ? request : request.param("page", page));
    }

    @Test
    void aMetaTestSendsTheTestCodeAndReportsTheVerdict() throws Exception {
        String response = test(acme.tenant(), acme.admin(), "meta").andExpect(status().isOk())
                .andExpect(jsonPath("$.test.ok").value(true))
                .andExpect(jsonPath("$.test.code").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(TOKEN);
        JsonNode sent = json.readTree(STUB.callsTo(META_EVENTS).get(0).body());
        assertThat(sent.get("test_event_code").asText()).isEqualTo("TEST123");
        assertThat(sent.get("access_token").asText()).isEqualTo(TOKEN);
        assertThat(outboxRows(acme.tenant())).isEmpty(); // a test never goes through the queue
        Document audit = auditEntries("INTEGRATION_TESTED").get(0);
        assertThat(audit.getString("outcome")).isEqualTo("SUCCESS");
        assertThat(audit.getString("entityId")).isEqualTo("meta");
    }

    @Test
    void aRefusedTestIsReportedWithoutChangingTheCard() throws Exception {
        STUB.reply(META_EVENTS, ProviderStub.Reply.of(400,
                "{\"error\":{\"message\":\"Invalid OAuth access token\",\"code\":190}}"));
        test(acme.tenant(), acme.admin(), "meta").andExpect(status().isOk())
                .andExpect(jsonPath("$.test.ok").value(false))
                .andExpect(jsonPath("$.test.code").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.test.message")
                        .value("Meta rejected the saved secret. Create a new one and save it here."));
        assertThat(auditEntries("INTEGRATION_TESTED").get(0).getString("outcome")).isEqualTo("FAILURE");
        list(acme.tenant(), acme.admin()).andExpect(jsonPath("$.integrations[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.integrations[1].lastError").isEmpty());
    }

    @Test
    void aGoogleTestUsesTheValidationServer() throws Exception {
        STUB.reply("/debug/", ProviderStub.Reply.of(200, "{\"validationMessages\":[{\"fieldPath\":\"events\","
                + "\"description\":\"Event at index: [0] has invalid name\",\"validationCode\":\"NAME_INVALID\"}]}"));
        test(acme.tenant(), acme.admin(), "google").andExpect(status().isOk())
                .andExpect(jsonPath("$.test.ok").value(false))
                .andExpect(jsonPath("$.test.code").value("REJECTED"))
                .andExpect(jsonPath("$.test.message").value("Google found a problem: Event at index: [0] has invalid name"));
        assertThat(STUB.callsTo("/debug/mp/collect")).hasSize(1);
        assertThat(STUB.callsTo("/mp/collect")).isEmpty();
    }

    @Test
    void aTestNeedsTheSecretAndForMetaATestCode() throws Exception {
        saveServer(acme.tenant(), acme.admin(), "meta", server(true, null, null)).andExpect(status().isOk());
        test(acme.tenant(), acme.admin(), "meta").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.testEventCode: set a test event code from Events Manager first"));

        Tenant bare = marketing(createTenant("bare", "Bare", "shop.bare.test"), true);
        String admin = adminTokenFor(bare);
        test(bare, admin, "google").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server: save the GA4 measurement ID first"));
        save(bare, admin, "google", browser("ga4MeasurementId", "G-AB12CD3")).andExpect(status().isOk());
        test(bare, admin, "google").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("server.secret: save the secret first"));
        assertThat(STUB.calls()).isEmpty();
    }

    @Test
    void theEleventhTestInAMinuteIsRateLimited() throws Exception {
        // The window is the clock minute: start early in one so all eleven calls land in the same window.
        long intoMinute = System.currentTimeMillis() % 60_000;
        if (intoMinute > 40_000) {
            Thread.sleep(60_000 - intoMinute + 50);
        }
        for (int i = 0; i < 10; i++) {
            test(acme.tenant(), acme.admin(), "google").andExpect(status().isOk());
        }
        test(acme.tenant(), acme.admin(), "google").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many test events, please wait a minute"));
    }

    @Test
    void anotherStoresCopiedSecretCannotBeRead() throws Exception {
        Tenant globex = marketing(createTenant("globex", "Globex", "shop.globex.test"), true);
        String admin = adminTokenFor(globex);
        save(globex, admin, "meta", browser("pixelId", "999999999999999")).andExpect(status().isOk());
        Document acmeSecrets = rows(acme.tenant()).stream().filter(r -> "META".equals(r.getString("provider")))
                .findFirst().orElseThrow().get("secrets", Document.class);
        mongo.getCollection("marketing_integrations").updateOne(new Document("tenantId", globex.id()),
                new Document("$set", new Document("secrets", acmeSecrets)
                        .append("server", new Document("enabled", true).append("testEventCode", "TEST123"))));

        test(globex, admin, "meta").andExpect(status().isOk())
                .andExpect(jsonPath("$.test.ok").value(false))
                .andExpect(jsonPath("$.test.code").value("SECRET_UNREADABLE"));
        assertThat(STUB.calls()).isEmpty();
    }

    @Test
    void theDeliveryLogIsPagedNewestFirstAndBoundToTheStore() throws Exception {
        for (int i = 0; i < 25; i++) {
            mongo.getCollection("marketing_outbox").insertOne(new Document("tenantId", acme.tenant().id())
                    .append("provider", "META").append("eventId", "o" + i).append("eventName", "purchase")
                    .append("orderId", "o" + i).append("orderNumber", "ORD-" + i).append("status", "PENDING")
                    .append("attempts", 1).append("error", "TIMEOUT")
                    .append("nextAttemptAt", Date.from(Instant.now().plusSeconds(60)))
                    .append("createdAt", Date.from(Instant.parse("2026-10-31T10:00:00Z").plusSeconds(i)))
                    .append("updatedAt", Date.from(Instant.now())));
        }
        JsonNode first = body(deliveries(acme.tenant(), acme.admin(), "meta", null).andExpect(status().isOk())
                .andReturn());
        assertThat(first.get("page").asInt()).isEqualTo(1);
        assertThat(first.get("totalPage").asInt()).isEqualTo(2);
        assertThat(first.get("deliveries")).hasSize(20);
        JsonNode newest = first.get("deliveries").get(0);
        assertThat(newest.get("eventId").asText()).isEqualTo("o24");
        assertThat(newest.get("nextAttemptAt").isNull()).isFalse();
        assertThat(newest.get("error").get("code").asText()).isEqualTo("TIMEOUT");
        assertThat(newest.get("error").get("message").asText()).isEqualTo("Meta did not answer in time. We will try again.");

        deliveries(acme.tenant(), acme.admin(), "meta", "2").andExpect(jsonPath("$.deliveries.length()").value(5));
        deliveries(acme.tenant(), acme.admin(), "meta", "9").andExpect(jsonPath("$.deliveries.length()").value(0));
        deliveries(acme.tenant(), acme.admin(), "meta", "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page: must be a positive number"));
        deliveries(acme.tenant(), acme.admin(), "meta", "x").andExpect(status().isBadRequest());

        Tenant globex = marketing(createTenant("globex", "Globex", "shop.globex.test"), true);
        deliveries(globex, adminTokenFor(globex), "meta", null).andExpect(jsonPath("$.deliveries.length()").value(0));
    }

    @Test
    void bothEndpointsAreNotFoundWithTheFeatureOffOrAnUnknownProvider() throws Exception {
        test(acme.tenant(), acme.admin(), "tiktok").andExpect(status().isNotFound());
        deliveries(acme.tenant(), acme.admin(), "tiktok", null).andExpect(status().isNotFound());
        marketing(acme.tenant(), false);
        test(acme.tenant(), acme.admin(), "meta").andExpect(status().isNotFound());
        deliveries(acme.tenant(), acme.admin(), "meta", null).andExpect(status().isNotFound());
        assertThat(STUB.calls()).isEmpty();
    }
}
