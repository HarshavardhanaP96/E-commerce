package com.sixpack.marketing.infra.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.MarketingDestination.Credentials;
import com.sixpack.marketing.domain.MarketingDestination.TestVerdict;
import java.net.SocketTimeoutException;
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
class Ga4MeasurementProtocolTest {

    private static final String SECRET = "fake-ga4-secret-aaaaaaa";
    private static final String COLLECT = "https://ga.test/mp/collect?measurement_id=G-AB12CD3&api_secret=" + SECRET;
    private static final String DEBUG = "https://ga.test/debug/mp/collect?measurement_id=G-AB12CD3&api_secret=" + SECRET;
    private final Credentials creds = new Credentials("G-AB12CD3", SECRET);

    private MockRestServiceServer server;
    private Ga4MeasurementProtocol ga4;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://ga.test");
        server = MockRestServiceServer.bindTo(builder).build();
        ga4 = new Ga4MeasurementProtocol(builder.build());
    }

    static Conversion purchase(String gaClientId, boolean adConsent) {
        return new Conversion("6720f0c2a1b2c3d4e5f60718", Instant.parse("2026-10-31T10:00:00Z"), "INR", 790.0, 90.0,
                200.0, "DIWALI", List.of(new Conversion.Item("p1", "Lamp", "Red", 500.0, 1)),
                new Conversion.Identity(null, null, null, null, null, gaClientId, null, null, adConsent));
    }

    @Test
    void sendsThePurchaseWithTheOrderIdAsTransactionIdAndTheBrowsersClientId() {
        server.expect(requestTo(COLLECT)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.client_id").value("1234567890.1730000000"))
                .andExpect(jsonPath("$.timestamp_micros").value(1793440800000000L))
                .andExpect(jsonPath("$.consent.ad_user_data").value("GRANTED"))
                .andExpect(jsonPath("$.consent.ad_personalization").value("GRANTED"))
                .andExpect(jsonPath("$.user_data").doesNotExist())
                .andExpect(jsonPath("$.events[0].name").value("purchase"))
                .andExpect(jsonPath("$.events[0].params.transaction_id").value("6720f0c2a1b2c3d4e5f60718"))
                .andExpect(jsonPath("$.events[0].params.currency").value("INR"))
                .andExpect(jsonPath("$.events[0].params.value").value(790.0))
                .andExpect(jsonPath("$.events[0].params.coupon").value("DIWALI"))
                .andExpect(jsonPath("$.events[0].params.items[0].item_id").value("p1"))
                .andExpect(jsonPath("$.events[0].params.items[0].item_variant").value("Red"))
                .andRespond(withNoContent());
        ga4.send(creds, purchase("1234567890.1730000000", true));
        server.verify();
    }

    @Test
    void withoutTheBrowsersIdAStableOneIsMadeAndAdConsentFollowsTheChoice() {
        server.expect(requestTo(COLLECT))
                .andExpect(jsonPath("$.client_id").value(org.hamcrest.Matchers.matchesPattern("^\\d+\\.1793440800$")))
                .andExpect(jsonPath("$.consent.ad_user_data").value("DENIED"))
                .andRespond(withNoContent());
        ga4.send(creds, purchase(null, false));
        server.verify();
    }

    private DeliveryException failure(ResponseCreator response) {
        server.reset();
        server.expect(requestTo(COLLECT)).andRespond(response);
        try {
            ga4.send(creds, purchase(null, true));
        } catch (DeliveryException e) {
            return e;
        }
        throw new AssertionError("expected a DeliveryException");
    }

    @Test
    void statusesMapToRetryOrFail() {
        assertThat(failure(withServerError()).code()).isEqualTo(DeliveryCode.PROVIDER_DOWN);
        assertThat(failure(withStatus(HttpStatus.TOO_MANY_REQUESTS)).code()).isEqualTo(DeliveryCode.RATE_LIMITED);
        assertThat(failure(withException(new SocketTimeoutException("Read timed out"))).code())
                .isEqualTo(DeliveryCode.TIMEOUT);
        assertThat(failure(withStatus(HttpStatus.FORBIDDEN)).code()).isEqualTo(DeliveryCode.AUTH_FAILED);
        DeliveryException rejected = failure(withBadRequest());
        assertThat(rejected.code()).isEqualTo(DeliveryCode.REJECTED);
        assertThat(rejected.ownerMessage(com.sixpack.marketing.domain.Provider.GOOGLE))
                .isEqualTo("Google rejected the event: HTTP 400");
    }

    @Test
    void theTestUsesTheValidationServerAndReportsItsFirstProblem() {
        server.expect(requestTo(DEBUG)).andExpect(jsonPath("$.events[0].name").value("purchase"))
                .andRespond(withSuccess("{\"validationMessages\":[]}", MediaType.APPLICATION_JSON));
        TestVerdict ok = ga4.test(creds, null, "INR");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).contains("does not check the API secret");

        server.reset();
        server.expect(requestTo(DEBUG)).andRespond(withSuccess("{\"validationMessages\":[{\"fieldPath\":\"events\","
                + "\"description\":\"Event at index: [0] has invalid name\",\"validationCode\":\"NAME_INVALID\"}]}",
                MediaType.APPLICATION_JSON));
        TestVerdict bad = ga4.test(creds, null, "INR");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.code()).isEqualTo("REJECTED");
        assertThat(bad.message()).isEqualTo("Google found a problem: Event at index: [0] has invalid name");
    }

    @Test
    void nothingLogsOrThrowsTheSecret(CapturedOutput output) {
        DeliveryException timeout = failure(withException(new SocketTimeoutException("Read timed out")));
        failure(withServerError());
        failure(withBadRequest());
        assertThat(timeout.getMessage()).doesNotContain(SECRET);
        assertThat(timeout.ownerMessage(com.sixpack.marketing.domain.Provider.GOOGLE)).doesNotContain(SECRET);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }
}
