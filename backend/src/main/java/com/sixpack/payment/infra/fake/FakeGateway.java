package com.sixpack.payment.infra.fake;

import com.sixpack.payment.PaymentDetails;
import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.ProviderOrder;
import com.sixpack.payment.RefundResult;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Local/test gateway: no network, every order "pays" when the client sends signature {@code fake}.
 * Refuses to start under the {@code prod} profile.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "fake", matchIfMissing = true)
class FakeGateway implements PaymentGateway {

    static final String SIGNATURE = "fake";
    private static final Logger log = LoggerFactory.getLogger(FakeGateway.class);

    FakeGateway(Environment env) {
        if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("The fake payment gateway cannot run with the prod profile");
        }
        log.warn("Using FAKE payment gateway - no real payments are taken");
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String publicKey() {
        return "fake";
    }

    @Override
    public ProviderOrder createOrder(long amountPaise, String receipt) {
        return new ProviderOrder("fake_order_" + UUID.randomUUID().toString().replace("-", ""), amountPaise, "INR");
    }

    @Override
    public PaymentDetails fetchPayment(String paymentId) {
        return new PaymentDetails("fake", null);
    }

    @Override
    public RefundResult refund(String paymentId, long amountPaise, String idempotencyKey, String reason) {
        return new RefundResult("fake_rfnd_" + UUID.randomUUID().toString().replace("-", ""), "processed");
    }

    @Override
    public boolean verifySignature(String providerOrderId, String paymentId, String signature) {
        return providerOrderId.startsWith("fake_order_") && SIGNATURE.equals(signature);
    }
}
