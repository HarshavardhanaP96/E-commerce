package com.sixpack.order.domain;

import java.time.Instant;

/**
 * @param status "PAID" once the provider's signature has been verified; null on older orders
 * @param amount rupees charged
 */
public record PaymentInfo(String provider, String orderId, String paymentId, String status, Double amount,
                          String currency, Instant paidAt) {

    /** Pre-status shape, kept so existing callers compile unchanged. */
    public PaymentInfo(String provider, String orderId, String paymentId) {
        this(provider, orderId, paymentId, null, null, null, null);
    }
}
