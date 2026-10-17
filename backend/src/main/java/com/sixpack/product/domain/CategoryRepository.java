package com.sixpack.product.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for categories. */
public interface CategoryRepository {
    Optional<Category> findById(String id);

    Optional<Category> findBySlug(String slug);

    List<Category> findAll();

    boolean existsByParentId(String parentId);

    Category save(Category category);

    void deleteById(String id);
}
