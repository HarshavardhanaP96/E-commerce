package com.sixpack.product.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** The same variants, with option-value images that are not in {@code allowed} dropped. */
    public ProductVariants retainingImages(Set<String> allowed) {
        return new ProductVariants(axes.stream().map(a -> new OptionAxis(a.name(), a.type(),
                a.values().stream().map(v -> new OptionValue(v.label(), v.hex(),
                        v.images().stream().filter(allowed::contains).toList())).toList())).toList(), items);
    }

    /** Every option-value image path used by these axes. */
    public Set<String> imagePaths() {
        return axes.stream().flatMap(a -> a.values().stream()).flatMap(v -> v.images().stream())
                .collect(java.util.stream.Collectors.toSet());
    }
}
