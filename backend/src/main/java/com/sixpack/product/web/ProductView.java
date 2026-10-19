package com.sixpack.product.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import com.sixpack.product.domain.ProductVariants;
import java.time.Instant;
import java.util.List;

record ProductView(@JsonProperty("_id") String id, String name, String photo, double price, int stock,
                   String category, Instant createdAt, String slug, String sku, String description, String brand,
                   Double mrp, Double taxRate, String hsn, String status, List<String> tags, List<String> images,
                   String categoryId, String countryOfOrigin, VariantsView variants) {

    record VariantsView(List<AxisView> axes, List<VariantView> items) {
        static VariantsView from(ProductVariants v) {
            return new VariantsView(
                    v.axes().stream().map(a -> new AxisView(a.name(), a.type().name(), a.values().stream()
                            .map(x -> new ValueView(x.label(), x.hex(), x.images())).toList())).toList(),
                    v.items().stream().map(i -> new VariantView(i.id(), i.sku(), i.label(),
                            i.options().stream().map(o -> new ChoiceView(o.name(), o.value())).toList(), i.price(),
                            i.mrp(), i.stock(), i.status().name())).toList());
        }
    }

    record AxisView(String name, String type, List<ValueView> values) {
    }

    record ValueView(String label, String hex, List<String> images) {
    }

    record VariantView(String id, String sku, String label, List<ChoiceView> options, double price, Double mrp,
                       int stock, String status) {
    }

    record ChoiceView(String name, String value) {
    }

    static ProductView from(Product p) {
        ProductDetails d = p.details();
        return new ProductView(p.id(), p.name(), p.photo(), p.price(), p.stock(), p.category(), p.createdAt(),
                d.slug(), d.sku(), d.description(), d.brand(), d.mrp(), d.taxRate(), d.hsn(), d.status().name(),
                d.tags(), d.images(), d.categoryId(), d.countryOfOrigin(), VariantsView.from(d.variants()));
    }
}
