package com.sixpack.order.domain;

import java.util.List;

/**
 * Snapshot of the product at purchase time, so later product edits/deletes do not change history.
 *
 * @param taxRate percent applied to this line, or null for the default rate (older orders)
 * @param variantId the variant bought, or null for a product without variants (and for older orders)
 * @param variantLabel e.g. "M / Black", so history reads right even after the variant is renamed or deleted
 */
public record OrderItem(String productId, String name, String photo, double price, int quantity, String sku,
                        Double taxRate, String variantId, String variantLabel, List<Option> options) {

    public record Option(String name, String value) {
    }

    public OrderItem {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** Pre-variant shape, kept so existing callers compile unchanged. */
    public OrderItem(String productId, String name, String photo, double price, int quantity, String sku,
                     Double taxRate) {
        this(productId, name, photo, price, quantity, sku, taxRate, null, null, null);
    }

    /** Pre-sku shape, kept so existing callers compile unchanged. */
    public OrderItem(String productId, String name, String photo, double price, int quantity) {
        this(productId, name, photo, price, quantity, null, null);
    }
}
