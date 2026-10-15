package com.sixpack.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.payment")
public record PaymentProperties(String provider, Razorpay razorpay) {

    public record Razorpay(String keyId, String keySecret, String baseUrl) {
    }

    public PaymentProperties {
        if (razorpay == null) {
            razorpay = new Razorpay(null, null, null);
        }
    }
}
