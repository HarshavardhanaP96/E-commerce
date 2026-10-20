package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.user.domain.MailSender;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

class EmailVerificationIntegrationTest extends IntegrationTest {

    @MockitoBean
    MailSender mailSender;

    @AfterEach
    void resetMock() {
        reset(mailSender);
    }

    private String tokenSentTo(String email, int times) {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailSender, times(times)).sendEmailVerification(eq(email), anyString(), link.capture());
        String last = link.getAllValues().get(link.getAllValues().size() - 1);
        assertThat(last).contains("/verify-email?token=");
        return last.substring(last.indexOf("token=") + 6);
    }

    private ResultActions confirm(String token) throws Exception {
        return mvc.perform(post("/api/v1/user/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token))));
    }

    private ResultActions resend(String jwt) throws Exception {
        return mvc.perform(post("/api/v1/user/me/verify-email/resend").header("Authorization", bearer(jwt)));
    }

    private boolean verified(String jwt) throws Exception {
        return body(mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(jwt))).andReturn())
                .get("user").get("emailVerified").asBoolean();
    }

    @Test
    void registrationSendsALinkAndFollowingItVerifiesTheEmailOnce() throws Exception {
        String jwt = register("verify@test.dev");
        assertThat(verified(jwt)).isFalse();
        String token = tokenSentTo("verify@test.dev", 1);

        confirm(token).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Email verified"));

        assertThat(verified(jwt)).isTrue();
        confirm(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verification link is invalid or has expired"));
        assertThat(auditEntries("EMAIL_VERIFIED")).hasSize(1);
    }

    @Test
    void unknownAndExpiredLinksAreRefused() throws Exception {
        String jwt = register("expired@test.dev");
        String token = tokenSentTo("expired@test.dev", 1);
        confirm("not-a-real-token").andExpect(status().isBadRequest());

        mongo.getCollection("email_verification_tokens").updateMany(new Document(),
                new Document("$set", new Document("expiresAt", Date.from(Instant.now().minusSeconds(60)))));

        confirm(token).andExpect(status().isBadRequest());
        assertThat(verified(jwt)).isFalse();
    }

    @Test
    void resendingSendsAFreshLinkAndRetiresTheOldOne() throws Exception {
        String jwt = register("resend@test.dev");
        String first = tokenSentTo("resend@test.dev", 1);

        resend(jwt).andExpect(status().isOk());
        String second = tokenSentTo("resend@test.dev", 2);

        assertThat(second).isNotEqualTo(first);
        confirm(first).andExpect(status().isBadRequest());
        confirm(second).andExpect(status().isOk());
    }

    @Test
    void resendingToAVerifiedAccountSendsNothing() throws Exception {
        String jwt = register("done@test.dev");
        confirm(tokenSentTo("done@test.dev", 1)).andExpect(status().isOk());

        resend(jwt).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Your email is already verified"));

        verify(mailSender, times(1)).sendEmailVerification(anyString(), anyString(), anyString());
        mvc.perform(post("/api/v1/user/me/verify-email/resend")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMailFailureDoesNotStopRegistration() throws Exception {
        doThrow(new RuntimeException("smtp down")).when(mailSender)
                .sendEmailVerification(anyString(), anyString(), anyString());

        mvc.perform(multipart("/api/v1/user/register").param("name", "Test User").param("email", "nomail@test.dev")
                        .param("password", "password123").param("dob", "2000-05-01").param("acceptTerms", "true"))
                .andExpect(status().isCreated());

        assertThat(users.findByEmail("nomail@test.dev")).isPresent();
    }

    @Test
    void legacyAccountsCanAskForTheirFirstLink() throws Exception {
        String jwt = register("legacy@test.dev");
        mongo.getCollection("email_verification_tokens").deleteMany(new Document());
        reset(mailSender);

        resend(jwt).andExpect(status().isOk());

        confirm(tokenSentTo("legacy@test.dev", 1)).andExpect(status().isOk());
        assertThat(verified(jwt)).isTrue();
    }
}
