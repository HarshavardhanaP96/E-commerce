package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.application.TenantTrials;
import com.sixpack.tenant.domain.TenantStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** Trial stores run until their trial ends, then are suspended; a store that leaves is marked cancelled. */
@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123",
        "app.tenant.trial-length=14d"})
class TenantStatusLifecycleIntegrationTest extends TenantIntegrationTest {

    private static final String API = "/api/v1/platform/tenants";

    @Autowired
    TenantTrials trials;

    private String platformToken() throws Exception {
        return body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private JsonNode create(boolean trial) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("slug", "acme", "name", "Acme Shop",
                "domains", List.of("shop.acme.test")));
        if (trial) {
            body.put("trial", true);
        }
        return body(mvc.perform(post(API).header("Authorization", bearer(platformToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn()).get("tenant");
    }

    private ResultActions platform(String id, String action) throws Exception {
        return mvc.perform(post(API + "/" + id + "/" + action).header("Authorization", bearer(platformToken())));
    }

    private ResultActions storefront() throws Exception {
        return mvc.perform(get("/api/v1/product/latest").with(host("shop.acme.test")));
    }

    private TenantStatus statusOf(String id) {
        return tenantRepository.findById(id).orElseThrow().status();
    }

    @Test
    void aTrialStoreServesItsStorefrontUntilTheTrialEnds() throws Exception {
        JsonNode acme = create(true);

        assertThat(acme.get("status").asText()).isEqualTo("TRIAL");
        assertThat(Instant.parse(acme.get("trialEndsAt").asText()))
                .isCloseTo(Instant.now().plus(Duration.ofDays(14)), within(1, ChronoUnit.MINUTES));
        storefront().andExpect(status().isOk());

        trials.expireDue(Instant.now().plus(Duration.ofDays(13)));
        assertThat(statusOf(acme.get("id").asText())).isEqualTo(TenantStatus.TRIAL);
    }

    @Test
    void anExpiredTrialIsSuspendedBySystemAndOnRecord() throws Exception {
        String id = create(true).get("id").asText();

        trials.expireDue(Instant.now().plus(Duration.ofDays(15)));

        assertThat(statusOf(id)).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(tenantRepository.findById(id).orElseThrow().trialEndsAt()).isNull();
        tenantRegistry.refresh();
        storefront().andExpect(status().isForbidden());
        Document entry = mongo.getCollection("audit_log").find(new Document("tenantId", id)
                .append("action", "TENANT_STATUS_CHANGED")).first();
        assertThat(entry).isNotNull();
        assertThat(entry.getString("actorRole")).isEqualTo("SYSTEM");
    }

    @Test
    void activatingATrialEndsIt() throws Exception {
        String id = create(true).get("id").asText();

        platform(id, "activate").andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tenant.trialEndsAt").doesNotExist());

        trials.expireDue(Instant.now().plus(Duration.ofDays(30)));
        assertThat(statusOf(id)).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void storesStartActiveUnlessATrialIsAskedFor() throws Exception {
        JsonNode acme = create(false);

        assertThat(acme.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(acme.get("trialEndsAt").isNull()).isTrue();
    }

    @Test
    void aCancelledStoreIsClosedAndCanBeReactivatedOrOffboarded() throws Exception {
        String id = create(true).get("id").asText();

        platform(id, "cancel").andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.status").value("CANCELLED"))
                .andExpect(jsonPath("$.tenant.trialEndsAt").doesNotExist());
        storefront().andExpect(status().isForbidden());

        platform(id, "activate").andExpect(status().isOk());
        storefront().andExpect(status().isOk());
        platform(id, "cancel").andExpect(status().isOk());
        platform(id, "offboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.status").value("SCHEDULED_FOR_DELETION"));
    }

    @Test
    void theDefaultStoreCannotBeCancelledNorAStoreAlreadyLeaving() throws Exception {
        platform(tenantRepository.findBySlug("default").orElseThrow().id(), "cancel")
                .andExpect(status().isBadRequest());
        String id = create(false).get("id").asText();
        platform(id, "offboard").andExpect(status().isOk());
        platform(id, "cancel").andExpect(status().isConflict());
    }
}
