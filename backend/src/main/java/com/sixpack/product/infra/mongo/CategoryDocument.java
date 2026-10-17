package com.sixpack.product.infra.mongo;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("categories")
class CategoryDocument {
    @Id
    String id;
    String name;
    @Indexed(unique = true)
    String slug;
    @Indexed
    String parentId;
    String image;
    @CreatedDate
    Instant createdAt;
    @LastModifiedDate
    Instant updatedAt;
}
