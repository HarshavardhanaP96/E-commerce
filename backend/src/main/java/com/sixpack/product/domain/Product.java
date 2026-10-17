package com.sixpack.product.domain;

import java.time.Instant;

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

    public static String normalizeCategory(String category) {
        return category == null ? null : category.trim().toLowerCase();
    }
}
