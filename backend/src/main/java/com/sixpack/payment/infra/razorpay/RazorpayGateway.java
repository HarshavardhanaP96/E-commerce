package com.sixpack.payment.infra.razorpay;

import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.PaymentProperties;
import com.sixpack.payment.PaymentProviderException;
import com.sixpack.payment.ProviderOrder;
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

/** Razorpay Orders API over plain REST (ADR-005). */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "razorpay")
class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    record RazorpayOrderResponse(String id, long amount, String currency) {
    }

    private final PaymentProperties.Razorpay config;
    private final RestClient client;

    RazorpayGateway(PaymentProperties props, RestClient.Builder builder) {
        this.config = props.razorpay();
        if (isBlank(config.keyId()) || isBlank(config.keySecret())) {
            throw new IllegalStateException("RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET are required when PAYMENT_PROVIDER=razorpay");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder
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
    public boolean verifySignature(String providerOrderId, String paymentId, String signature) {
        String expected = Hmac.sha256Hex(config.keySecret(), providerOrderId + "|" + paymentId);
        return Hmac.constantTimeEquals(expected, signature);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
