package com.sixpack.order.domain;

import java.util.List;

/**
 * Single source of truth for order pricing (contracts.md): 18% tax, free shipping above ₹1000, flat
 * coupon discount capped at the subtotal. Computed in integer paise to avoid floating-point drift.
 */
public final class PriceCalculator {

    static final long FREE_SHIPPING_ABOVE_PAISE = 1000_00;
    static final long SHIPPING_PAISE = 200_00;
    static final double TAX_RATE = 0.18;

    private PriceCalculator() {
    }

    public static Quote quote(List<OrderItem> items, double couponAmount) {
        long subtotal = 0;
        for (OrderItem item : items) {
            subtotal += toPaise(item.price()) * item.quantity();
        }
        long shipping = subtotal > FREE_SHIPPING_ABOVE_PAISE ? 0 : SHIPPING_PAISE;
        long tax = Math.round(subtotal * TAX_RATE / 100.0) * 100; // whole rupees, like the old UI
        long discount = Math.min(Math.max(toPaise(couponAmount), 0), subtotal);
        long total = subtotal + tax + shipping - discount;
        return new Quote(rupees(subtotal), rupees(tax), rupees(shipping), rupees(discount), rupees(total));
    }

    static long toPaise(double rupees) {
        return Math.round(rupees * 100);
    }

    private static double rupees(long paise) {
        return paise / 100.0;
    }
}
