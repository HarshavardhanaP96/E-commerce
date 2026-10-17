package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class UserProfileIntegrationTest extends IntegrationTest {

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                               String token, Object body) throws Exception {
        req.header("Authorization", bearer(token));
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    private Map<String, Object> addr(String label, boolean isDefault) {
        return Map.of("label", label, "name", "Asha", "phone", "9876543210", "line1", "12 MG Road",
                "city", "Pune", "state", "MH", "country", "India", "pinCode", "411001", "isDefault", isDefault);
    }

    private String addAddress(String token, String label, boolean isDefault) throws Exception {
        MvcResult res = send(post("/api/v1/user/me/addresses"), token, addr(label, isDefault))
                .andExpect(status().isCreated()).andReturn();
        return body(res).get("address").get("id").asText();
    }

    @Test
    void profileUpdateChangesOnlySentFields() throws Exception {
        String token = register("profile@test.dev");

        send(put("/api/v1/user/me"), token, Map.of("phone", "+919876543210", "locale", "en-IN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.phone").value("+919876543210"))
                .andExpect(jsonPath("$.user.locale").value("en-IN"))
                .andExpect(jsonPath("$.user.status").value("ACTIVE"));
        send(put("/api/v1/user/me"), token, Map.of("name", "New Name"))
                .andExpect(jsonPath("$.user.name").value("New Name"))
                .andExpect(jsonPath("$.user.phone").value("+919876543210"));
    }

    @Test
    void invalidProfileValuesAreBadRequest() throws Exception {
        String token = register("badprofile@test.dev");
        send(put("/api/v1/user/me"), token, Map.of("phone", "abc")).andExpect(status().isBadRequest());
        send(put("/api/v1/user/me"), token, Map.of("locale", "english")).andExpect(status().isBadRequest());
        send(put("/api/v1/user/me"), token, Map.of("name", "x".repeat(81))).andExpect(status().isBadRequest());
    }

    @Test
    void addressBookKeepsExactlyOneDefault() throws Exception {
        String token = register("book@test.dev");
        String first = addAddress(token, "Home", false);
        String second = addAddress(token, "Work", false);
        String third = addAddress(token, "Parents", true);

        send(get("/api/v1/user/me/addresses"), token, null)
                .andExpect(jsonPath("$.addresses.length()").value(3))
                .andExpect(jsonPath("$.addresses[0].isDefault").value(false))
                .andExpect(jsonPath("$.addresses[2].isDefault").value(true));

        send(delete("/api/v1/user/me/addresses/" + third), token, null).andExpect(status().isOk());
        send(get("/api/v1/user/me/addresses"), token, null)
                .andExpect(jsonPath("$.addresses.length()").value(2))
                .andExpect(jsonPath("$.addresses[0].id").value(first))
                .andExpect(jsonPath("$.addresses[0].isDefault").value(true))
                .andExpect(jsonPath("$.addresses[1].isDefault").value(false));

        send(put("/api/v1/user/me/addresses/" + second), token, addr("Work 2", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address.label").value("Work 2"))
                .andExpect(jsonPath("$.address.isDefault").value(true));
        send(get("/api/v1/user/me/addresses"), token, null)
                .andExpect(jsonPath("$.addresses[0].isDefault").value(false))
                .andExpect(jsonPath("$.addresses[1].isDefault").value(true));
    }

    @Test
    void eleventhAddressIsConflict() throws Exception {
        String token = register("many@test.dev");
        for (int i = 0; i < 10; i++) {
            addAddress(token, "A" + i, false);
        }
        send(post("/api/v1/user/me/addresses"), token, addr("A10", false)).andExpect(status().isConflict());
    }

    @Test
    void invalidAddressesUnknownIdsAndAnonymousAreRejected() throws Exception {
        String token = register("rules@test.dev");
        Map<String, Object> noLine1 = new java.util.HashMap<>(addr("X", false));
        noLine1.remove("line1");
        send(post("/api/v1/user/me/addresses"), token, noLine1).andExpect(status().isBadRequest());
        Map<String, Object> badPin = new java.util.HashMap<>(addr("X", false));
        badPin.put("pinCode", "12");
        send(post("/api/v1/user/me/addresses"), token, badPin).andExpect(status().isBadRequest());
        Map<String, Object> badPhone = new java.util.HashMap<>(addr("X", false));
        badPhone.put("phone", "call me");
        send(post("/api/v1/user/me/addresses"), token, badPhone).andExpect(status().isBadRequest());

        send(put("/api/v1/user/me/addresses/missing"), token, addr("X", false)).andExpect(status().isNotFound());
        send(delete("/api/v1/user/me/addresses/missing"), token, null).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/user/me/addresses")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/user/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usersNeverSeeEachOthersAddresses() throws Exception {
        String alice = register("alice@test.dev");
        String bob = register("bob@test.dev");
        String aliceAddress = addAddress(alice, "Home", true);

        send(get("/api/v1/user/me/addresses"), bob, null).andExpect(jsonPath("$.addresses.length()").value(0));
        send(delete("/api/v1/user/me/addresses/" + aliceAddress), bob, null).andExpect(status().isNotFound());
        send(get("/api/v1/user/me/addresses"), alice, null).andExpect(jsonPath("$.addresses.length()").value(1));
    }

    @Test
    void userViewDoesNotExposeAddresses() throws Exception {
        String token = register("view@test.dev");
        addAddress(token, "Home", true);
        send(get("/api/v1/user/me"), token, null).andExpect(jsonPath("$.user.addresses").doesNotExist());
    }

    @Test
    void legacyUserCanSaveAnAddress() throws Exception {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(new Document("_id", id).append("name", "Old")
                .append("email", "legacy@test.dev").append("password", encoder.encode("password123"))
                .append("photo", "").append("role", "user").append("dob", new java.util.Date(0)));
        MvcResult login = mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "legacy@test.dev",
                                "password", "password123"))))
                .andExpect(status().isOk()).andReturn();
        String token = body(login).get("token").asText();

        String addressId = addAddress(token, "Home", false);

        assertThat(users.findById(id.toHexString()).orElseThrow().details().addresses())
                .extracting(a -> a.id()).containsExactly(addressId);
    }
}
