package com.sixpack.payment;

/** @param status the provider's refund state, e.g. {@code processed} or {@code pending} */
public record RefundResult(String id, String status) {
}
