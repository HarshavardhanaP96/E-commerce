package com.sixpack.order.domain;

/**
 * Snapshot of the product at purchase time, so later product edits/deletes do not change history.
 *
 * @param taxRate percent applied to this line, or null for the default rate (older orders)
 */
public record OrderItem(String productId, String name, String photo, double price, int quantity, String sku,
                        Double taxRate) {

    /** Pre-sku shape, kept so existing callers compile unchanged. */
    public OrderItem(String productId, String name, String photo, double price, int quantity) {
        this(productId, name, photo, price, quantity, null, null);
    }
}
