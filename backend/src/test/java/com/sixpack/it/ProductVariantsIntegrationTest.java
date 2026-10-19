package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.Variant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class ProductVariantsIntegrationTest extends IntegrationTest {

    @Autowired
    ProductRepository products;

    private static Map<String, Object> axis(String name, String type, String... labels) {
        return Map.of("name", name, "type", type, "values",
                java.util.Arrays.stream(labels).map(l -> Map.of("label", l)).toList());
    }

    private static Map<String, Object> variant(String sku, double price, int stock, String size, String colour) {
        var m = new java.util.HashMap<String, Object>();
        m.put("sku", sku);
        m.put("price", price);
        m.put("stock", stock);
        m.put("options", List.of(Map.of("name", "Size", "value", size), Map.of("name", "Colour", "value", colour)));
        return m;
    }

    private Map<String, Object> shirtBody() {
        return Map.of("axes", List.of(axis("Size", "text", "S", "M"), axis("Colour", "colour", "Black", "White")),
                "variants", List.of(variant("TS-S-BLK", 499, 5, "S", "Black"),
                        variant("TS-M-BLK", 549, 3, "M", "Black"), variant("TS-S-WHT", 520, 2, "S", "White")));
    }

    private ResultActions putVariants(String token, String productId, Object body) throws Exception {
        return mvc.perform(put("/api/v1/product/" + productId + "/variants").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)).header("Authorization", bearer(token)));
    }

    private ResultActions patchStock(String token, String productId, String variantId, Object body)
            throws Exception {
        return mvc.perform(patch("/api/v1/product/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                .header("Authorization", bearer(token)));
    }

    private JsonNode product(String id) throws Exception {
        return body(mvc.perform(get("/api/v1/product/" + id)).andExpect(status().isOk()).andReturn()).get("product");
    }

    private String shirt(String token) throws Exception {
        String id = createProduct(token, "T-Shirt", 1, 1, "clothing");
        putVariants(token, id, shirtBody()).andExpect(status().isOk());
        return id;
    }

    @Test
    void simpleAndLegacyProductsExposeEmptyVariants() throws Exception {
        ObjectId id = new ObjectId();
        mongo.getCollection("products").insertOne(new Document("_id", id).append("name", "Old")
                .append("photo", "uploads/o.png").append("price", 10.0).append("stock", 2)
                .append("category", "misc"));

        mvc.perform(get("/api/v1/product/" + id.toHexString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.variants.axes.length()").value(0))
                .andExpect(jsonPath("$.product.variants.items.length()").value(0))
                .andExpect(jsonPath("$.product.stock").value(2));
    }

    @Test
    void savingVariantsDerivesPriceAndStockAndGeneratesIds() throws Exception {
        String token = adminToken();
        String id = createProduct(token, "T-Shirt", 1, 1, "clothing");

        putVariants(token, id, shirtBody()).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.price").value(499.0))
                .andExpect(jsonPath("$.product.stock").value(10))
                .andExpect(jsonPath("$.product.variants.axes.length()").value(2))
                .andExpect(jsonPath("$.product.variants.items.length()").value(3))
                .andExpect(jsonPath("$.product.variants.items[0].label").value("S / Black"))
                .andExpect(jsonPath("$.product.variants.items[0].sku").value("TS-S-BLK"))
                .andExpect(jsonPath("$.product.variants.items[0].id").isNotEmpty());

        JsonNode loaded = product(id);
        assertThat(loaded.get("price").asDouble()).isEqualTo(499.0);
        assertThat(loaded.get("variants").get("axes").get(1).get("type").asText()).isEqualTo("COLOUR");
    }

    @Test
    void generatesSkusWhenBlank() throws Exception {
        String token = adminToken();
        String id = createProduct(token, "T-Shirt", 1, 1, "clothing");
        var noSku = variant(null, 100, 1, "S", "Black");

        putVariants(token, id, Map.of("axes", List.of(axis("Size", "text", "S"), axis("Colour", "colour", "Black")),
                "variants", List.of(noSku))).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.variants.items[0].sku").value("T-SHIRT-S-BLACK"));
    }

    @Test
    void onlyAdminsMayEditVariants() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "T-Shirt", 1, 1, "clothing");
        String user = register("shopper@test.dev");

        putVariants(user, id, shirtBody()).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/product/" + id + "/variants").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shirtBody()))).andExpect(status().isUnauthorized());
        patchStock(user, id, "x", Map.of("delta", 1)).andExpect(status().isForbidden());
    }

    @Test
    void invalidVariantsAreRejectedWithoutChangingTheProduct() throws Exception {
        String token = adminToken();
        String id = shirt(token);

        putVariants(token, id, Map.of("axes", List.of(axis("Size", "text", "S")),
                "variants", List.of(Map.of("price", 10, "options", List.of(Map.of("name", "Size", "value", "XL"))))))
                .andExpect(status().isBadRequest());
        putVariants(token, id, Map.of("axes", List.of(), "variants", List.of(variant("A", 1, 1, "S", "Black"))))
                .andExpect(status().isBadRequest());
        putVariants(token, "000000000000000000000000", shirtBody()).andExpect(status().isNotFound());

        assertThat(product(id).get("variants").get("items")).hasSize(3);
    }

    @Test
    void editingKeepsLiveStockAndIds() throws Exception {
        String token = adminToken();
        String id = shirt(token);
        JsonNode first = product(id).get("variants").get("items").get(0);
        String variantId = first.get("id").asText();

        // a sale lands, then the admin saves a stale copy that still says stock 5
        assertThat(products.adjustVariantStock(id, variantId, -2)).isTrue();
        var edit = new java.util.HashMap<>(variant("TS-S-BLK", 450, 5, "S", "Black"));
        edit.put("id", variantId);
        putVariants(token, id, Map.of("axes", List.of(axis("Size", "text", "S", "M"),
                axis("Colour", "colour", "Black", "White")), "variants", List.of(edit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.variants.items[0].id").value(variantId))
                .andExpect(jsonPath("$.product.variants.items[0].stock").value(3))
                .andExpect(jsonPath("$.product.variants.items[0].price").value(450.0))
                .andExpect(jsonPath("$.product.price").value(450.0))
                .andExpect(jsonPath("$.product.stock").value(3));
    }

    @Test
    void skusAreUniqueAcrossProductsAndBetweenProductAndVariantSkus() throws Exception {
        String token = adminToken();
        shirt(token);   // owns TS-S-BLK, TS-M-BLK, TS-S-WHT
        String other = createProduct(token, "Polo", 1, 1, "clothing");

        putVariants(token, other, Map.of("axes", List.of(axis("Size", "text", "S"), axis("Colour", "colour", "Black")),
                "variants", List.of(variant("TS-S-BLK", 100, 1, "S", "Black")))).andExpect(status().isConflict());

        // a product-level SKU may not take a variant's SKU either
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + other).param("sku", "TS-M-BLK")
                .header("Authorization", bearer(token))).andExpect(status().isConflict());
        // ...and a variant may not reuse a product-level SKU
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + other).param("sku", "POLO-1")
                .header("Authorization", bearer(token))).andExpect(status().isOk());
        String third = createProduct(token, "Cap", 1, 1, "clothing");
        putVariants(token, third, Map.of("axes", List.of(axis("Size", "text", "S"), axis("Colour", "colour", "Black")),
                "variants", List.of(variant("POLO-1", 100, 1, "S", "Black")))).andExpect(status().isConflict());
    }

    @Test
    void theDatabaseItselfRejectsTwoProductsSharingAVariantSku() {
        Document variantDoc = new Document("variantId", "a").append("sku", "DUP-1").append("price", 1.0)
                .append("stock", 1);
        mongo.getCollection("products").insertOne(new Document("name", "A").append("price", 1.0).append("stock", 1)
                .append("variants", new Document("items", List.of(variantDoc))));
        // products without variants never collide
        mongo.getCollection("products").insertOne(new Document("name", "Legacy 1").append("price", 1.0));
        mongo.getCollection("products").insertOne(new Document("name", "Legacy 2").append("price", 1.0));

        assertThatThrownBy(() -> mongo.getCollection("products").insertOne(new Document("name", "B")
                .append("price", 1.0).append("stock", 1)
                .append("variants", new Document("items", List.of(new Document(variantDoc))))))
                .isInstanceOf(com.mongodb.MongoWriteException.class)
                .hasMessageContaining("duplicate key");
    }

    @Test
    void stockCanBeAdjustedByDeltaOrSetAndNeverGoesNegative() throws Exception {
        String token = adminToken();
        String id = shirt(token);
        String variantId = product(id).get("variants").get("items").get(0).get("id").asText();   // stock 5

        patchStock(token, id, variantId, Map.of("delta", 4)).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.variants.items[0].stock").value(9))
                .andExpect(jsonPath("$.product.stock").value(14));
        patchStock(token, id, variantId, Map.of("set", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.variants.items[0].stock").value(1))
                .andExpect(jsonPath("$.product.stock").value(6));
        patchStock(token, id, variantId, Map.of("delta", -2)).andExpect(status().isConflict());
        patchStock(token, id, variantId, Map.of()).andExpect(status().isBadRequest());
        patchStock(token, id, variantId, Map.of("delta", 1, "set", 1)).andExpect(status().isBadRequest());
        patchStock(token, id, variantId, Map.of("set", -1)).andExpect(status().isBadRequest());
        patchStock(token, id, "missing", Map.of("delta", 1)).andExpect(status().isNotFound());
        assertThat(product(id).get("stock").asInt()).isEqualTo(6);
    }

    @Test
    void plainProductEditsKeepVariantsAndIgnorePriceAndStock() throws Exception {
        String token = adminToken();
        String id = shirt(token);

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).param("name", "Renamed Shirt")
                        .param("price", "9999").param("stock", "9999").param("countryOfOrigin", "India")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.name").value("Renamed Shirt"))
                .andExpect(jsonPath("$.product.countryOfOrigin").value("India"))
                .andExpect(jsonPath("$.product.price").value(499.0))
                .andExpect(jsonPath("$.product.stock").value(10))
                .andExpect(jsonPath("$.product.variants.items.length()").value(3));
    }

    @Test
    void sendingNoVariantsMakesTheProductSimpleAgain() throws Exception {
        String token = adminToken();
        String id = shirt(token);

        putVariants(token, id, Map.of("axes", List.of(), "variants", List.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.variants.items.length()").value(0))
                .andExpect(jsonPath("$.product.price").value(499.0))
                .andExpect(jsonPath("$.product.stock").value(10));
        assertThat(mongo.getCollection("products").find(new Document("_id", new ObjectId(id))).first())
                .doesNotContainKey("variants");
    }

    @Test
    void concurrentSalesOfTheLastUnitsNeverOversellAndKeepTheTotalInStep() throws Exception {
        String token = adminToken();
        String id = shirt(token);
        Variant target = products.findById(id).orElseThrow().details().variants().items().get(2);   // stock 2
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger sold = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(pool.submit(() -> {
                if (products.adjustVariantStock(id, target.id(), -1)) {
                    sold.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        Product after = products.findById(id).orElseThrow();
        assertThat(sold.get()).isEqualTo(2);
        assertThat(after.details().variants().find(target.id()).orElseThrow().stock()).isZero();
        assertThat(after.stock()).isEqualTo(after.details().variants().totalStock()).isEqualTo(8);
    }

    @Test
    void savingTheVariantStructureWhileSalesHappenNeverLosesASale() throws Exception {
        String token = adminToken();
        String id = createProduct(token, "T-Shirt", 1, 1, "clothing");
        putVariants(token, id, Map.of("axes", List.of(axis("Size", "text", "S"), axis("Colour", "colour", "Black")),
                "variants", List.of(variant("TS-S-BLK", 100, 1000, "S", "Black")))).andExpect(status().isOk());
        String variantId = products.findById(id).orElseThrow().details().variants().items().get(0).id();
        var edit = new java.util.HashMap<>(variant("TS-S-BLK", 100, 1000, "S", "Black"));
        edit.put("id", variantId);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger sold = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            futures.add(pool.submit(() -> {
                if (products.adjustVariantStock(id, variantId, -1)) {
                    sold.incrementAndGet();
                }
            }));
            if (i % 10 == 0) {
                futures.add(pool.submit(() -> {
                    try {
                        // a 409 under heavy contention is acceptable; a lost sale is not
                        putVariants(token, id, Map.of("axes", List.of(axis("Size", "text", "S"),
                                axis("Colour", "colour", "Black")), "variants", List.of(edit)));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        Product after = products.findById(id).orElseThrow();
        assertThat(sold.get()).isEqualTo(150);
        assertThat(after.stock()).isEqualTo(1000 - 150);
        assertThat(after.details().variants().totalStock()).isEqualTo(850);
    }
}
