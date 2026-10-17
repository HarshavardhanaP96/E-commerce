package com.sixpack.product.infra.mongo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("products")
class ProductDocument {

    @Id
    String id;
    String name;
    String photo;
    Double price;
    Integer stock;
    @Indexed
    String category;
    @Indexed(unique = true, sparse = true)
    String slug;
    @Indexed(unique = true, sparse = true)
    String sku;
    String description;
    String brand;
    Double mrp;
    Double taxRate;
    String hsn;
    @Indexed
    String status;
    List<String> tags;
    List<String> images;
    @Indexed
    String categoryId;
    @CreatedDate
    @Indexed
    Instant createdAt;
    @LastModifiedDate
    Instant updatedAt;
}
