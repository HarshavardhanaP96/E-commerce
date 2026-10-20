package com.sixpack.payment.infra.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sixpack.payment.PaymentDetails;
import com.sixpack.payment.PaymentProperties;
import com.sixpack.payment.PaymentProviderException;
import com.sixpack.payment.RefundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RazorpayGatewayTest {

    private MockRestServiceServer server;
    private RazorpayGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RazorpayGateway(
                new PaymentProperties("razorpay", new PaymentProperties.Razorpay("key", "secret", "https://rzp.test")),
                builder.baseUrl("https://rzp.test").build());
    }

    @Test
    void readsTheMethodOfASuccessfulPayment() {
        server.expect(requestTo("https://rzp.test/v1/payments/pay_1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"pay_1\",\"status\":\"captured\",\"method\":\"upi\","
                        + "\"amount\":79000,\"error_description\":null}", MediaType.APPLICATION_JSON));

        assertThat(gateway.fetchPayment("pay_1")).isEqualTo(new PaymentDetails("upi", null));
    }

    @Test
    void passesOnTheReasonWhenTheProviderSaysThePaymentFailed() {
        server.expect(requestTo("https://rzp.test/v1/payments/pay_2"))
                .andRespond(withSuccess("{\"status\":\"failed\",\"method\":\"card\","
                        + "\"error_description\":\"Card declined\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.fetchPayment("pay_2")).isEqualTo(new PaymentDetails("card", "Card declined"));
    }

    @Test
    void sendsARefundWithAnIdempotencyKeyAndTheReason() {
        server.expect(requestTo("https://rzp.test/v1/payments/pay_1/refund")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Refund-Idempotency", "rfnd_o1_0_10000"))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.notes.reason").value("Damaged"))
                .andRespond(withSuccess("{\"id\":\"rfnd_9\",\"status\":\"processed\",\"amount\":10000}",
                        MediaType.APPLICATION_JSON));

        assertThat(gateway.refund("pay_1", 10_000, "rfnd_o1_0_10000", "Damaged"))
                .isEqualTo(new RefundResult("rfnd_9", "processed"));
        server.verify();
    }

    @Test
    void aRefusedRefundBecomesAReadableProviderError() {
        server.expect(requestTo("https://rzp.test/v1/payments/pay_1/refund")).andRespond(
                withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"description\":\"The refund amount is greater than the payment\"}}"));

        assertThatThrownBy(() -> gateway.refund("pay_1", 99_999_999, "k", "r"))
                .isInstanceOf(PaymentProviderException.class).hasMessage("The payment provider refused the refund");
    }

    @Test
    void anUnreachableProviderBecomesAProviderError() {
        server.expect(requestTo("https://rzp.test/v1/payments/pay_1")).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.fetchPayment("pay_1")).isInstanceOf(PaymentProviderException.class)
                .hasMessageContaining("unavailable");
    }
}
