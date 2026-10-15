package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthorizationIntegrationTest extends IntegrationTest {

    @Test
    void anonymousCanBrowseButNotTransact() throws Exception {
        mvc.perform(get("/api/v1/product/latest")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/product/categories")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/product/all")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/order/my")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/v1/order/checkout").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/order/someid")).andExpect(status().isUnauthorized());
    }

    @Test
    void customersCannotReachAdminEndpoints() throws Exception {
        String user = bearer(register("customer@test.dev"));
        mvc.perform(get("/api/v1/user/all").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dashboard/stats").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/product/admin-products").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/product/abc").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/order/all").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/order/abc").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payment/coupon/all").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"coupon\":\"FREE\",\"amount\":100}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoutesWorkForAdmins() throws Exception {
        String admin = bearer(adminToken());
        mvc.perform(get("/api/v1/user/all").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/order/all").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payment/coupon/all").header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    void forgedOrTamperedTokensAreRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("sixpack").subject("attacker")
                .claim("role", "ADMIN").expirationTime(new Date(System.currentTimeMillis() + 60_000)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner("secret".repeat(8))); // the old fallback secret, padded
        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(jwt.serialize())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/user/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotDeleteThemselves() throws Exception {
        String token = adminToken();
        String id = userId(token);
        mvc.perform(delete("/api/v1/user/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }
}
