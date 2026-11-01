package com.sixpack.product.domain;

import com.sixpack.shared.PageResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Persistence port for products. */
public interface ProductRepository {

    Optional<Product> findById(String id);

    /** Products the current store holds (for its product limit). */
    long count();

    List<Product> findAllById(Collection<String> ids);

    /** Public (ACTIVE) products only. */
    List<Product> findLatest(int limit);

    /** Public (ACTIVE) products, oldest first, at most {@code limit}, read through a cursor one at a time. */
    void forEachPublic(int limit, Consumer<Product> action);

    /** Every product regardless of status (admin). */
    List<Product> findAll();

    /** Categories of public (ACTIVE) products only. */
    List<String> distinctCategories();

    /** Option names and values of public products that have variants. */
    List<OptionFacet> optionFacets();

    /** Public (ACTIVE) products only. */
    PageResult<Product> search(ProductSearch search);

    Optional<Product> findBySlug(String slug);

    /** Matches a product's own SKU or any of its variants' SKUs. */
    Optional<Product> findBySku(String sku);

    boolean existsByCategoryId(String categoryId);

    Product save(Product product);

    void deleteById(String id);

    /**
     * Sale of a product without variants. Never matches a product that has variants, whose stock lives on them.
     *
     * @return true if stock was ≥ quantity and has been decremented
     */
    boolean decrementStockIfAvailable(String id, int quantity);

    void incrementStock(String id, int quantity);

    /**
     * Sale of a variant: decrements it and the product total only if the variant is sellable and has enough stock.
     *
     * @return true if stock was ≥ quantity and has been decremented
     */
    boolean decrementVariantStockIfAvailable(String productId, String variantId, int quantity);

    /**
     * Applies {@code change} to a freshly read product and stores only its variants, price and stock, retrying
     * when stock changed in the meantime so a concurrent sale is never overwritten.
     *
     * @return the stored product, or empty when it does not exist
     * @throws com.sixpack.shared.ApiException 409 when concurrent changes keep winning
     */
    Optional<Product> updateVariants(String id, UnaryOperator<Product> change);

    /**
     * Atomically adds {@code delta} (negative to sell) to one variant and to the product total.
     *
     * @return false if the variant does not exist or the result would be below zero
     */
    boolean adjustVariantStock(String productId, String variantId, int delta);

    /** Sets a variant's stock only if it still equals {@code expected}; keeps the product total in step. */
    boolean compareAndSetVariantStock(String productId, String variantId, int expected, int updated);
}
