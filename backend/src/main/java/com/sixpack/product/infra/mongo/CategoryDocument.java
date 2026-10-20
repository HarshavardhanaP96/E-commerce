package com.sixpack.product.infra.mongo;

import java.time.Instant;
import com.sixpack.shared.tenant.TenantDocument;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("categories")
@CompoundIndex(name = "tenant_slug", def = "{'tenantId': 1, 'slug': 1}", unique = true)
class CategoryDocument extends TenantDocument {
    @Id
    String id;
    String name;
    String slug;
    @Indexed
    String parentId;
    String image;
    @CreatedDate
    Instant createdAt;
    @LastModifiedDate
    Instant updatedAt;
}
