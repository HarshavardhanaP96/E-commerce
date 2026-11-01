package com.sixpack.product;

import java.util.function.Consumer;

/** Read-only view of the catalogue for modules that list it elsewhere (the marketing product feed). */
public interface CatalogReads {

    /**
     * Calls {@code sink} once per sellable line of the current store's public products, oldest product first,
     * reading one product at a time. A product without variants is one line; a product with variants is one line
     * per active variant (disabled ones are left out). At most {@code maxProducts} products are read.
     */
    void forEachListing(int maxProducts, Consumer<Listing> sink);

    /**
     * @param variantId    null for a product without variants
     * @param variantLabel e.g. "M / Black"; null for a product without variants
     * @param description  as the admin typed it; may be null
     * @param brand        the product's own brand; may be null or blank
     * @param photo        storage path such as {@code uploads/…}: the variant's colour image, else the product
     *                     photo; may be null
     */
    record Listing(String productId, String variantId, String name, String variantLabel, String description,
                   String brand, String photo, double price, int stock) {
    }
}
