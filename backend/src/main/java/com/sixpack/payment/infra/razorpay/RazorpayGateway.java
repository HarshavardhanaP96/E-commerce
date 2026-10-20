package com.sixpack.payment.infra.razorpay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.payment.PaymentDetails;
import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.PaymentProperties;
import com.sixpack.payment.PaymentProviderException;
import com.sixpack.payment.ProviderOrder;
import com.sixpack.payment.RefundResult;
import com.sixpack.payment.infra.Hmac;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Razorpay Orders API over plain REST (ADR-005). */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "razorpay")
class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    record RazorpayOrderResponse(String id, long amount, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RazorpayPaymentResponse(String status, String method,
                                   @JsonProperty("error_description") String errorDescription) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RazorpayRefundResponse(String id, String status) {
    }

    private final PaymentProperties.Razorpay config;
    private final RestClient client;

    RazorpayGateway(PaymentProperties props, RestClient.Builder builder) {
        this(props, build(props, builder));
    }

    /** Takes a ready client, so tests can put a mock server behind it. */
    RazorpayGateway(PaymentProperties props, RestClient client) {
        this.config = props.razorpay();
        this.client = client;
    }

    private static RestClient build(PaymentProperties props, RestClient.Builder builder) {
        PaymentProperties.Razorpay config = props.razorpay();
        if (isBlank(config.keyId()) || isBlank(config.keySecret())) {
            throw new IllegalStateException("RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET are required when PAYMENT_PROVIDER=razorpay");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return builder
                .baseUrl(isBlank(config.baseUrl()) ? "https://api.razorpay.com" : config.baseUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(config.keyId(), config.keySecret()))
                .build();
    }

    @Override
    public String provider() {
        return "razorpay";
    }

    @Override
    public String publicKey() {
        return config.keyId();
    }

    @Override
    public ProviderOrder createOrder(long amountPaise, String receipt) {
        try {
            RazorpayOrderResponse response = client.post().uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amountPaise, "currency", "INR", "receipt", receipt))
                    .retrieve()
                    .body(RazorpayOrderResponse.class);
            if (response == null || response.id() == null) {
                throw new PaymentProviderException("Payment provider returned an empty response");
            }
            return new ProviderOrder(response.id(), response.amount(), response.currency());
        } catch (RestClientException e) {
            log.error("Razorpay order creation failed", e);
            throw new PaymentProviderException("Payment provider is unavailable, please try again");
        }
    }

    @Override
    public PaymentDetails fetchPayment(String paymentId) {
        try {
            RazorpayPaymentResponse response = client.get().uri("/v1/payments/{id}", paymentId)
                    .retrieve().body(RazorpayPaymentResponse.class);
            if (response == null) {
                throw new PaymentProviderException("Payment provider returned an empty response");
            }
            boolean failed = "failed".equals(response.status());
            return new PaymentDetails(response.method(), failed ? response.errorDescription() : null);
        } catch (RestClientException e) {
            log.error("Razorpay payment lookup failed for {}", paymentId, e);
            throw new PaymentProviderException("Payment provider is unavailable, please try again");
        }
    }

    @Override
    public RefundResult refund(String paymentId, long amountPaise, String idempotencyKey, String reason) {
        try {
            RazorpayRefundResponse response = client.post().uri("/v1/payments/{id}/refund", paymentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Refund-Idempotency", idempotencyKey)
                    .body(Map.of("amount", amountPaise, "speed", "normal", "notes", Map.of("reason", reason),
                            "receipt", idempotencyKey))
                    .retrieve()
                    .body(RazorpayRefundResponse.class);
            if (response == null || response.id() == null) {
                throw new PaymentProviderException("Payment provider returned an empty response");
            }
            return new RefundResult(response.id(), response.status());
        } catch (RestClientResponseException e) {
            log.error("Razorpay refund of {} on {} was refused: {}", amountPaise, paymentId,
                    e.getResponseBodyAsString());
            throw new PaymentProviderException("The payment provider refused the refund");
        } catch (RestClientException e) {
            log.error("Razorpay refund of {} on {} failed", amountPaise, paymentId, e);
            throw new PaymentProviderException("Payment provider is unavailable, please try again");
        }
    }

    @Override
    public boolean verifySignature(String providerOrderId, String paymentId, String signature) {
        String expected = Hmac.sha256Hex(config.keySecret(), providerOrderId + "|" + paymentId);
        return Hmac.constantTimeEquals(expected, signature);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
