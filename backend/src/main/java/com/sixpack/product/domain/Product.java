package com.sixpack.product.domain;

import java.time.Instant;
import java.util.List;

public record Product(String id, String name, String photo, double price, int stock, String category,
                      Instant createdAt, ProductDetails details) {

    public Product {
        details = details == null ? ProductDetails.EMPTY : details;
    }

    /** Pre-details shape, kept so existing callers compile unchanged. */
    public Product(String id, String name, String photo, double price, int stock, String category,
                   Instant createdAt) {
        this(id, name, photo, price, stock, category, createdAt, ProductDetails.EMPTY);
    }

    public Product withDetails(ProductDetails newDetails) {
        return new Product(id, name, photo, price, stock, category, createdAt, newDetails);
    }

    public boolean hasVariants() {
        return !details.variants().isEmpty();
    }

    /**
     * Replaces the variants and re-derives {@code price} (lowest sellable variant) and {@code stock} (sum of
     * variant stock), so every reader of those two fields keeps working. Clearing the variants keeps the last
     * derived values.
     */
    public Product withVariants(ProductVariants variants) {
        Product updated = new Product(id, name, photo, price, stock, category, createdAt,
                details.withVariants(variants));
        return variants.isEmpty() ? updated
                : new Product(id, name, photo, variants.lowestPrice(), variants.totalStock(), category, createdAt,
                        updated.details);
    }

    /**
     * Sellable lines at or below the low-stock threshold: the product itself, or each active variant. Draft and
     * archived products raise no alerts.
     */
    public List<StockAlert> stockAlerts(int storeDefault) {
        if (!details.status().isPublic()) {
            return List.of();
        }
        int threshold = details.lowStockAt(storeDefault);
        if (!hasVariants()) {
            return stock <= threshold
                    ? List.of(new StockAlert(id, name, photo, null, null, details.sku(), stock, threshold))
                    : List.of();
        }
        return details.variants().items().stream()
                .filter(v -> v.status().isSellable() && v.stock() <= threshold)
                .map(v -> new StockAlert(id, name, photo, v.id(), v.label(), v.sku(), v.stock(), threshold))
                .toList();
    }

    public static String normalizeCategory(String category) {
        return category == null ? null : category.trim().toLowerCase();
    }
}
