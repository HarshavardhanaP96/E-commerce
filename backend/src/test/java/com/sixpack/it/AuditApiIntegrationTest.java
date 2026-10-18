package com.sixpack.it;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.FieldChange;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class AuditApiIntegrationTest extends IntegrationTest {

    @Autowired
    AuditTrail audit;

    private void seed(AuditAction action, String type, String id, String actor) {
        audit.record(AuditEvent.of(action).on(type, id).by(actor, "ADMIN")
                .changing(List.of(FieldChange.of("price", 1.0, 2.0))));
    }

    private ResultActions search(String token, String... params) throws Exception {
        var request = get("/api/v1/admin/audit").header("Authorization", bearer(token));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    @Test
    void onlyAdminsCanReadTheLog() throws Exception {
        mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isUnauthorized());
        search(register("curious@test.dev")).andExpect(status().isForbidden());
        search(adminToken()).andExpect(status().isOk());
    }

    @Test
    void listsNewestFirstAndFiltersByEveryField() throws Exception {
        String admin = adminToken();
        seed(AuditAction.PRODUCT_CREATED, "product", "p1", "a1");
        seed(AuditAction.PRODUCT_UPDATED, "product", "p1", "a1");
        seed(AuditAction.ORDER_PLACED, "order", "o1", "a2");

        search(admin, "entityType", "product").andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].action").value("PRODUCT_UPDATED"));
        search(admin, "entityType", "product", "entityId", "p1", "action", "product_created")
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].changes[0].field").value("price"))
                .andExpect(jsonPath("$.entries[0].changes[0].after").value(2.0));
        search(admin, "actorId", "a2").andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].entityId").value("o1"));
        search(admin, "outcome", "FAILURE").andExpect(jsonPath("$.entries", hasSize(0)));
        search(admin, "action", "ORDER_PLACED", "outcome", "success")
                .andExpect(jsonPath("$.entries", hasSize(1)));
    }

    @Test
    void filtersByTimeRange() throws Exception {
        String admin = adminToken();
        seed(AuditAction.PRODUCT_CREATED, "product", "p1", "a1");

        search(admin, "from", Instant.now().minusSeconds(3600).toString(), "action", "PRODUCT_CREATED")
                .andExpect(jsonPath("$.entries", hasSize(1)));
        search(admin, "from", Instant.now().plusSeconds(3600).toString(), "action", "PRODUCT_CREATED")
                .andExpect(jsonPath("$.entries", hasSize(0)));
        search(admin, "to", Instant.now().minusSeconds(3600).toString(), "action", "PRODUCT_CREATED")
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    void pagesInHundreds() throws Exception {
        String admin = adminToken();
        for (int i = 0; i < 105; i++) {
            seed(AuditAction.PRODUCT_UPDATED, "product", "p" + i, "a1");
        }

        search(admin, "action", "PRODUCT_UPDATED").andExpect(jsonPath("$.entries", hasSize(100)))
                .andExpect(jsonPath("$.totalPage").value(2));
        search(admin, "action", "PRODUCT_UPDATED", "page", "2").andExpect(jsonPath("$.entries", hasSize(5)));
        search(admin, "action", "PRODUCT_UPDATED", "page", "9").andExpect(jsonPath("$.entries", hasSize(0)));
        search(admin, "action", "PRODUCT_UPDATED", "page", "0").andExpect(jsonPath("$.entries", hasSize(100)));
    }

    @Test
    void badFiltersAreRejectedNotCrashed() throws Exception {
        String admin = adminToken();
        search(admin, "action", "NOT_A_THING").andExpect(status().isBadRequest());
        search(admin, "outcome", "MAYBE").andExpect(status().isBadRequest());
        search(admin, "from", "yesterday").andExpect(status().isBadRequest());
        search(admin, "page", "abc").andExpect(status().isBadRequest());
    }

    @Test
    void neverExposesHashesOrIpEvidence() throws Exception {
        String admin = adminToken();
        audit.record(AuditEvent.of(AuditAction.LOGIN_FAILED).failed().aboutSubject("x@test.dev"));

        String body = search(admin, "action", "LOGIN_FAILED").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("ipHash", "subjectHash", "userAgent");
    }
}
