package com.sixpack.product.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.product.domain.Product;
import java.time.Instant;

record ProductView(@JsonProperty("_id") String id, String name, String photo, double price, int stock,
                   String category, Instant createdAt) {

    static ProductView from(Product p) {
        return new ProductView(p.id(), p.name(), p.photo(), p.price(), p.stock(), p.category(), p.createdAt());
    }
}
