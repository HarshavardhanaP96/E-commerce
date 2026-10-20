package com.sixpack.order.domain;

import java.util.List;

/**
 * A store's pricing rules, in rupees: the flat shipping fee, the subtotal above which shipping is free, and the GST
 * rate for products that do not set their own.
 */
public record PricingRules(double shippingFee, double freeShippingAbove, double defaultTaxRate) {

    /** The platform's rules before stores could set their own (contracts.md). */
    public static final PricingRules STANDARD = new PricingRules(200.0, 1000.0, 18.0);

    /**
     * The items with the rate each is charged at written on it, so the order (and its invoice) keeps that rate when
     * the store later changes its default.
     */
    public List<OrderItem> stamp(List<OrderItem> items) {
        return items.stream().map(i -> i.taxRate() != null ? i
                : new OrderItem(i.productId(), i.name(), i.photo(), i.price(), i.quantity(), i.sku(), defaultTaxRate,
                        i.variantId(), i.variantLabel(), i.options())).toList();
    }
}
