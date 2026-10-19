package com.sixpack.payment;

/**
 * What the provider says about one payment.
 *
 * @param method        how the customer paid, e.g. {@code upi}, {@code card}, {@code netbanking}; null if unknown
 * @param failureReason the provider's explanation when the payment did not go through, else null
 */
public record PaymentDetails(String method, String failureReason) {
}
