package com.sixpack.order.domain;

import java.time.Instant;
import java.util.List;

/**
 * @param status         {@code PAID}, then {@code PARTIALLY_REFUNDED} or {@code REFUNDED}; null on older orders
 * @param amount         rupees charged
 * @param method         how the customer paid (e.g. {@code upi}); null when the provider could not be asked
 * @param failureReason  why the last attempt to return money failed; cleared by the next success. Null otherwise
 * @param refundedPaise  money returned or being returned, including refunds that are still in flight
 * @param refunds        every completed refund, oldest first
 */
public record PaymentInfo(String provider, String orderId, String paymentId, String status, Double amount,
                          String currency, Instant paidAt, String method, String failureReason, long refundedPaise,
                          List<Refund> refunds) {

    public static final String PAID = "PAID";
    public static final String PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";
    public static final String REFUNDED = "REFUNDED";

    public PaymentInfo {
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
    }

    /** Pre-refund shape, kept so existing callers compile unchanged. */
    public PaymentInfo(String provider, String orderId, String paymentId, String status, Double amount,
                       String currency, Instant paidAt) {
        this(provider, orderId, paymentId, status, amount, currency, paidAt, null, null, 0, null);
    }

    /** Pre-status shape, kept so existing callers compile unchanged. */
    public PaymentInfo(String provider, String orderId, String paymentId) {
        this(provider, orderId, paymentId, null, null, null, null);
    }

    public PaymentInfo withMethod(String newMethod) {
        return new PaymentInfo(provider, orderId, paymentId, status, amount, currency, paidAt, newMethod,
                failureReason, refundedPaise, refunds);
    }

    /** Rupees returned or being returned. */
    public double refunded() {
        return refundedPaise / 100.0;
    }

    /** What is still refundable, in paise. */
    public long refundablePaise() {
        return totalPaise() - refundedPaise;
    }

    public long totalPaise() {
        return amount == null ? 0 : Math.round(amount * 100);
    }
}
