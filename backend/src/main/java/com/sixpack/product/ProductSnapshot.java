package com.sixpack.product;

import java.util.List;

/**
 * What checkout needs to know about one purchasable line. For a variant line, {@code price}, {@code stock},
 * {@code sku} and {@code photo} are the variant's (photo falls back to the product's).
 *
 * @param variantId null for a product without variants
 * @param variantLabel e.g. "M / Black"; null for a product without variants
 */
public record ProductSnapshot(String id, String name, String photo, double price, int stock, String sku,
                              Double taxRate, String variantId, String variantLabel, List<Option> options,
                              String category) {

    public record Option(String name, String value) {
    }

    public ProductSnapshot {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** Shape for a product without variants. */
    public ProductSnapshot(String id, String name, String photo, double price, int stock, String sku,
                           Double taxRate) {
        this(id, name, photo, price, stock, sku, taxRate, null, null, null, null);
    }
}
