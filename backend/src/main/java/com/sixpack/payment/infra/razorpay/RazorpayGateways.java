package com.sixpack.payment.infra.razorpay;

import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.PaymentProperties;
import org.springframework.web.client.RestClient;

/** Builds a Razorpay gateway for one tenant's own keys. */
public final class RazorpayGateways {

    private RazorpayGateways() {
    }

    public static PaymentGateway create(String keyId, String keySecret, String baseUrl, RestClient.Builder builder) {
        return new RazorpayGateway(new PaymentProperties.Razorpay(keyId, keySecret, baseUrl), builder);
    }
}
