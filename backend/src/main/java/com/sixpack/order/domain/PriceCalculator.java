package com.sixpack.order.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single source of truth for order pricing (contracts.md): tax per product rate (default 18%), free shipping above ₹1000, flat
 * coupon discount capped at the subtotal. Computed in integer paise to avoid floating-point drift.
 */
public final class PriceCalculator {

    static final long FREE_SHIPPING_ABOVE_PAISE = 1000_00;
    static final long SHIPPING_PAISE = 200_00;
    static final double DEFAULT_TAX_RATE_PERCENT = 18.0;

    private PriceCalculator() {
    }

    public static Quote quote(List<OrderItem> items, double couponAmount) {
        long subtotal = 0;
        Map<Double, Long> subtotalByRate = new TreeMap<>();
        for (OrderItem item : items) {
            long line = toPaise(item.price()) * item.quantity();
            subtotal += line;
            double rate = item.taxRate() == null ? DEFAULT_TAX_RATE_PERCENT : item.taxRate();
            subtotalByRate.merge(rate, line, Long::sum);
        }
        long shipping = subtotal > FREE_SHIPPING_ABOVE_PAISE ? 0 : SHIPPING_PAISE;
        long tax = 0;
        for (Map.Entry<Double, Long> group : subtotalByRate.entrySet()) {
            // Whole rupees per rate group, like the old UI. At 18% this is the previous formula, bit for bit.
            tax += Math.round(group.getValue() * (group.getKey() / 100.0) / 100.0) * 100;
        }
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
