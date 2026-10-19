package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class UserStatusIntegrationTest extends IntegrationTest {

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions updateUser(String admin, String id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/user/" + id).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void suspendedUsersCannotLogInOrUseTheirSession() throws Exception {
        String admin = adminToken();
        String token = register("sus@test.dev");
        String id = userId(token);

        updateUser(admin, id, Map.of("status", "suspended")).andExpect(status().isOk());

        login("sus@test.dev", "password123").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        // Status is only revealed after the password is right.
        login("sus@test.dev", "wrong-password").andExpect(status().isUnauthorized());

        updateUser(admin, id, Map.of("status", "ACTIVE")).andExpect(status().isOk());
        login("sus@test.dev", "password123").andExpect(status().isOk());
    }

    @Test
    void suspendingAUserRevokesTheirAlreadyIssuedTokenOnEveryEndpoint() throws Exception {
        String admin = adminToken();
        String token = register("revoked@test.dev");
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token))).andExpect(status().isOk());

        updateUser(admin, userId(token), Map.of("status", "suspended")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your account is suspended"));
    }

    @Test
    void reactivatingAUserRestoresTheirExistingToken() throws Exception {
        String admin = adminToken();
        String token = register("back@test.dev");
        String id = userId(token);

        updateUser(admin, id, Map.of("status", "suspended")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        updateUser(admin, id, Map.of("status", "active")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    @Test
    void aSuspendedAdminLosesAdminAccess() throws Exception {
        String admin = adminToken();
        String otherId = userId(register("admin2@test.dev"));
        updateUser(admin, otherId, Map.of("role", "admin")).andExpect(status().isOk());
        // The role claim is fixed at login, so log in again to get an admin token.
        String adminTwo = body(login("admin2@test.dev", "password123").andReturn()).get("token").asText();
        mvc.perform(post("/api/v1/consent").header("Authorization", bearer(adminTwo))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("purpose", "admin_aup", "granted", true,
                        "source", "reprompt")))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(adminTwo))).andExpect(status().isOk());

        updateUser(admin, otherId, Map.of("status", "suspended")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(adminTwo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your account is suspended"));
    }

    @Test
    void aTokenForADeletedAccountIsRejectedAsUnauthenticated() throws Exception {
        String admin = adminToken();
        String token = register("gone@test.dev");
        String id = userId(token);

        mvc.perform(delete("/api/v1/user/" + id).header("Authorization", bearer(admin))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotSuspendThemselvesAndStatusMustBeValid() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);

        updateUser(admin, adminId, Map.of("status", "suspended")).andExpect(status().isBadRequest());
        updateUser(admin, adminId, Map.of("status", "banned")).andExpect(status().isBadRequest());
        updateUser(admin, adminId, Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void roleOnlyUpdateStillWorks() throws Exception {
        String admin = adminToken();
        String id = userId(register("role@test.dev"));

        updateUser(admin, id, Map.of("role", "admin")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/" + id).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.user.role").value("admin"))
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));
    }

    @Test
    void loginStampsLastLogin() throws Exception {
        String admin = adminToken();
        String id = userId(register("seen@test.dev"));
        mvc.perform(get("/api/v1/user/" + id).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.user.lastLoginAt").doesNotExist());

        login("seen@test.dev", "password123").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.lastLoginAt").exists());

        mvc.perform(get("/api/v1/user/" + id).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.user.lastLoginAt").exists());
    }
}
