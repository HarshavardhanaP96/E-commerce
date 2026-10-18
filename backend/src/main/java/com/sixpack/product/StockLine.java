package com.sixpack.product;

/** Quantity of one purchasable line; {@code variantId} is null for a product without variants. */
public record StockLine(String productId, String variantId, int quantity) {
}
