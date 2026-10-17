# Product and Category Fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `Product` the Tier 1 catalogue fields (slug, sku, description, brand, mrp, tax, hsn, status, tags, gallery images, categoryId) and add a `Category` collection with admin CRUD, without breaking any existing endpoint, JSON field or stored document.

**Architecture:** New optional fields live in a nested `ProductDetails` record on `Product`, so the existing 7-argument constructor and every current call site keep compiling. Stored documents without the new fields load with defaults (`ProductDetails.EMPTY`), so there is **no data migration**. `Category` is a new aggregate inside the existing `product` Modulith module (domain, Mongo adapter, service, controller). Draft/archived products are hidden from public listings and cannot be bought.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.13, Spring Data MongoDB (`MongoTemplate`), JUnit 5 + AssertJ + MockMvc + Testcontainers `mongo:7.0`; React + TypeScript + Redux Toolkit Query (frontend).

**Spec:** `docs/superpowers/specs/2026-10-17-richer-entities-design.md` (§4 Entities, delivery step 1). This is plan 1 of 4. Later plans (not in this file): User/Address, Order/PaymentInfo, Coupon. Product variants and multi-tenancy are out of scope.

## Global Constraints

- Keep JSON field names and `_id` (ADR-004). Existing response fields keep their types and meaning; new fields are additive.
- New fields are optional on read. Old Mongo documents must load with defaults. No data migration script.
- Module boundary: everything in this plan stays inside `com.sixpack.product` (plus `shared` security config, tests, frontend, docs). `ModularityTests` must stay green.
- Errors use `com.sixpack.shared.ApiException` (`badRequest`, `notFound`, `conflict`); responses use `ApiResponse.of(...)` / `ApiResponse.message(...)`.
- Update semantics for `PUT`: omitted or blank string params mean "keep the current value". Clearing a field is out of scope.
- `category` (the free-text string) stays as-is and still drives `/product/categories` and `?category=` filtering. `categoryId` is the new optional link to the `categories` collection.
- Backend tests: `cd backend && mvn -q -Dtest=<Class> test` (Docker must be running for Testcontainers). Final gate: `mvn verify`, `npm run build`, `npm run lint`.

## Review Focus

1. A legacy `products` document with none of the new fields must still load, list, and be editable (Task 2 and Task 4 tests).
2. Two products with a blank/whitespace `sku` must not collide on the unique index; only real duplicates return 409 (Task 4 test).
3. A `DRAFT` or `ARCHIVED` product must not appear in public listings, categories, or be purchasable through `ProductCatalog` (Task 5 tests).
4. Deleting a category that has sub-categories or products, and setting a parent that creates a cycle (including itself), must be rejected with 409/400, never corrupt the tree (Task 3 tests).
5. `mrp` below `price`, an unknown `status`, 21 tags, or `taxRate` over 100 must return 400, not 500 (Task 4 tests).

---

## File Structure

Backend (`backend/src/main/java/com/sixpack/product/`):

| File | Responsibility |
|---|---|
| `domain/ProductStatus.java` (create) | `DRAFT/ACTIVE/ARCHIVED`, parse and stored-value helpers |
| `domain/ProductDetails.java` (create) | Immutable bundle of the new optional fields, with `EMPTY` |
| `domain/Slugs.java` (create) | Pure `text -> url slug` function |
| `domain/Product.java` (modify) | Add `details` component, keep 7-arg constructor |
| `domain/ProductRepository.java` (modify) | `findBySlug`, `findBySku`, `existsByCategoryId` |
| `infra/mongo/ProductDocument.java`, `MongoProductRepository.java` (modify) | Persist new fields, public-only queries |
| `domain/Category.java`, `domain/CategoryRepository.java` (create) | Category aggregate and port |
| `infra/mongo/CategoryDocument.java`, `MongoCategoryRepository.java` (create) | Mongo adapter |
| `application/CategoryService.java` (create) | Category rules (slug, parent, cycle, delete guards) |
| `application/ProductService.java` (modify) | Merge/validate details, slug and SKU rules, gallery images |
| `application/ProductCatalogService.java` (modify) | Hide non-public products from checkout |
| `web/Uploads.java` (create) | Shared `MultipartFile -> byte[]` helper |
| `web/ProductDetailsForm.java` (create) | Validated multipart form for the new fields |
| `web/ProductController.java`, `ProductView.java` (modify) | Accept and expose new fields |
| `web/CategoryController.java`, `CategoryView.java` (create) | `/api/v1/category` |

Also: `shared/security/SecurityConfig.java` (category rules), `backend/src/test/.../it/IntegrationTest.java` (clean `categories`), new tests, frontend files in Task 7, docs in Task 8.

---

### Task 1: Domain value types (ProductStatus, ProductDetails, Slugs, Product.details)

**Files:**
- Create: `backend/src/main/java/com/sixpack/product/domain/ProductStatus.java`
- Create: `backend/src/main/java/com/sixpack/product/domain/ProductDetails.java`
- Create: `backend/src/main/java/com/sixpack/product/domain/Slugs.java`
- Modify: `backend/src/main/java/com/sixpack/product/domain/Product.java`
- Test: `backend/src/test/java/com/sixpack/product/domain/ProductDomainTest.java`

**Interfaces:**
- Produces:
  - `enum ProductStatus { DRAFT, ACTIVE, ARCHIVED; boolean isPublic(); static ProductStatus fromStored(String); static ProductStatus parse(String) }` (`parse` throws `ApiException` 400)
  - `record ProductDetails(String slug, String sku, String description, String brand, Double mrp, Double taxRate, String hsn, ProductStatus status, List<String> tags, List<String> images, String categoryId)` with `static final ProductDetails EMPTY` and `ProductDetails withImages(List<String>)`
  - `Slugs.of(String) : String`
  - `Product(String id, String name, String photo, double price, int stock, String category, Instant createdAt, ProductDetails details)` plus the old 7-arg constructor, and `Product withDetails(ProductDetails)`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/product/domain/ProductDomainTest.java`:

```java
package com.sixpack.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductDomainTest {

    @Test
    void slugsAreLowercaseAsciiAndDashed() {
        assertThat(Slugs.of("Running Shoe")).isEqualTo("running-shoe");
        assertThat(Slugs.of("  Café  Crème!! ")).isEqualTo("cafe-creme");
        assertThat(Slugs.of("C++ Book (2nd ed.)")).isEqualTo("c-book-2nd-ed");
    }

    @Test
    void slugsFallBackAndAreCapped() {
        assertThat(Slugs.of("***")).isEqualTo("item");
        assertThat(Slugs.of(null)).isEqualTo("item");
        assertThat(Slugs.of("a".repeat(200))).hasSize(80);
    }

    @Test
    void emptyDetailsAreActiveWithEmptyLists() {
        ProductDetails d = ProductDetails.EMPTY;
        assertThat(d.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(d.tags()).isEmpty();
        assertThat(d.images()).isEmpty();
        assertThat(d.slug()).isNull();
    }

    @Test
    void detailsDefaultNullStatusAndLists() {
        ProductDetails d = new ProductDetails(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(d).isEqualTo(ProductDetails.EMPTY);
    }

    @Test
    void legacySevenArgConstructorGetsEmptyDetails() {
        Product p = new Product("1", "Shoe", "uploads/a.png", 10, 2, "footwear", null);
        assertThat(p.details()).isEqualTo(ProductDetails.EMPTY);
        assertThat(p.withDetails(new ProductDetails("s", null, null, null, null, null, null, null,
                List.of("x"), null, null)).details().slug()).isEqualTo("s");
    }

    @Test
    void statusParsingAndVisibility() {
        assertThat(ProductStatus.parse(" draft ")).isEqualTo(ProductStatus.DRAFT);
        assertThat(ProductStatus.fromStored(null)).isEqualTo(ProductStatus.ACTIVE);
        assertThat(ProductStatus.fromStored("garbage")).isEqualTo(ProductStatus.ACTIVE);
        assertThat(ProductStatus.ACTIVE.isPublic()).isTrue();
        assertThat(ProductStatus.DRAFT.isPublic()).isFalse();
        assertThat(ProductStatus.ARCHIVED.isPublic()).isFalse();
        assertThatThrownBy(() -> ProductStatus.parse("bogus")).isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=ProductDomainTest test`
Expected: compilation FAIL (`Slugs`, `ProductDetails`, `ProductStatus` do not exist).

- [ ] **Step 3: Write minimal implementation**

`ProductStatus.java`:

```java
package com.sixpack.product.domain;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** Lifecycle of a catalogue entry. Only ACTIVE products are listed publicly or purchasable. */
public enum ProductStatus {
    DRAFT, ACTIVE, ARCHIVED;

    public boolean isPublic() {
        return this == ACTIVE;
    }

    /** Lenient read of a stored value: legacy documents without a status are ACTIVE. */
    public static ProductStatus fromStored(String stored) {
        if (stored == null) {
            return ACTIVE;
        }
        try {
            return valueOf(stored);
        } catch (IllegalArgumentException e) {
            return ACTIVE;
        }
    }

    /** Strict parse of user input. */
    public static ProductStatus parse(String input) {
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("status: must be one of DRAFT, ACTIVE, ARCHIVED");
        }
    }
}
```

`ProductDetails.java`:

```java
package com.sixpack.product.domain;

import java.util.List;

/** Optional catalogue fields. Legacy products carry {@link #EMPTY}. */
public record ProductDetails(
        String slug,
        String sku,
        String description,
        String brand,
        Double mrp,
        Double taxRate,
        String hsn,
        ProductStatus status,
        List<String> tags,
        List<String> images,
        String categoryId) {

    public static final ProductDetails EMPTY =
            new ProductDetails(null, null, null, null, null, null, null, null, null, null, null);

    public ProductDetails {
        status = status == null ? ProductStatus.ACTIVE : status;
        tags = tags == null ? List.of() : List.copyOf(tags);
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ProductDetails withImages(List<String> newImages) {
        return new ProductDetails(slug, sku, description, brand, mrp, taxRate, hsn, status, tags, newImages,
                categoryId);
    }
}
```

`Slugs.java`:

```java
package com.sixpack.product.domain;

import java.text.Normalizer;
import java.util.Locale;

/** URL-safe slugs: lowercase ASCII letters and digits separated by single dashes, at most 80 characters. */
public final class Slugs {
    private static final int MAX_LENGTH = 80;

    private Slugs() {
    }

    public static String of(String text) {
        if (text == null) {
            return "item";
        }
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "item" : slug;
    }
}
```

Replace `Product.java` (keep the existing imports for `Instant`):

```java
package com.sixpack.product.domain;

import java.time.Instant;

public record Product(String id, String name, String photo, double price, int stock, String category,
                      Instant createdAt, ProductDetails details) {

    public Product {
        details = details == null ? ProductDetails.EMPTY : details;
    }

    /** Pre-details shape, kept so existing callers compile unchanged. */
    public Product(String id, String name, String photo, double price, int stock, String category,
                   Instant createdAt) {
        this(id, name, photo, price, stock, category, createdAt, ProductDetails.EMPTY);
    }

    public Product withDetails(ProductDetails newDetails) {
        return new Product(id, name, photo, price, stock, category, createdAt, newDetails);
    }

    public static String normalizeCategory(String category) {
        return category == null ? null : category.trim().toLowerCase();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=ProductDomainTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/sixpack/product/domain backend/src/test/java/com/sixpack/product
git commit -m "feat(product): add ProductDetails, ProductStatus and Slugs value types"
```

---

### Task 2: Persist the new fields (document, repository, legacy documents)

**Files:**
- Modify: `backend/src/main/java/com/sixpack/product/infra/mongo/ProductDocument.java`
- Modify: `backend/src/main/java/com/sixpack/product/infra/mongo/MongoProductRepository.java` (`save`, `toDomain`, new finders)
- Modify: `backend/src/main/java/com/sixpack/product/domain/ProductRepository.java`
- Test: `backend/src/test/java/com/sixpack/it/ProductDetailsIntegrationTest.java` (create)

**Interfaces:**
- Consumes: Task 1 types.
- Produces: `Optional<Product> findBySlug(String slug)`, `Optional<Product> findBySku(String sku)` on `ProductRepository`. `save` persists and `findById` returns every `ProductDetails` field.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/ProductDetailsIntegrationTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=ProductDetailsIntegrationTest test`
Expected: compilation FAIL (`findBySlug` / `findBySku` undefined).

- [ ] **Step 3: Write minimal implementation**

`ProductRepository.java`: add

```java
    Optional<Product> findBySlug(String slug);

    Optional<Product> findBySku(String sku);
```

`ProductDocument.java`: add these fields (and `java.util.List` import) below `category`:

```java
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
```

`MongoProductRepository.java`: add finders, extend `save`, replace `toDomain`:

```java
    @Override
    public Optional<Product> findBySlug(String slug) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("slug").is(slug)), ProductDocument.class))
                .map(MongoProductRepository::toDomain);
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("sku").is(sku)), ProductDocument.class))
                .map(MongoProductRepository::toDomain);
    }
```

In `save`, after `doc.category = p.category();` add:

```java
        ProductDetails d = p.details();
        doc.slug = d.slug();
        doc.sku = d.sku();
        doc.description = d.description();
        doc.brand = d.brand();
        doc.mrp = d.mrp();
        doc.taxRate = d.taxRate();
        doc.hsn = d.hsn();
        doc.status = d.status().name();
        doc.tags = d.tags();
        doc.images = d.images();
        doc.categoryId = d.categoryId();
```

`toDomain`:

```java
    private static Product toDomain(ProductDocument d) {
        ProductDetails details = new ProductDetails(d.slug, d.sku, d.description, d.brand, d.mrp, d.taxRate, d.hsn,
                ProductStatus.fromStored(d.status), d.tags, d.images, d.categoryId);
        return new Product(d.id, d.name, d.photo, d.price == null ? 0 : d.price, d.stock == null ? 0 : d.stock,
                d.category, d.createdAt, details);
    }
```

Add imports `com.sixpack.product.domain.ProductDetails` and `com.sixpack.product.domain.ProductStatus`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=ProductDetailsIntegrationTest,ProductIntegrationTest test`
Expected: PASS (new tests plus the existing product tests, proving nothing regressed).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(product): persist catalogue detail fields, legacy documents load with defaults"
```

---

### Task 3: Category aggregate and admin CRUD

**Files:**
- Create: `backend/src/main/java/com/sixpack/product/domain/Category.java`, `CategoryRepository.java`
- Create: `backend/src/main/java/com/sixpack/product/infra/mongo/CategoryDocument.java`, `MongoCategoryRepository.java`
- Create: `backend/src/main/java/com/sixpack/product/application/CategoryService.java`
- Create: `backend/src/main/java/com/sixpack/product/web/Uploads.java`, `CategoryController.java`, `CategoryView.java`
- Modify: `backend/src/main/java/com/sixpack/product/domain/ProductRepository.java` (+`existsByCategoryId`), `infra/mongo/MongoProductRepository.java`
- Modify: `backend/src/main/java/com/sixpack/product/web/ProductController.java` (use `Uploads.bytes`)
- Modify: `backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java`
- Modify: `backend/src/test/java/com/sixpack/it/IntegrationTest.java` (clean `categories`)
- Test: `backend/src/test/java/com/sixpack/it/CategoryIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `Slugs.of`, `ProductRepository`.
- Produces:
  - `record Category(String id, String name, String slug, String parentId, String image, Instant createdAt)`
  - `CategoryRepository { Optional<Category> findById(String); Optional<Category> findBySlug(String); List<Category> findAll(); boolean existsByParentId(String); Category save(Category); void deleteById(String); }`
  - `ProductRepository.existsByCategoryId(String) : boolean`
  - `CategoryService { List<Category> all(); Category get(String); Category create(CategoryCommand); Category update(String, CategoryCommand); void delete(String); record CategoryCommand(String name, String parentId, byte[] image) }`
  - HTTP: `GET /api/v1/category` (public) → `{categories:[…]}`; `POST /api/v1/category/new`, `PUT /api/v1/category/{id}`, `DELETE /api/v1/category/{id}` (ADMIN). View fields: `_id, name, slug, parentId, image, createdAt`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/sixpack/it/CategoryIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class CategoryIntegrationTest extends IntegrationTest {

    private String createCategory(String admin, String name, String parentId) throws Exception {
        var req = multipart("/api/v1/category/new").param("name", name).header("Authorization", bearer(admin));
        if (parentId != null) {
            req.param("parentId", parentId);
        }
        MvcResult res = mvc.perform(req).andExpect(status().isCreated()).andReturn();
        return body(res).get("category").get("_id").asText();
    }

    @Test
    void adminCreatesAndAnyoneLists() throws Exception {
        String admin = adminToken();
        createCategory(admin, "Running Shoes", null);

        mvc.perform(get("/api/v1/category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].name").value("Running Shoes"))
                .andExpect(jsonPath("$.categories[0].slug").value("running-shoes"));
    }

    @Test
    void customersCannotWrite() throws Exception {
        String user = register("shopper@test.dev");
        mvc.perform(multipart("/api/v1/category/new").param("name", "X").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateNamesGetDistinctSlugs() throws Exception {
        String admin = adminToken();
        createCategory(admin, "Shoes", null);
        createCategory(admin, "Shoes", null);

        mvc.perform(get("/api/v1/category"))
                .andExpect(jsonPath("$.categories[0].slug").value("shoes"))
                .andExpect(jsonPath("$.categories[1].slug").value("shoes-2"));
    }

    @Test
    void parentMustExist() throws Exception {
        mvc.perform(multipart("/api/v1/category/new").param("name", "Child")
                        .param("parentId", "64b7f0000000000000000000").header("Authorization", bearer(adminToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotDeleteCategoryWithChildren() throws Exception {
        String admin = adminToken();
        String parent = createCategory(admin, "Parent", null);
        createCategory(admin, "Child", parent);

        mvc.perform(delete("/api/v1/category/" + parent).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotDeleteCategoryUsedByProducts() throws Exception {
        String admin = adminToken();
        String category = createCategory(admin, "Used", null);
        mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png",
                                com.sixpack.storage.TestImages.png()))
                        .param("name", "P").param("price", "10").param("stock", "1").param("category", "x")
                        .param("categoryId", category).header("Authorization", bearer(admin)))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/category/" + category).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsParentCycles() throws Exception {
        String admin = adminToken();
        String a = createCategory(admin, "A", null);
        String b = createCategory(admin, "B", a);

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/category/" + a).param("parentId", b)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/category/" + a).param("parentId", a)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesAnUnusedLeaf() throws Exception {
        String admin = adminToken();
        String id = createCategory(admin, "Leaf", null);

        mvc.perform(delete("/api/v1/category/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/category")).andExpect(jsonPath("$.categories.length()").value(0));
    }
}
```

Note: `cannotDeleteCategoryUsedByProducts` needs `categoryId` accepted on product creation, which lands in Task 4. Mark it `@org.junit.jupiter.api.Disabled("enabled in Task 4")` for now and remove the annotation in Task 4 Step 1.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=CategoryIntegrationTest test`
Expected: FAIL (404s: `/api/v1/category` does not exist).

- [ ] **Step 3: Write minimal implementation**

`Category.java`:

```java
package com.sixpack.product.domain;

import java.time.Instant;

public record Category(String id, String name, String slug, String parentId, String image, Instant createdAt) {
}
```

`CategoryRepository.java`:

```java
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
```

`CategoryDocument.java`:

```java
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
```

`MongoCategoryRepository.java`:

```java
package com.sixpack.product.infra.mongo;

import com.sixpack.product.domain.Category;
import com.sixpack.product.domain.CategoryRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoCategoryRepository implements CategoryRepository {

    private final MongoTemplate mongo;

    MongoCategoryRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Category> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, CategoryDocument.class)).map(MongoCategoryRepository::toDomain);
    }

    @Override
    public Optional<Category> findBySlug(String slug) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("slug").is(slug)), CategoryDocument.class))
                .map(MongoCategoryRepository::toDomain);
    }

    @Override
    public List<Category> findAll() {
        return mongo.find(new Query().with(Sort.by("createdAt", "name")), CategoryDocument.class).stream()
                .map(MongoCategoryRepository::toDomain).toList();
    }

    @Override
    public boolean existsByParentId(String parentId) {
        return mongo.exists(Query.query(Criteria.where("parentId").is(parentId)), CategoryDocument.class);
    }

    @Override
    public Category save(Category c) {
        CategoryDocument doc = c.id() == null ? null : mongo.findById(c.id(), CategoryDocument.class);
        if (doc == null) {
            doc = new CategoryDocument();
        }
        doc.id = c.id();
        doc.name = c.name();
        doc.slug = c.slug();
        doc.parentId = c.parentId();
        doc.image = c.image();
        return toDomain(mongo.save(doc));
    }

    @Override
    public void deleteById(String id) {
        mongo.remove(Query.query(Criteria.where("id").is(id)), CategoryDocument.class);
    }

    private static Category toDomain(CategoryDocument d) {
        return new Category(d.id, d.name, d.slug, d.parentId, d.image, d.createdAt);
    }
}
```

`ProductRepository.java`: add `boolean existsByCategoryId(String categoryId);`. `MongoProductRepository.java`: add

```java
    @Override
    public boolean existsByCategoryId(String categoryId) {
        return mongo.exists(Query.query(Criteria.where("categoryId").is(categoryId)), ProductDocument.class);
    }
```

`CategoryService.java`:

```java
package com.sixpack.product.application;

import com.sixpack.product.domain.Category;
import com.sixpack.product.domain.CategoryRepository;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.Slugs;
import com.sixpack.shared.ApiException;
import com.sixpack.storage.FileStorage;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CategoryService {

    public record CategoryCommand(String name, String parentId, byte[] image) {
    }

    private static final int MAX_DEPTH = 20;

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final FileStorage storage;

    CategoryService(CategoryRepository categories, ProductRepository products, FileStorage storage) {
        this.categories = categories;
        this.products = products;
        this.storage = storage;
    }

    public List<Category> all() {
        return categories.findAll();
    }

    public Category get(String id) {
        return categories.findById(id).orElseThrow(() -> ApiException.notFound("Category not found"));
    }

    public Category create(CategoryCommand cmd) {
        String parentId = blankToNull(cmd.parentId());
        if (parentId != null) {
            requireExists(parentId);
        }
        String name = cmd.name().trim();
        String image = cmd.image() == null ? null : storage.storeImage(cmd.image());
        try {
            return categories.save(new Category(null, name, freeSlug(Slugs.of(name)), parentId, image, null));
        } catch (RuntimeException e) {
            storage.delete(image);
            throw e;
        }
    }

    public Category update(String id, CategoryCommand cmd) {
        Category current = get(id);
        String parentId = blankToNull(cmd.parentId());
        if (parentId != null) {
            requireExists(parentId);
            assertNoCycle(id, parentId);
        } else {
            parentId = current.parentId();
        }
        String name = blankToNull(cmd.name()) != null ? cmd.name().trim() : current.name();
        String image = cmd.image() != null ? storage.storeImage(cmd.image()) : current.image();
        Category saved = categories.save(new Category(id, name, current.slug(), parentId, image, current.createdAt()));
        if (current.image() != null && !current.image().equals(image)) {
            storage.delete(current.image());
        }
        return saved;
    }

    public void delete(String id) {
        Category current = get(id);
        if (categories.existsByParentId(id)) {
            throw ApiException.conflict("Category has sub-categories");
        }
        if (products.existsByCategoryId(id)) {
            throw ApiException.conflict("Category is used by products");
        }
        categories.deleteById(id);
        if (current.image() != null) {
            storage.delete(current.image());
        }
    }

    private void requireExists(String parentId) {
        if (categories.findById(parentId).isEmpty()) {
            throw ApiException.badRequest("parentId: category not found");
        }
    }

    /** Walks up from the proposed parent; reaching {@code id} (or an absurdly deep chain) is a cycle. */
    private void assertNoCycle(String id, String newParentId) {
        String cursor = newParentId;
        int hops = 0;
        while (cursor != null) {
            if (cursor.equals(id) || ++hops > MAX_DEPTH) {
                throw ApiException.badRequest("parentId: would create a cycle");
            }
            cursor = categories.findById(cursor).map(Category::parentId).orElse(null);
        }
    }

    private String freeSlug(String base) {
        String candidate = base;
        for (int n = 2; categories.findBySlug(candidate).isPresent(); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
```

`web/Uploads.java`:

```java
package com.sixpack.product.web;

import com.sixpack.shared.ApiException;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

final class Uploads {
    private Uploads() {
    }

    /** @return the file's bytes, or null when no file was sent */
    static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("photo: could not be read");
        }
    }
}
```

In `ProductController.java`: delete the private `bytes(...)` method and replace calls `bytes(photo)` with `Uploads.bytes(photo)` (remove the now unused `IOException` import).

`web/CategoryView.java`:

```java
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
```

`web/CategoryController.java`:

```java
package com.sixpack.product.web;

import com.sixpack.product.application.CategoryService;
import com.sixpack.product.application.CategoryService.CategoryCommand;
import com.sixpack.shared.web.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/category")
class CategoryController {

    private final CategoryService service;

    CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    Map<String, Object> list() {
        return ApiResponse.of("categories", service.all().stream().map(CategoryView::from).toList());
    }

    @PostMapping(value = "/new", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(
            @RequestParam @NotBlank @Size(max = 80) String name,
            @RequestParam(required = false) @Size(max = 64) String parentId,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        var created = service.create(new CategoryCommand(name, parentId, Uploads.bytes(image)));
        return ApiResponse.of("message", "Category created successfully", "category", CategoryView.from(created));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> update(
            @PathVariable String id,
            @RequestParam(required = false) @Size(max = 80) String name,
            @RequestParam(required = false) @Size(max = 64) String parentId,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        var updated = service.update(id, new CategoryCommand(name, parentId, Uploads.bytes(image)));
        return ApiResponse.of("message", "Category updated successfully", "category", CategoryView.from(updated));
    }

    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.message("Category deleted successfully");
    }
}
```

`SecurityConfig.java`: directly after the line `.requestMatchers("/api/v1/product/**").hasRole(ADMIN)` add

```java
                        .requestMatchers(GET, "/api/v1/category", "/api/v1/category/**").permitAll()
                        .requestMatchers("/api/v1/category", "/api/v1/category/**").hasRole(ADMIN)
```

`IntegrationTest.java`: add `"categories"` to the collection list in `cleanDatabase()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=CategoryIntegrationTest,ProductIntegrationTest,ModularityTests test`
Expected: PASS (the `@Disabled` test is skipped).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(product): add Category collection with admin CRUD"
```

---

### Task 4: Accept, validate and expose the new product fields

**Files:**
- Modify: `backend/src/main/java/com/sixpack/product/application/ProductService.java`
- Create: `backend/src/main/java/com/sixpack/product/web/ProductDetailsForm.java`
- Modify: `backend/src/main/java/com/sixpack/product/web/ProductController.java`, `ProductView.java`
- Modify: `backend/src/test/java/com/sixpack/it/CategoryIntegrationTest.java` (remove `@Disabled`)
- Test: `backend/src/test/java/com/sixpack/it/ProductDetailsIntegrationTest.java` (add tests)

**Interfaces:**
- Consumes: Task 1–3 types, `CategoryRepository.findById`, `ProductRepository.findBySlug/findBySku`.
- Produces:
  - `ProductService.DetailsCommand(String slug, String sku, String description, String brand, Double mrp, Double taxRate, String hsn, String status, List<String> tags, String categoryId)` with `static final DetailsCommand NONE`
  - `ProductService.ProductCommand(String name, Double price, Integer stock, String category, byte[] photo, DetailsCommand details)` plus the old 5-arg constructor
  - `ProductView` adds `slug, sku, description, brand, mrp, taxRate, hsn, status, tags, images, categoryId`
  - HTTP: `POST /product/new` and `PUT /product/{id}` accept optional `slug, sku, description, brand, mrp, taxRate, hsn, status, tags (comma separated), categoryId`.

- [ ] **Step 1: Write the failing tests**

Remove the `@Disabled` line from `cannotDeleteCategoryUsedByProducts` in `CategoryIntegrationTest`.

Add to `ProductDetailsIntegrationTest` (and the imports shown):

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
```

```java
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
        create(admin, "A", "sku", "  ").andExpect(status().isCreated()).andExpect(jsonPath("$.product.sku").doesNotExist());
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
        String id = body(create(admin, "Shoe", "brand", "Acme", "sku", "S-9").andReturn()).get("product").get("_id").asText();

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

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id.toHexString()).param("stock", "7")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.stock").value(7))
                .andExpect(jsonPath("$.product.slug").value("old-lamp"))
                .andExpect(jsonPath("$.product.status").value("ACTIVE"));
    }
```

(`body(...)` takes an `MvcResult`; `create(...).andReturn()` returns one.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=ProductDetailsIntegrationTest,CategoryIntegrationTest test`
Expected: FAIL (new params ignored: no `slug` in response, 201 where 400/409 expected).

- [ ] **Step 3: Write minimal implementation**

`ProductService.java`:

Replace the `ProductCommand` record with:

```java
    public record DetailsCommand(String slug, String sku, String description, String brand, Double mrp,
                                 Double taxRate, String hsn, String status, List<String> tags, String categoryId) {
        public static final DetailsCommand NONE =
                new DetailsCommand(null, null, null, null, null, null, null, null, null, null);
    }

    public record ProductCommand(String name, Double price, Integer stock, String category, byte[] photo,
                                 DetailsCommand details) {
        public ProductCommand {
            details = details == null ? DetailsCommand.NONE : details;
        }

        public ProductCommand(String name, Double price, Integer stock, String category, byte[] photo) {
            this(name, price, stock, category, photo, DetailsCommand.NONE);
        }
    }

    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_LENGTH = 30;
```

Inject `CategoryRepository categories` (new constructor parameter and field; import `com.sixpack.product.domain.CategoryRepository`, `ProductDetails`, `ProductStatus`, `Slugs`, `java.util.Objects`, `java.util.LinkedHashSet`, `java.util.Locale`).

In `create`, replace the `products.save(new Product(...))` call so details are merged inside the `try`:

```java
            String name = cmd.name().trim();
            Product saved = products.save(new Product(null, name, photo, cmd.price(), cmd.stock(),
                    Product.normalizeCategory(cmd.category()), null,
                    merge(null, name, ProductDetails.EMPTY, cmd.details(), cmd.price())));
```

In `update`, compute details before storing the new photo, and pass them to the new `Product`:

```java
        Product current = products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
        String name = cmd.name() != null && !cmd.name().isBlank() ? cmd.name().trim() : current.name();
        double price = cmd.price() != null ? cmd.price() : current.price();
        ProductDetails details = merge(id, name, current.details(), cmd.details(), price);
        String photo = current.photo();
        if (cmd.photo() != null) {
            photo = storage.storeImage(cmd.photo());
        }
        Product saved = products.save(new Product(id, name, photo, price,
                cmd.stock() != null ? cmd.stock() : current.stock(),
                cmd.category() != null && !cmd.category().isBlank()
                        ? Product.normalizeCategory(cmd.category()) : current.category(),
                current.createdAt(), details));
```

(The remaining lines of `update` — deleting the old photo, publishing the event, returning `saved` — stay.)

Add these private helpers:

```java
    private ProductDetails merge(String selfId, String name, ProductDetails cur, DetailsCommand c, double price) {
        String sku = pick(c.sku(), cur.sku());
        if (sku != null && !sku.equals(cur.sku()) && takenBySomeoneElse(products.findBySku(sku), selfId)) {
            throw ApiException.conflict("sku: already in use");
        }

        String slug;
        if (hasText(c.slug())) {
            slug = Slugs.of(c.slug());
            if (!slug.equals(cur.slug()) && takenBySomeoneElse(products.findBySlug(slug), selfId)) {
                throw ApiException.conflict("slug: already in use");
            }
        } else if (cur.slug() != null) {
            slug = cur.slug();
        } else {
            slug = freeSlug(Slugs.of(name), selfId);
        }

        Double mrp = c.mrp() != null ? c.mrp() : cur.mrp();
        if (mrp != null && mrp < price) {
            throw ApiException.badRequest("mrp: must not be less than price");
        }

        String categoryId = pick(c.categoryId(), cur.categoryId());
        if (categoryId != null && !categoryId.equals(cur.categoryId()) && categories.findById(categoryId).isEmpty()) {
            throw ApiException.badRequest("categoryId: category not found");
        }

        return new ProductDetails(slug, sku, pick(c.description(), cur.description()), pick(c.brand(), cur.brand()),
                mrp, c.taxRate() != null ? c.taxRate() : cur.taxRate(), pick(c.hsn(), cur.hsn()),
                hasText(c.status()) ? ProductStatus.parse(c.status()) : cur.status(),
                c.tags() != null && !c.tags().isEmpty() ? normalizeTags(c.tags()) : cur.tags(),
                cur.images(), categoryId);
    }

    private String freeSlug(String base, String selfId) {
        String candidate = base;
        for (int n = 2; takenBySomeoneElse(products.findBySlug(candidate), selfId); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    private static boolean takenBySomeoneElse(java.util.Optional<Product> found, String selfId) {
        return found.filter(p -> !Objects.equals(p.id(), selfId)).isPresent();
    }

    private static List<String> normalizeTags(List<String> raw) {
        var tags = new LinkedHashSet<String>();
        for (String t : raw) {
            if (t != null && !t.isBlank()) {
                tags.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (tags.size() > MAX_TAGS || tags.stream().anyMatch(t -> t.length() > MAX_TAG_LENGTH)) {
            throw ApiException.badRequest("tags: at most " + MAX_TAGS + " tags of " + MAX_TAG_LENGTH + " characters");
        }
        return List.copyOf(tags);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** New value when provided (trimmed), otherwise the current one. Blank counts as not provided. */
    private static String pick(String proposed, String current) {
        return hasText(proposed) ? proposed.trim() : current;
    }
```

`web/ProductDetailsForm.java`:

```java
package com.sixpack.product.web;

import com.sixpack.product.application.ProductService.DetailsCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Optional multipart fields shared by product create and update. */
record ProductDetailsForm(
        @Size(max = 80) String slug,
        @Size(max = 60) String sku,
        @Size(max = 5000) String description,
        @Size(max = 60) String brand,
        @PositiveOrZero @DecimalMax("10000000") Double mrp,
        @DecimalMin("0") @DecimalMax("100") Double taxRate,
        @Pattern(regexp = "\\d{4,8}|") String hsn,
        @Pattern(regexp = "(?i)draft|active|archived|") String status,
        @Size(max = 20) List<@Size(max = 30) String> tags,
        @Size(max = 64) String categoryId) {

    DetailsCommand toCommand() {
        return new DetailsCommand(slug, sku, description, brand, mrp, taxRate, hsn, status, tags, categoryId);
    }
}
```

`ProductController.java`: add a `@Valid @ModelAttribute ProductDetailsForm details` parameter (import `jakarta.validation.Valid`, `org.springframework.web.bind.annotation.ModelAttribute`) to `create` and `update`, and pass `details.toCommand()` as the sixth `ProductCommand` argument:

```java
    Map<String, Object> create(
            @RequestParam @NotBlank @Size(max = 120) String name,
            ...existing params...
            @RequestPart("photo") MultipartFile photo,
            @Valid @ModelAttribute ProductDetailsForm details) {
        Product created = service.create(
                new ProductCommand(name, price, stock, category, Uploads.bytes(photo), details.toCommand()));
```

and the same for `update` (`new ProductCommand(name, price, stock, category, Uploads.bytes(photo), details.toCommand())`).

`ProductView.java` (replace):

```java
package com.sixpack.product.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import java.time.Instant;
import java.util.List;

record ProductView(@JsonProperty("_id") String id, String name, String photo, double price, int stock,
                   String category, Instant createdAt, String slug, String sku, String description, String brand,
                   Double mrp, Double taxRate, String hsn, String status, List<String> tags, List<String> images,
                   String categoryId) {
    static ProductView from(Product p) {
        ProductDetails d = p.details();
        return new ProductView(p.id(), p.name(), p.photo(), p.price(), p.stock(), p.category(), p.createdAt(),
                d.slug(), d.sku(), d.description(), d.brand(), d.mrp(), d.taxRate(), d.hsn(), d.status().name(),
                d.tags(), d.images(), d.categoryId());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn -q -Dtest=ProductDetailsIntegrationTest,CategoryIntegrationTest,ProductIntegrationTest,ModularityTests test`
Expected: PASS. If `invalidDetailsAreBadRequestNotServerError` returns 500 for a field-binding failure, check `GlobalExceptionHandler` maps `BindException` (it does, line 35) and that `@Valid` is on the form parameter.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(product): accept and expose slug, sku, brand, pricing, status, tags and category link"
```

---

### Task 5: Hide DRAFT/ARCHIVED products from the public and from checkout

**Files:**
- Modify: `backend/src/main/java/com/sixpack/product/infra/mongo/MongoProductRepository.java` (`findLatest`, `distinctCategories`, `search`)
- Modify: `backend/src/main/java/com/sixpack/product/domain/ProductRepository.java` (javadoc)
- Modify: `backend/src/main/java/com/sixpack/product/application/ProductCatalogService.java` (`findSnapshots`)
- Test: `backend/src/test/java/com/sixpack/it/ProductDetailsIntegrationTest.java` (add)

**Interfaces:**
- Consumes: `ProductStatus.isPublic()`.
- Produces: unchanged signatures. Behaviour: `findLatest`, `distinctCategories`, `search` return only public products (status ACTIVE or absent). `findAll` (admin) and `findById` return all. `ProductCatalog.findSnapshots` omits non-public products, so checkout reports "A product in your cart no longer exists".

- [ ] **Step 1: Write the failing tests**

Add to `ProductDetailsIntegrationTest`:

```java
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

        mvc.perform(get("/api/v1/product/all"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/v1/product/latest"))
                .andExpect(jsonPath("$.products.length()").value(2));
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
```

Add `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=ProductDetailsIntegrationTest test`
Expected: FAIL (`$.total` is 4, snapshots contain the draft).

- [ ] **Step 3: Write minimal implementation**

`MongoProductRepository.java`: add

```java
    /** Matches ACTIVE products and legacy documents with no status at all. */
    private static Criteria publicOnly() {
        return Criteria.where("status").nin(ProductStatus.DRAFT.name(), ProductStatus.ARCHIVED.name());
    }
```

Then change:
- `findLatest`: `return find(new Query(publicOnly()).with(NEWEST_FIRST).limit(limit));`
- `distinctCategories`: `mongo.findDistinct(new Query(publicOnly()), "category", ProductDocument.class, String.class)`
- `search`: start with `Query query = new Query(publicOnly());` instead of `new Query()`.

`ProductRepository.java`: add javadoc to `findLatest`, `distinctCategories`, `search`: `/** Public (ACTIVE) products only. */`, and to `findAll`: `/** Every product regardless of status (admin). */`.

`ProductCatalogService.findSnapshots`: add `.filter(p -> p.details().status().isPublic())` before `.map(...)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn -q -Dtest=ProductDetailsIntegrationTest,ProductIntegrationTest,CheckoutIntegrationTest,DashboardIntegrationTest test`
Expected: PASS (checkout and dashboard unchanged, since legacy and ACTIVE products stay visible).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(product): hide draft and archived products from listings and checkout"
```

---

### Task 6: Gallery images (`images[]`)

**Files:**
- Modify: `backend/src/main/java/com/sixpack/product/application/ProductService.java`
- Modify: `backend/src/main/java/com/sixpack/product/web/ProductDetailsForm.java`
- Test: `backend/src/test/java/com/sixpack/it/ProductDetailsIntegrationTest.java` (add)

**Interfaces:**
- Consumes: `FileStorage.storeImage(byte[]) : String` (throws on non-images), `FileStorage.delete(String)`, `ProductDetails.withImages`.
- Produces: `DetailsCommand` gains a final component `List<byte[]> images`; `NONE` passes `null`. Multipart part `images` (repeatable, max 5) on create/update. Update with any images replaces the whole gallery and deletes the old files; update with none keeps it. Delete product removes gallery files.

- [ ] **Step 1: Write the failing tests**

Add to `ProductDetailsIntegrationTest`:

```java
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
        var tooMany = multipart("/api/v1/product/new").file(image("photo"))
                .param("name", "G").param("price", "10").param("stock", "1").param("category", "x")
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
```

Add `import org.springframework.test.web.servlet.MvcResult;`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=ProductDetailsIntegrationTest test`
Expected: FAIL (`images` is empty / more than five accepted).

- [ ] **Step 3: Write minimal implementation**

`ProductService.java`:

Change `DetailsCommand` to add the last component and update `NONE`:

```java
    public record DetailsCommand(String slug, String sku, String description, String brand, Double mrp,
                                 Double taxRate, String hsn, String status, List<String> tags, String categoryId,
                                 List<byte[]> images) {
        public static final DetailsCommand NONE =
                new DetailsCommand(null, null, null, null, null, null, null, null, null, null, null);
    }
```

In `create`, store the gallery and clean up on failure (the `catch` block already deletes `photo`):

```java
        String photo = storage.storeImage(cmd.photo());
        List<String> gallery = storeAll(cmd.details().images());
        try {
            String name = cmd.name().trim();
            Product saved = products.save(new Product(null, name, photo, cmd.price(), cmd.stock(),
                    Product.normalizeCategory(cmd.category()), null,
                    merge(null, name, ProductDetails.EMPTY, cmd.details(), cmd.price()).withImages(gallery)));
            events.publishEvent(new ProductChangedEvent());
            return saved;
        } catch (RuntimeException e) {
            storage.delete(photo);
            gallery.forEach(storage::delete);
            throw e;
        }
```

In `update`, after computing `details` and the photo, store new gallery images only when sent:

```java
        List<String> oldGallery = current.details().images();
        List<String> gallery = cmd.details().images() == null || cmd.details().images().isEmpty()
                ? oldGallery : storeAll(cmd.details().images());
```

use `details.withImages(gallery)` in the saved `Product`, and after the save, next to the old-photo cleanup:

```java
        if (gallery != oldGallery) {
            oldGallery.forEach(storage::delete);
        }
```

In `delete`, after `storage.delete(current.photo());` add `current.details().images().forEach(storage::delete);`.

Add helper:

```java
    private List<String> storeAll(List<byte[]> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<String> stored = new java.util.ArrayList<>();
        try {
            for (byte[] f : files) {
                stored.add(storage.storeImage(f));
            }
        } catch (RuntimeException e) {
            stored.forEach(storage::delete);
            throw e;
        }
        return stored;
    }
```

`ProductDetailsForm.java`: add a component `@Size(max = 5) List<MultipartFile> images` (import `org.springframework.web.multipart.MultipartFile`) after `categoryId`, and update `toCommand()`:

```java
    DetailsCommand toCommand() {
        List<byte[]> files = images == null ? null : images.stream().map(Uploads::bytes)
                .filter(java.util.Objects::nonNull).toList();
        return new DetailsCommand(slug, sku, description, brand, mrp, taxRate, hsn, status, tags, categoryId, files);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn -q -Dtest=ProductDetailsIntegrationTest,ProductIntegrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(product): optional gallery images with replace and cleanup semantics"
```

---

### Task 7: Admin form and TypeScript types

**Files:**
- Modify: `ecommerce-frontend/src/types/types.ts` (`Product`)
- Modify: `ecommerce-frontend/src/components/admin/productFormData.ts`
- Modify: `ecommerce-frontend/src/components/admin/ProductForm.tsx`
- Modify: `ecommerce-frontend/src/pages/admin/management/newproduct.tsx`, `productmanagement.tsx`

**Interfaces:**
- Consumes: backend fields from Task 4 (`sku, brand, description, mrp, status, tags`). Gallery upload and category management UI are intentionally left for a later plan; `categoryId`, `slug`, `hsn`, `taxRate` stay API-only for now.
- Produces: `emptyProductForm: ProductFormValues` exported from `productFormData.ts`.

- [ ] **Step 1: Types and form data**

In `types.ts`, extend `Product`:

```ts
export type Product = {
  _id: string;
  name: string;
  price: number;
  stock: number;
  category: string;
  photo: string;
  createdAt?: string;
  slug?: string;
  sku?: string;
  description?: string;
  brand?: string;
  mrp?: number;
  status?: "DRAFT" | "ACTIVE" | "ARCHIVED";
  tags?: string[];
  images?: string[];
};
```

Replace `productFormData.ts`:

```ts
export type ProductFormValues = {
  name: string;
  price: string;
  stock: string;
  category: string;
  sku: string;
  brand: string;
  description: string;
  mrp: string;
  status: string;
  tags: string;
  photo?: File;
};

export const emptyProductForm: ProductFormValues = {
  name: "",
  price: "",
  stock: "",
  category: "",
  sku: "",
  brand: "",
  description: "",
  mrp: "",
  status: "ACTIVE",
  tags: "",
  photo: undefined,
};

export const toFormData = (v: ProductFormValues) => {
  const fd = new FormData();
  if (v.name) fd.set("name", v.name);
  if (v.price !== "") fd.set("price", v.price);
  if (v.stock !== "") fd.set("stock", v.stock);
  if (v.category) fd.set("category", v.category);
  if (v.sku) fd.set("sku", v.sku);
  if (v.brand) fd.set("brand", v.brand);
  if (v.description) fd.set("description", v.description);
  if (v.mrp !== "") fd.set("mrp", v.mrp);
  if (v.status) fd.set("status", v.status);
  if (v.tags) fd.set("tags", v.tags);
  if (v.photo) fd.set("photo", v.photo);
  return fd;
};
```

- [ ] **Step 2: Wire the pages**

`newproduct.tsx`: import `emptyProductForm` alongside `toFormData` and replace the local `empty` constant: delete the `const empty ...` line and use `useState<ProductFormValues>(emptyProductForm)`.

`productmanagement.tsx`: import `emptyProductForm`; initialise `useState<ProductFormValues>(emptyProductForm)`; in the `useEffect`, set:

```ts
      setValues({
        ...emptyProductForm,
        name: product.name,
        price: String(product.price),
        stock: String(product.stock),
        category: product.category,
        sku: product.sku ?? "",
        brand: product.brand ?? "",
        description: product.description ?? "",
        mrp: product.mrp != null ? String(product.mrp) : "",
        status: product.status ?? "ACTIVE",
        tags: (product.tags ?? []).join(", "),
      });
```

- [ ] **Step 3: Form inputs**

In `ProductForm.tsx`, change the `set` helper to accept inputs, selects and textareas:

```ts
  const set = (field: keyof ProductFormValues) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    onChange({ ...values, [field]: e.target.value });
```

and add these fields inside `<form>` before the photo `<label className="span-2">`:

```tsx
        <label>
          SKU
          <input value={values.sku} onChange={set("sku")} maxLength={60} />
        </label>
        <label>
          Brand
          <input value={values.brand} onChange={set("brand")} maxLength={60} />
        </label>
        <label>
          MRP (₹)
          <input type="number" min={0} step="0.01" value={values.mrp} onChange={set("mrp")} />
        </label>
        <label>
          Status
          <select value={values.status} onChange={set("status")}>
            <option value="ACTIVE">Active</option>
            <option value="DRAFT">Draft (hidden)</option>
            <option value="ARCHIVED">Archived (hidden)</option>
          </select>
        </label>
        <label className="span-2">
          Tags (comma separated)
          <input value={values.tags} onChange={set("tags")} placeholder="e.g. summer, sale" />
        </label>
        <label className="span-2">
          Description
          <textarea value={values.description} onChange={set("description")} rows={3} maxLength={5000} />
        </label>
```

The existing `required={!currentPhoto}` attributes on name/category/price/stock stay as they are; the new fields are all optional.

- [ ] **Step 4: Verify**

Run: `cd ecommerce-frontend && npm run build && npm run lint`
Expected: both succeed with no type errors. Then start the stack (`docker compose up -d --build`), create a product with a SKU, brand and `Draft` status in the admin UI, confirm it is absent from the storefront and present in the admin list, edit it back to `Active`, and confirm it appears.

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend/src
git commit -m "feat(admin): edit sku, brand, mrp, status, tags and description on products"
```

---

### Task 8: Docs and full verification

**Files:**
- Modify: `docs/foundations/contracts.md`, `requirements.md`, `architecture.md`, `test-rollout.md`

- [ ] **Step 1: Update contracts**

In `contracts.md`, under the product section, document the new optional multipart params on `POST /new` and `PUT /{id}` (`slug, sku, description, brand, mrp, taxRate, hsn, status, tags, categoryId, images`), the new response fields on every product object, the 409 for duplicate `sku`/`slug`, the 400 rules (`mrp ≥ price`, `taxRate` 0–100, `hsn` 4–8 digits, at most 20 tags of 30 characters, at most 5 gallery images), and the fact that DRAFT/ARCHIVED products are excluded from `/latest`, `/all`, `/categories` but returned by `/admin-products` and `/{id}`. Add a `/api/v1/category` table: `GET /` 🔓, `POST /new`, `PUT /{id}`, `DELETE /{id}` 🛡, with the 409 rules for sub-categories and referenced categories and the 400 for cycles.

- [ ] **Step 2: Requirements, ADR, rollout**

- `requirements.md`: add `FR` lines for catalogue fields, category management, and draft visibility.
- `architecture.md`: add `ADR-015` (newest first, dated 2026-10-17): "**Catalogue fields as an additive nested `ProductDetails`; `Category` inside the product module; no migration.**" Record: why nested (old constructor and call sites unchanged), why legacy docs default to ACTIVE, why sparse unique indexes for slug/sku, public queries use `status nin [DRAFT, ARCHIVED]` so missing status stays visible, and that multi-tenancy remains a parked future plan (see the spec appendix). Add `Category` to the product module's row in the module table.
- `test-rollout.md`: note the new ITs (`ProductDetailsIntegrationTest`, `CategoryIntegrationTest`) and that rollout needs no migration, only that `auto-index-creation` builds the new sparse indexes on first start.

- [ ] **Step 3: Full verification**

Run, from the repo root:

```bash
cd backend && mvn verify
cd ../ecommerce-frontend && npm run build && npm run lint
```

Expected: all green, including `ModularityTests` and the pre-existing integration tests. If anything fails, fix it before the commit; do not skip tests.

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs: document catalogue fields, categories and ADR-015"
```

---

## Self-Review

- **Spec coverage (§4 Product + Category rows, Tier 1):** `slug, sku, description, brand, images[], mrp, price(existing), taxRate, hsn, status, tags[], categoryId` → Tasks 1, 2, 4, 6. `Category{name, slug, parentId, image}` → Task 3. Draft visibility (implied by `status`) → Task 5. Admin form → Task 7. Docs → Task 8. Deliberately not here: User/Address, Order, Coupon (later plans), variants and Tier 2 fields, a category management UI and gallery upload UI (follow-up frontend plan).
- **Placeholder scan:** none; every code step contains the code. The one conditional (`@Disabled` in Task 3, lifted in Task 4) is explicit.
- **Type consistency:** `ProductDetails` 11 components (Task 1) match `MongoProductRepository.toDomain`, `ProductView`, and `ProductService.merge`. `DetailsCommand` has 10 components in Task 4 and 11 in Task 6, and Task 6 shows the updated record and `NONE`; `ProductDetailsForm.toCommand()` is updated in the same task. `ProductCommand` keeps its 5-argument constructor. `Uploads.bytes` introduced in Task 3 is used by Tasks 3 and 6.
- **Review Focus coverage:** 1 → Task 2 (legacy load) and Task 4 (`legacyProductCanBeEditedAndGainsASlug`); 2 → Task 4 (`blankSkuNeverCollides`, `duplicateSkuIsConflict`); 3 → Task 5; 4 → Task 3; 5 → Task 4 (`invalidDetailsAreBadRequestNotServerError`).
