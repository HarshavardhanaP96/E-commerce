package com.sixpack.order.domain;

/** All amounts in rupees, rounded to paise. */
public record Quote(double subtotal, double tax, double shippingCharges, double discount, double total) {

    public long totalPaise() {
        return Math.round(total * 100);
    }
}
