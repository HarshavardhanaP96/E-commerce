package com.sixpack.product.domain;

import java.time.Instant;

public record Product(String id, String name, String photo, double price, int stock, String category,
                      Instant createdAt) {

    public static String normalizeCategory(String category) {
        return category == null ? null : category.trim().toLowerCase();
    }
}
