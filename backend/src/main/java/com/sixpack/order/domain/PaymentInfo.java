package com.sixpack.order.domain;

public record PaymentInfo(String provider, String orderId, String paymentId) {
}
