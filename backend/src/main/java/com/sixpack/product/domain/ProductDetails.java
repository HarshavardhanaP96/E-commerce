package com.sixpack.product.domain;

import java.util.List;

/** Optional catalogue fields. Legacy products carry {@link #EMPTY}. */
public record ProductDetails(
        String slug,
        String sku,
        String description,
        String brand,
        Double mrp,
        Double taxRate,
        String hsn,
        ProductStatus status,
        List<String> tags,
        List<String> images,
        String categoryId) {

    public static final ProductDetails EMPTY =
            new ProductDetails(null, null, null, null, null, null, null, null, null, null, null);

    public ProductDetails {
        status = status == null ? ProductStatus.ACTIVE : status;
        tags = tags == null ? List.of() : List.copyOf(tags);
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ProductDetails withImages(List<String> newImages) {
        return new ProductDetails(slug, sku, description, brand, mrp, taxRate, hsn, status, tags, newImages,
                categoryId);
    }
}
