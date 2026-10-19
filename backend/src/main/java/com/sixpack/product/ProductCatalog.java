package com.sixpack.product;

import java.util.Collection;
import java.util.List;

/** Public API of the product module for checkout and other modules. */
public interface ProductCatalog {

    /** Public products by id, at product level (no variant resolution). */
    List<ProductSnapshot> findSnapshots(Collection<String> productIds);

    /**
     * Resolves one cart line to what can be bought. A product with variants needs a {@code variantId} of a
     * sellable variant; a product without variants must not have one.
     *
     * @throws com.sixpack.shared.ApiException 400 if the product or variant is missing, unavailable or the options
     *         do not fit the product
     */
    ProductSnapshot resolveLine(String productId, String variantId);

    /**
     * Atomically decrements stock for every line, or none of them.
     *
     * @throws com.sixpack.shared.ApiException 409 if any line lacks stock
     */
    void reserveStock(Collection<StockLine> lines);

    void releaseStock(Collection<StockLine> lines);
}
