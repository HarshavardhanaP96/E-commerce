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

    /**
     * Looks the payment up at the provider.
     *
     * @throws PaymentProviderException if the provider is unreachable or does not know the payment
     */
    PaymentDetails fetchPayment(String paymentId);

    /**
     * Returns money to the customer's original payment method.
     *
     * @param idempotencyKey the same key must be sent when the same refund is retried, so a request that timed out
     *                       but was carried out is not carried out twice
     * @throws PaymentProviderException if the provider is unreachable or refuses the refund
     */
    RefundResult refund(String paymentId, long amountPaise, String idempotencyKey, String reason);

    /** Constant-time check of the provider's payment signature. */
    boolean verifySignature(String providerOrderId, String paymentId, String signature);
}
