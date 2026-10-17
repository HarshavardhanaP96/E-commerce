package com.sixpack.product.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import java.time.Instant;
import java.util.List;

record ProductView(@JsonProperty("_id") String id, String name, String photo, double price, int stock,
                   String category, Instant createdAt, String slug, String sku, String description, String brand,
                   Double mrp, Double taxRate, String hsn, String status, List<String> tags, List<String> images,
                   String categoryId) {
    static ProductView from(Product p) {
        ProductDetails d = p.details();
        return new ProductView(p.id(), p.name(), p.photo(), p.price(), p.stock(), p.category(), p.createdAt(),
                d.slug(), d.sku(), d.description(), d.brand(), d.mrp(), d.taxRate(), d.hsn(), d.status().name(),
                d.tags(), d.images(), d.categoryId());
    }
}
