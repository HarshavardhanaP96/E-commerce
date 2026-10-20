package com.sixpack.product.domain;

/**
 * A sellable line (a product without variants, or one active variant) at or below its low-stock threshold.
 *
 * @param variantId null for a product without variants
 */
public record StockAlert(String productId, String name, String photo, String variantId, String label, String sku,
                         int stock, int threshold) {

    public boolean isOut() {
        return stock <= 0;
    }
}
