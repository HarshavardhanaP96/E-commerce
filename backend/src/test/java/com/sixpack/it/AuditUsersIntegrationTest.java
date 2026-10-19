package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.user.domain.PasswordResetToken;
import com.sixpack.user.domain.PasswordResetTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AuditUsersIntegrationTest extends IntegrationTest {

    @Autowired
    PasswordResetTokenRepository resetTokens;

    private String wholeLog() {
        List<String> docs = new ArrayList<>();
        mongo.getCollection("audit_log").find().forEach(d -> docs.add(d.toJson()));
        return String.join("\n", docs);
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token, Object body) throws Exception {
        if (token != null) {
            request = request.header("Authorization", bearer(token));
        }
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static final Map<String, Object> ADDRESS = Map.of("name", "Asha Rao", "phone", "9876543210",
            "line1", "12 MG Road", "city", "Bengaluru", "state", "Karnataka", "country", "India",
            "pinCode", "560001", "isDefault", true);

    @Test
    void registrationAndLoginAreAuditedWithoutTheEmail() throws Exception {
        String token = register("reg-audit@test.dev");
        String id = userId(token);

        Document registered = auditEntries("USER_REGISTERED").get(0);
        assertThat(registered.getString("actorId")).isEqualTo(id);
        assertThat(registered.getString("actorRole")).isEqualTo("USER");
        assertThat(registered.getString("entityId")).isEqualTo(id);

        json(post("/api/v1/user/login"), null, Map.of("email", "reg-audit@test.dev", "password", "password123"))
                .andExpect(status().isOk());
        Document login = auditEntries("LOGIN_SUCCEEDED").get(0);
        assertThat(login.getString("actorId")).isEqualTo(id);
        assertThat(login.getString("outcome")).isEqualTo("SUCCESS");

        assertThat(wholeLog()).doesNotContain("reg-audit@test.dev", "Test User", "password123");
    }

    @Test
    void aFailedLoginIsAuditedAsAnonymousWithOnlyAHashOfTheEmail() throws Exception {
        register("target@test.dev");
        json(post("/api/v1/user/login"), null, Map.of("email", "Target@test.dev", "password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        json(post("/api/v1/user/login"), null, Map.of("email", "nobody@test.dev", "password", "wrong-password"))
                .andExpect(status().isUnauthorized());

        List<Document> failures = auditEntries("LOGIN_FAILED");
        assertThat(failures).hasSize(2).allSatisfy(f -> {
            assertThat(f.getString("outcome")).isEqualTo("FAILURE");
            assertThat(f.getString("actorRole")).isEqualTo("ANONYMOUS");
            assertThat(f.getString("actorId")).isNull();
            assertThat(f.getString("subjectHash")).hasSize(64);
        });
        // The unknown email gets no entity; the known one is tied to the account being attacked.
        assertThat(failures.stream().filter(f -> f.getString("entityId") != null)).hasSize(1);
        assertThat(wholeLog().toLowerCase()).doesNotContain("target@test.dev", "nobody@test.dev");
    }

    @Test
    void adminActionsOnUsersAreAudited() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        String customer = register("managed@test.dev");
        String customerId = userId(customer);

        json(put("/api/v1/user/" + customerId), admin, Map.of("status", "suspended")).andExpect(status().isOk());
        Document status = auditEntries("USER_STATUS_CHANGED").get(0);
        assertThat(status.getString("actorId")).isEqualTo(adminId);
        assertThat(status.getString("entityId")).isEqualTo(customerId);
        assertThat(changeOf(status, "status").get("before")).isEqualTo("ACTIVE");
        assertThat(changeOf(status, "status").get("after")).isEqualTo("SUSPENDED");

        json(post("/api/v1/user/login"), null, Map.of("email", "managed@test.dev", "password", "password123"))
                .andExpect(status().isForbidden());
        assertThat(auditEntries("LOGIN_BLOCKED_SUSPENDED").get(0).getString("entityId")).isEqualTo(customerId);

        json(put("/api/v1/user/" + customerId), admin, Map.of("role", "admin")).andExpect(status().isOk());
        Document role = auditEntries("USER_ROLE_CHANGED").get(0);
        assertThat(changeOf(role, "role").get("before")).isEqualTo("USER");
        assertThat(changeOf(role, "role").get("after")).isEqualTo("ADMIN");

        mvc.perform(delete("/api/v1/user/" + customerId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Document erased = auditEntries("USER_ERASED").get(0);
        assertThat(erased.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(erased.getString("entityId")).isEqualTo(customerId);
    }

    @Test
    void profileEditsRecordThatPersonalFieldsChangedButNotTheirValues() throws Exception {
        String token = register("profile-audit@test.dev");
        String id = userId(token);

        json(put("/api/v1/user/me"), token, Map.of("name", "Zed Zebra", "phone", "+919876501234",
                "locale", "en-IN")).andExpect(status().isOk());

        Document updated = auditEntries("PROFILE_UPDATED").get(0);
        assertThat(updated.getString("entityId")).isEqualTo(id);
        assertThat(changeOf(updated, "name").getBoolean("redacted")).isTrue();
        assertThat(changeOf(updated, "name").get("after")).isNull();
        assertThat(changeOf(updated, "phone").getBoolean("redacted")).isTrue();
        assertThat(changeOf(updated, "locale").getBoolean("redacted")).isFalse();
        assertThat(changeOf(updated, "locale").get("after")).isEqualTo("en-IN");
        assertThat(wholeLog()).doesNotContain("Zed Zebra", "919876501234");
    }

    @Test
    void addressChangesAreAuditedByIdOnly() throws Exception {
        String token = register("addr-audit@test.dev");

        String addressId = body(json(post("/api/v1/user/me/addresses"), token, ADDRESS)
                .andExpect(status().isCreated()).andReturn()).get("address").get("id").asText();
        assertThat(auditEntries("ADDRESS_ADDED").get(0).getString("entityId")).isEqualTo(addressId);

        json(put("/api/v1/user/me/addresses/" + addressId), token, ADDRESS).andExpect(status().isOk());
        assertThat(auditEntries("ADDRESS_UPDATED").get(0).getString("entityId")).isEqualTo(addressId);

        mvc.perform(delete("/api/v1/user/me/addresses/" + addressId).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertThat(auditEntries("ADDRESS_DELETED").get(0).getString("entityId")).isEqualTo(addressId);

        assertThat(wholeLog()).doesNotContain("12 MG Road", "560001", "9876543210", "Asha Rao");
    }

    @Test
    void passwordResetIsAudited() throws Exception {
        String token = register("reset-audit@test.dev");
        String id = userId(token);

        json(post("/api/v1/user/forgot-password"), null, Map.of("email", "reset-audit@test.dev"))
                .andExpect(status().isOk());
        json(post("/api/v1/user/forgot-password"), null, Map.of("email", "ghost@test.dev"))
                .andExpect(status().isOk());
        List<Document> requested = auditEntries("PASSWORD_RESET_REQUESTED");
        assertThat(requested).hasSize(2).allSatisfy(r -> assertThat(r.getString("subjectHash")).hasSize(64));
        assertThat(requested.stream().filter(r -> id.equals(r.getString("entityId")))).hasSize(1);

        String raw = "known-reset-token";
        resetTokens.save(new PasswordResetToken(null, id, sha256(raw), Instant.now().plusSeconds(600)));
        json(post("/api/v1/user/reset-password"), null, Map.of("token", raw, "password", "a-new-password-1"))
                .andExpect(status().isOk());
        Document completed = auditEntries("PASSWORD_RESET_COMPLETED").get(0);
        assertThat(completed.getString("actorId")).isEqualTo(id);
        assertThat(completed.getString("entityId")).isEqualTo(id);

        assertThat(wholeLog().toLowerCase()).doesNotContain("reset-audit@test.dev", "ghost@test.dev", raw,
                "a-new-password-1");
    }

    @Test
    void consentDecisionsAreAuditedButRegistrationConsentsAreNot() throws Exception {
        String token = register("consent-audit@test.dev");
        String id = userId(token);
        assertThat(auditEntries("CONSENT_CHANGED")).isEmpty(); // the ledger already records these

        json(post("/api/v1/consent"), token, Map.of("purpose", "marketing_email", "granted", true,
                "source", "settings")).andExpect(status().isOk());

        Document entry = auditEntries("CONSENT_CHANGED").get(0);
        assertThat(entry.getString("actorId")).isEqualTo(id);
        assertThat(changeOf(entry, "purpose").get("after")).isEqualTo("marketing_email");
        assertThat(changeOf(entry, "granted").get("after")).isEqualTo(true);
        assertThat(changeOf(entry, "version").get("after")).isEqualTo("2026-10-16");
    }

    @Test
    void acceptingTheAdminAgreementIsAudited() throws Exception {
        String admin = adminToken();
        Document entry = auditEntries("CONSENT_CHANGED").get(0);
        assertThat(entry.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(entry.getString("actorId")).isEqualTo(userId(admin));
        assertThat(changeOf(entry, "purpose").get("after")).isEqualTo("admin_aup");
        assertThat(changeOf(entry, "version").get("after")).isEqualTo("2026-10-18");
    }

    @Test
    void deletingYourOwnAccountLeavesNoPersonalValuesInTheLog() throws Exception {
        String token = register("erase-audit@test.dev");
        String id = userId(token);
        json(put("/api/v1/user/me"), token, Map.of("name", "Erasable Name", "phone", "+919000000001"))
                .andExpect(status().isOk());

        json(delete("/api/v1/user/me"), token, Map.of("password", "password123")).andExpect(status().isOk());

        Document deleted = auditEntries("ACCOUNT_DELETED_SELF").get(0);
        assertThat(deleted.getString("actorId")).isEqualTo(id);
        // The trail survives erasure, holding only the opaque id.
        assertThat(mongo.getCollection("audit_log").countDocuments(new Document("actorId", id)))
                .isGreaterThan(2);
        assertThat(wholeLog().toLowerCase()).doesNotContain("erase-audit@test.dev", "erasable name", "9000000001");
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
