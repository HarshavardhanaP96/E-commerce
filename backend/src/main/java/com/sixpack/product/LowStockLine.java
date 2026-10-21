package com.sixpack.product;

/**
 * A sellable product, or an active variant of one, at or below its low-stock threshold.
 *
 * @param variantId null for a product without variants
 */
public record LowStockLine(String productId, String name, String variantId, String label, String sku, int stock,
                           int threshold) {
}
