package com.sixpack.marketing.infra.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.MarketingDestination.Credentials;
import com.sixpack.marketing.domain.MarketingDestination.TestVerdict;
import com.sixpack.marketing.domain.Provider;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class MetaConversionsApiTest {

    private static final String TOKEN = "fake-meta-token-aaaaaaaa";
    private static final String EVENTS = "https://graph.test/v25.0/123456789012345/events";
    private final Credentials creds = new Credentials("123456789012345", TOKEN);

    private MockRestServiceServer server;
    private MetaConversionsApi meta;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://graph.test");
        server = MockRestServiceServer.bindTo(builder).build();
        meta = new MetaConversionsApi(builder.build(), "v25.0");
    }

    static Conversion purchase() {
        return new Conversion("6720f0c2a1b2c3d4e5f60718", Instant.parse("2026-10-31T10:00:00Z"), "INR", 790.0, 90.0,
                200.0, null, List.of(new Conversion.Item("p1", "Lamp", "Red", 500.0, 1)),
                new Conversion.Identity("f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79", null, null,
                        "fb.1.1730000000000.1098115397", null, null, "Mozilla/5.0 test",
                        "https://shop.acme.test/shipping", true));
    }

    @Test
    void sendsThePurchaseWithTheOrderIdAsEventIdAndTheTokenInTheBody() {
        server.expect(requestTo(EVENTS)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.access_token").value(TOKEN))
                .andExpect(jsonPath("$.test_event_code").doesNotExist())
                .andExpect(jsonPath("$.data[0].event_name").value("Purchase"))
                .andExpect(jsonPath("$.data[0].event_id").value("6720f0c2a1b2c3d4e5f60718"))
                .andExpect(jsonPath("$.data[0].event_time").value(1793440800))
                .andExpect(jsonPath("$.data[0].action_source").value("website"))
                .andExpect(jsonPath("$.data[0].event_source_url").value("https://shop.acme.test/shipping"))
                .andExpect(jsonPath("$.data[0].user_data.em[0]")
                        .value("f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79"))
                .andExpect(jsonPath("$.data[0].user_data.ph").doesNotExist())
                .andExpect(jsonPath("$.data[0].user_data.fbp").value("fb.1.1730000000000.1098115397"))
                .andExpect(jsonPath("$.data[0].user_data.client_user_agent").value("Mozilla/5.0 test"))
                .andExpect(jsonPath("$.data[0].custom_data.currency").value("INR"))
                .andExpect(jsonPath("$.data[0].custom_data.value").value(790.0))
                .andExpect(jsonPath("$.data[0].custom_data.order_id").value("6720f0c2a1b2c3d4e5f60718"))
                .andExpect(jsonPath("$.data[0].custom_data.contents[0].id").value("p1"))
                .andExpect(jsonPath("$.data[0].custom_data.num_items").value(1))
                .andRespond(withSuccess("{\"events_received\":1,\"fbtrace_id\":\"x\"}", MediaType.APPLICATION_JSON));
        meta.send(creds, purchase());
        server.verify();
    }

    private DeliveryException failure(ResponseCreator response) {
        server.reset();
        server.expect(requestTo(EVENTS)).andRespond(response);
        try {
            meta.send(creds, purchase());
        } catch (DeliveryException e) {
            return e;
        }
        throw new AssertionError("expected a DeliveryException");
    }

    private static ResponseCreator metaError(int code, String message) {
        return withBadRequest().contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"" + message
                + "\",\"type\":\"OAuthException\",\"code\":" + code + ",\"fbtrace_id\":\"t\"}}");
    }

    @Test
    void outagesThrottlingAndTimeoutsAreRetryable() {
        assertThat(failure(withServerError()).code()).isEqualTo(DeliveryCode.PROVIDER_DOWN);
        DeliveryException throttled = failure(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "120"));
        assertThat(throttled.code()).isEqualTo(DeliveryCode.RATE_LIMITED);
        assertThat(throttled.retryAfter()).isEqualTo(Duration.ofSeconds(120));
        assertThat(failure(metaError(17, "User request limit reached")).code()).isEqualTo(DeliveryCode.RATE_LIMITED);
        assertThat(failure(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"Temporary\",\"code\":2,\"is_transient\":true}}")).code())
                .isEqualTo(DeliveryCode.PROVIDER_DOWN);
        assertThat(failure(withException(new SocketTimeoutException("Read timed out"))).code())
                .isEqualTo(DeliveryCode.TIMEOUT);
        assertThat(DeliveryCode.TIMEOUT.retryable()).isTrue();
    }

    @Test
    void aBadTokenOrParameterFailsOnceWithAnOwnerMessageThatHidesTheToken() {
        DeliveryException auth = failure(metaError(190, "Invalid OAuth access token"));
        assertThat(auth.code()).isEqualTo(DeliveryCode.AUTH_FAILED);
        assertThat(auth.ownerMessage(Provider.META)).isEqualTo(
                "Meta rejected the saved secret. Create a new one and save it here.");
        assertThat(failure(withUnauthorizedRequest()).code()).isEqualTo(DeliveryCode.AUTH_FAILED);

        DeliveryException rejected = failure(metaError(100, "Invalid parameter near " + TOKEN));
        assertThat(rejected.code()).isEqualTo(DeliveryCode.REJECTED);
        assertThat(rejected.ownerMessage(Provider.META)).isEqualTo(
                "Meta rejected the event: Invalid parameter near [hidden]");
        assertThat(rejected.getMessage()).isEqualTo("REJECTED");
    }

    @Test
    void theTestEventUsesTheTestCodeAndReportsMetasAnswer() {
        server.expect(requestTo(EVENTS)).andExpect(jsonPath("$.test_event_code").value("TEST123"))
                .andExpect(jsonPath("$.access_token").value(TOKEN))
                .andExpect(jsonPath("$.data[0].event_name").value("Purchase"))
                .andRespond(withSuccess("{\"events_received\":1}", MediaType.APPLICATION_JSON));
        TestVerdict ok = meta.test(creds, "TEST123", "INR");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).contains("Test events");

        server.reset();
        server.expect(requestTo(EVENTS)).andRespond(metaError(190, "Invalid OAuth access token"));
        TestVerdict bad = meta.test(creds, "TEST123", "INR");
        assertThat(bad).isEqualTo(new TestVerdict(false, "AUTH_FAILED",
                "Meta rejected the saved secret. Create a new one and save it here."));
    }

    @Test
    void nothingLogsTheToken(CapturedOutput output) {
        failure(withServerError());
        failure(withException(new SocketTimeoutException("Read timed out")));
        failure(metaError(100, "Invalid parameter near " + TOKEN));
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }
}
