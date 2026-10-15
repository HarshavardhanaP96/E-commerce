package com.sixpack.payment;

/** Public API of the payment module. Exactly one implementation is active, chosen by {@code app.payment.provider}. */
public interface PaymentGateway {

    /** e.g. {@code razorpay} or {@code fake}; the frontend picks its checkout flow from this. */
    String provider();

    /** Publishable key the browser needs to open the checkout widget (never the secret). */
    String publicKey();

    /**
     * @throws PaymentProviderException if the provider is unreachable or rejects the request
     */
    ProviderOrder createOrder(long amountPaise, String receipt);

    /** Constant-time check of the provider's payment signature. */
    boolean verifySignature(String providerOrderId, String paymentId, String signature);
}
