package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.tenant.application.LegacyIndexCleanup;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;

class LegacyIndexCleanupIntegrationTest extends IntegrationTest {

    @Autowired
    LegacyIndexCleanup cleanup;

    private List<String> indexNames(String collection) {
        return mongo.indexOps(collection).getIndexInfo().stream().map(IndexInfo::getName).toList();
    }

    @Test
    void dropsTheOldGlobalUniqueIndexesAndKeepsThePerTenantOnes() {
        // What a database that ran the single-tenant app has.
        mongo.indexOps("products").ensureIndex(new Index().on("slug", org.springframework.data.domain.Sort.Direction.ASC)
                .unique().sparse());
        mongo.indexOps("categories").ensureIndex(new Index().on("slug", org.springframework.data.domain.Sort.Direction.ASC)
                .unique());
        assertThat(indexNames("products")).contains("slug_1");

        assertThat(cleanup.cleanup()).isEqualTo(2);

        assertThat(indexNames("products")).doesNotContain("slug_1").contains("tenant_slug", "tenant_sku");
        assertThat(indexNames("categories")).doesNotContain("slug_1").contains("tenant_slug");
        assertThat(cleanup.cleanup()).isZero(); // nothing left to drop
    }

    @Test
    void twoTenantsMayShareASlugAtTheDatabaseLevelOnceTheOldIndexIsGone() {
        cleanup.cleanup();
        mongo.getCollection("products").insertOne(new Document("name", "A").append("slug", "same").append("tenantId", "t1"));
        mongo.getCollection("products").insertOne(new Document("name", "B").append("slug", "same").append("tenantId", "t2"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mongo.getCollection("products")
                        .insertOne(new Document("name", "C").append("slug", "same").append("tenantId", "t1")))
                .isInstanceOf(com.mongodb.MongoWriteException.class).hasMessageContaining("duplicate key");
    }
}
