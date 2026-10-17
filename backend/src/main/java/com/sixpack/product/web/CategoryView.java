package com.sixpack.product.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.product.domain.Category;
import java.time.Instant;

record CategoryView(@JsonProperty("_id") String id, String name, String slug, String parentId, String image,
                    Instant createdAt) {
    static CategoryView from(Category c) {
        return new CategoryView(c.id(), c.name(), c.slug(), c.parentId(), c.image(), c.createdAt());
    }
}
