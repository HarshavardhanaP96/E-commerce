package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.ProductStatus;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

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
        backfillLegacy();

        Product p = products.findById(id.toHexString()).orElseThrow();

        assertThat(p.details()).isEqualTo(ProductDetails.EMPTY);
        assertThat(p.details().status()).isEqualTo(ProductStatus.ACTIVE);
    }

    private ResultActions create(String admin, String name, String... extra) throws Exception {
        var req = multipart("/api/v1/product/new")
                .file(new MockMultipartFile("photo", "p.png", "image/png", com.sixpack.storage.TestImages.png()))
                .param("name", name).param("price", "100").param("stock", "5").param("category", "misc")
                .header("Authorization", bearer(admin));
        for (int i = 0; i < extra.length; i += 2) {
            req.param(extra[i], extra[i + 1]);
        }
        return mvc.perform(req);
    }

    @Test
    void createStoresDetailsAndGeneratesSlug() throws Exception {
        create(adminToken(), "Running Shoe", "sku", "RS-1", "brand", "Acme", "description", "Light shoe",
                "mrp", "150", "taxRate", "18", "hsn", "6403", "status", "draft", "tags", " Run ,SPORT,run")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.product.slug").value("running-shoe"))
                .andExpect(jsonPath("$.product.sku").value("RS-1"))
                .andExpect(jsonPath("$.product.brand").value("Acme"))
                .andExpect(jsonPath("$.product.mrp").value(150.0))
                .andExpect(jsonPath("$.product.taxRate").value(18.0))
                .andExpect(jsonPath("$.product.hsn").value("6403"))
                .andExpect(jsonPath("$.product.status").value("DRAFT"))
                .andExpect(jsonPath("$.product.tags.length()").value(2))
                .andExpect(jsonPath("$.product.tags[0]").value("run"))
                .andExpect(jsonPath("$.product.tags[1]").value("sport"));
    }

    @Test
    void sameNameGetsSuffixedSlug() throws Exception {
        String admin = adminToken();
        create(admin, "Running Shoe").andExpect(jsonPath("$.product.slug").value("running-shoe"));
        create(admin, "Running Shoe").andExpect(jsonPath("$.product.slug").value("running-shoe-2"));
    }

    @Test
    void blankSkuNeverCollides() throws Exception {
        String admin = adminToken();
        create(admin, "A", "sku", "  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.product.sku").doesNotExist());
        create(admin, "B", "sku", "").andExpect(status().isCreated());
        create(admin, "C").andExpect(status().isCreated());
    }

    @Test
    void duplicateSkuIsConflict() throws Exception {
        String admin = adminToken();
        create(admin, "A", "sku", "S-1").andExpect(status().isCreated());
        create(admin, "B", "sku", "S-1").andExpect(status().isConflict());
    }

    @Test
    void invalidDetailsAreBadRequestNotServerError() throws Exception {
        String admin = adminToken();
        create(admin, "A", "mrp", "50").andExpect(status().isBadRequest()); // price is 100
        create(admin, "A", "status", "bogus").andExpect(status().isBadRequest());
        create(admin, "A", "taxRate", "101").andExpect(status().isBadRequest());
        create(admin, "A", "hsn", "12").andExpect(status().isBadRequest());
        create(admin, "A", "categoryId", "64b7f0000000000000000000").andExpect(status().isBadRequest());
        String manyTags = String.join(",", java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "t" + i).toList());
        create(admin, "A", "tags", manyTags).andExpect(status().isBadRequest());
    }

    @Test
    void updateChangesOnlyWhatIsSent() throws Exception {
        String admin = adminToken();
        String id = body(create(admin, "Shoe", "brand", "Acme", "sku", "S-9").andReturn())
                .get("product").get("_id").asText();

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).param("description", "New text")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.description").value("New text"))
                .andExpect(jsonPath("$.product.brand").value("Acme"))
                .andExpect(jsonPath("$.product.sku").value("S-9"))
                .andExpect(jsonPath("$.product.slug").value("shoe"));
    }

    @Test
    void legacyProductCanBeEditedAndGainsASlug() throws Exception {
        ObjectId id = new ObjectId();
        mongo.getCollection("products").insertOne(new Document("_id", id).append("name", "Old Lamp")
                .append("photo", "uploads/o.png").append("price", 10.0).append("stock", 2)
                .append("category", "misc"));
        backfillLegacy();

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id.toHexString()).param("stock", "7")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.stock").value(7))
                .andExpect(jsonPath("$.product.slug").value("old-lamp"))
                .andExpect(jsonPath("$.product.status").value("ACTIVE"));
    }

    @Autowired
    com.sixpack.product.ProductCatalog catalog;

    @Test
    void draftAndArchivedAreHiddenFromPublicListings() throws Exception {
        String admin = adminToken();
        create(admin, "Visible", "status", "active");
        create(admin, "Hidden Draft", "status", "draft", "category", "secret-draft");
        create(admin, "Hidden Archived", "status", "archived");
        mongo.getCollection("products").insertOne(new Document("name", "Legacy").append("photo", "uploads/o.png")
                .append("price", 10.0).append("stock", 1).append("category", "misc")
                .append("createdAt", java.time.Instant.now()));
        backfillLegacy();

        mvc.perform(get("/api/v1/product/all")).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/v1/product/latest")).andExpect(jsonPath("$.products.length()").value(2));
        mvc.perform(get("/api/v1/product/categories"))
                .andExpect(jsonPath("$.categories", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("secret-draft"))));
        mvc.perform(get("/api/v1/product/admin-products").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.products.length()").value(4));
    }

    @Test
    void nonPublicProductsCannotBePurchased() throws Exception {
        String admin = adminToken();
        String draft = body(create(admin, "Draft", "status", "draft").andReturn())
                .get("product").get("_id").asText();
        String live = body(create(admin, "Live").andReturn()).get("product").get("_id").asText();

        assertThat(catalog.findSnapshots(List.of(draft, live)))
                .extracting(com.sixpack.product.ProductSnapshot::id).containsExactly(live);
    }

    private static MockMultipartFile image(String part) throws Exception {
        return new MockMultipartFile(part, "g.png", "image/png", com.sixpack.storage.TestImages.png());
    }

    @Test
    void galleryImagesAreStoredReplacedAndKept() throws Exception {
        String admin = adminToken();
        MvcResult created = mvc.perform(multipart("/api/v1/product/new")
                        .file(image("photo")).file(image("images")).file(image("images"))
                        .param("name", "Gallery").param("price", "10").param("stock", "1").param("category", "x")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.product.images.length()").value(2)).andReturn();
        String id = body(created).get("product").get("_id").asText();

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).param("brand", "Acme")
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.product.images.length()").value(2));

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).file(image("images"))
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.product.images.length()").value(1));
    }

    @Test
    void rejectsMoreThanFiveGalleryImagesAndNonImages() throws Exception {
        String admin = adminToken();
        var tooMany = multipart("/api/v1/product/new");
        tooMany.file(image("photo"));
        tooMany.param("name", "G").param("price", "10").param("stock", "1").param("category", "x")
                .header("Authorization", bearer(admin));
        for (int i = 0; i < 6; i++) {
            tooMany.file(image("images"));
        }
        mvc.perform(tooMany).andExpect(status().isBadRequest());

        mvc.perform(multipart("/api/v1/product/new").file(image("photo"))
                        .file(new MockMultipartFile("images", "x.png", "image/png", "<html>".getBytes()))
                        .param("name", "G").param("price", "10").param("stock", "1").param("category", "x")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }
}
