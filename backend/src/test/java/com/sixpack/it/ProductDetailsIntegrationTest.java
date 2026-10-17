package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.ProductStatus;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProductDetailsIntegrationTest extends IntegrationTest {

    @Autowired
    ProductRepository products;

    @Test
    void roundTripsEveryNewField() {
        Product saved = products.save(new Product(null, "Shoe", "uploads/a.png", 100, 3, "footwear", null,
                new ProductDetails("running-shoe", "SKU-1", "Light", "Acme", 150.0, 18.0, "6403",
                        ProductStatus.DRAFT, List.of("run", "sport"), List.of("uploads/b.png"), "cat1")));

        Product loaded = products.findById(saved.id()).orElseThrow();

        assertThat(loaded.details()).isEqualTo(saved.details());
        assertThat(loaded.details().status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(products.findBySlug("running-shoe")).map(Product::id).contains(saved.id());
        assertThat(products.findBySku("SKU-1")).map(Product::id).contains(saved.id());
        assertThat(products.findBySlug("nope")).isEmpty();
    }

    @Test
    void legacyDocumentWithoutNewFieldsLoadsWithDefaults() {
        ObjectId id = new ObjectId();
        mongo.getCollection("products").insertOne(new Document("_id", id).append("name", "Old")
                .append("photo", "uploads/o.png").append("price", 10.0).append("stock", 2)
                .append("category", "misc"));

        Product p = products.findById(id.toHexString()).orElseThrow();

        assertThat(p.details()).isEqualTo(ProductDetails.EMPTY);
        assertThat(p.details().status()).isEqualTo(ProductStatus.ACTIVE);
    }
}
