package com.sixpack.product;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Public API of the product module for checkout and other modules. */
public interface ProductCatalog {

    List<ProductSnapshot> findSnapshots(Collection<String> productIds);

    /**
     * Atomically decrements stock for every line, or none of them.
     *
     * @param quantities productId → quantity
     * @throws com.sixpack.shared.ApiException 409 if any product lacks stock
     */
    void reserveStock(Map<String, Integer> quantities);

    void releaseStock(Map<String, Integer> quantities);
}
