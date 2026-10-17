package com.sixpack.product.domain;

import com.sixpack.shared.PageResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence port for products. */
public interface ProductRepository {

    Optional<Product> findById(String id);

    List<Product> findAllById(Collection<String> ids);

    /** Public (ACTIVE) products only. */
    List<Product> findLatest(int limit);

    /** Every product regardless of status (admin). */
    List<Product> findAll();

    /** Categories of public (ACTIVE) products only. */
    List<String> distinctCategories();

    /** Public (ACTIVE) products only. */
    PageResult<Product> search(ProductSearch search);

    Optional<Product> findBySlug(String slug);

    Optional<Product> findBySku(String sku);

    boolean existsByCategoryId(String categoryId);

    Product save(Product product);

    void deleteById(String id);

    /** @return true if stock was ≥ quantity and has been decremented */
    boolean decrementStockIfAvailable(String id, int quantity);

    void incrementStock(String id, int quantity);
}
