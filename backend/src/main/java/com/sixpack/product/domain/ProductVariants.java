package com.sixpack.product.domain;

import java.util.List;
import java.util.Optional;

/** The option axes of a product and the variants built from them. Legacy and simple products carry {@link #EMPTY}. */
public record ProductVariants(List<OptionAxis> axes, List<Variant> items) {

    public static final ProductVariants EMPTY = new ProductVariants(List.of(), List.of());

    public ProductVariants {
        axes = axes == null ? List.of() : List.copyOf(axes);
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Optional<Variant> find(String variantId) {
        return items.stream().filter(v -> v.id().equals(variantId)).findFirst();
    }

    public int totalStock() {
        return items.stream().mapToInt(Variant::stock).sum();
    }

    /** Lowest price a shopper can pay: among sellable variants, or among all when none is sellable. */
    public double lowestPrice() {
        return items.stream().filter(v -> v.status().isSellable()).mapToDouble(Variant::price).min()
                .orElseGet(() -> items.stream().mapToDouble(Variant::price).min().orElse(0));
    }
}
