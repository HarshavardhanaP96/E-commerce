package com.sixpack.it;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AdminAgreementIntegrationTest extends IntegrationTest {

    @Test
    void anAdminIsAskedForTheAgreementAlongsideTermsAndPrivacy() throws Exception {
        String token = adminTokenWithoutAgreement();
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending", containsInAnyOrder("terms", "privacy", "admin_aup")));
    }

    @Test
    void adminEndpointsAreRefusedUntilTheAgreementIsAccepted() throws Exception {
        String token = adminTokenWithoutAgreement();

        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("acceptable-use")));

        // The dialog's own endpoints must stay reachable, or the admin could never accept.
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/legal/versions")).andExpect(status().isOk());

        decide(token, "admin_aup", true).andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    @Test
    void aNewAgreementVersionBlocksAdminsAgain() throws Exception {
        String token = adminToken();
        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(token))).andExpect(status().isOk());

        mongo.getCollection("consents").updateMany(new Document("purpose", "admin_aup"),
                new Document("$set", new Document("policyVersion", "2020-01-01")));

        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                // The seeded admin never accepted Terms or Privacy either, so they are still pending too.
                .andExpect(jsonPath("$.pending", containsInAnyOrder("terms", "privacy", "admin_aup")));
    }

    @Test
    void customersAreNeverAskedAndCannotAcceptIt() throws Exception {
        String token = register("customer-aup@test.dev");
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.pending", hasSize(0)));
        decide(token, "admin_aup", true).andExpect(status().isBadRequest());
    }

    @Test
    void theAgreementCannotBeWithdrawn() throws Exception {
        String token = adminToken();
        decide(token, "admin_aup", false).andExpect(status().isBadRequest());
    }

    @Test
    void theAgreementVersionIsPublic() throws Exception {
        mvc.perform(get("/api/v1/legal/versions"))
                .andExpect(jsonPath("$.versions.admin_aup").value("2026-10-18"));
    }

    private ResultActions decide(String token, String purpose, boolean granted) throws Exception {
        return mvc.perform(post("/api/v1/consent").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("purpose", purpose, "granted", granted, "source", "reprompt"))));
    }
}
