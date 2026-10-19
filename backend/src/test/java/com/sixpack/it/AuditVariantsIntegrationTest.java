package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AuditVariantsIntegrationTest extends IntegrationTest {

    private static Map<String, Object> axis(String name, String type, String... labels) {
        return Map.of("name", name, "type", type, "values",
                java.util.Arrays.stream(labels).map(l -> Map.of("label", l)).toList());
    }

    private static Map<String, Object> variant(String sku, double price, int stock, String size, String colour) {
        var m = new HashMap<String, Object>();
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

    private String firstVariantId(String productId) throws Exception {
        return body(mvc.perform(get("/api/v1/product/" + productId)).andExpect(status().isOk()).andReturn())
                .get("product").get("variants").get("items").get(0).get("id").asText();
    }

    @Test
    void replacingVariantsRecordsPriceAndStockChanges() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        String id = createProduct(admin, "T-Shirt", 1, 1, "clothing");
        assertThat(auditEntries("PRODUCT_UPDATED")).isEmpty();

        putVariants(admin, id, shirtBody()).andExpect(status().isOk());

        List<Document> entries = auditEntries("PRODUCT_UPDATED");
        assertThat(entries).hasSize(1);
        Document entry = entries.get(0);
        assertThat(entry.getString("entityType")).isEqualTo("product");
        assertThat(entry.getString("entityId")).isEqualTo(id);
        assertThat(entry.getString("actorId")).isEqualTo(adminId);
        assertThat(entry.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(changeOf(entry, "variantStocks").get("before")).isNull();
        assertThat(changeOf(entry, "variantStocks").get("after").toString())
                .contains("TS-S-BLK", "TS-M-BLK", "TS-S-WHT");
        assertThat(changeOf(entry, "variantPrices").get("after").toString())
                .contains("TS-S-BLK", "TS-M-BLK", "TS-S-WHT");
        assertThat(changeOf(entry, "price").get("before")).isEqualTo(1.0);
        assertThat(changeOf(entry, "price").get("after")).isEqualTo(499.0);
        assertThat(changeOf(entry, "stock").get("before")).isEqualTo(1);
        assertThat(changeOf(entry, "stock").get("after")).isEqualTo(10);
    }

    @Test
    void aDeltaStockAdjustmentRecordsTheDelta() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "T-Shirt", 1, 1, "clothing");
        putVariants(admin, id, shirtBody()).andExpect(status().isOk());
        String variantId = firstVariantId(id);

        patchStock(admin, id, variantId, Map.of("delta", 4)).andExpect(status().isOk());

        List<Document> entries = auditEntries("PRODUCT_UPDATED");
        assertThat(entries).hasSize(2);
        Document entry = entries.get(0);
        assertThat(entry.getString("entityId")).isEqualTo(id);
        assertThat(entry.getString("actorId")).isEqualTo(userId(admin));
        assertThat(entry.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactlyInAnyOrder("variantStocks", "stock");
        assertThat(changeOf(entry, "variantStocks").get("before").toString()).contains("TS-S-BLK=5");
        assertThat(changeOf(entry, "variantStocks").get("after").toString()).contains("TS-S-BLK=9");
        assertThat(changeOf(entry, "stock").get("before")).isEqualTo(10);
        assertThat(changeOf(entry, "stock").get("after")).isEqualTo(14);
    }

    @Test
    void aSetStockAdjustmentIsRecordedToo() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "T-Shirt", 1, 1, "clothing");
        putVariants(admin, id, shirtBody()).andExpect(status().isOk());
        String variantId = firstVariantId(id);

        patchStock(admin, id, variantId, Map.of("set", 1)).andExpect(status().isOk());

        List<Document> entries = auditEntries("PRODUCT_UPDATED");
        assertThat(entries).hasSize(2);
        assertThat(changeOf(entries.get(0), "variantStocks").get("after").toString()).contains("TS-S-BLK=1");
        assertThat(changeOf(entries.get(0), "stock").get("after")).isEqualTo(6);
    }

    @Test
    void refusedVariantWritesLeaveNoEntry() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "T-Shirt", 1, 1, "clothing");
        putVariants(admin, id, shirtBody()).andExpect(status().isOk());
        String variantId = firstVariantId(id);
        int before = auditEntries("PRODUCT_UPDATED").size();

        patchStock(admin, id, variantId, Map.of("delta", -50)).andExpect(status().isConflict());
        patchStock(admin, id, variantId, Map.of("delta", 1, "set", 1)).andExpect(status().isBadRequest());
        patchStock(admin, "000000000000000000000000", variantId, Map.of("delta", 1))
                .andExpect(status().isNotFound());
        putVariants(admin, "000000000000000000000000", shirtBody()).andExpect(status().isNotFound());
        putVariants(admin, id, Map.of("axes", List.of(), "variants", List.of(variant("A", 1, 1, "S", "Black"))))
                .andExpect(status().isBadRequest());

        assertThat(auditEntries("PRODUCT_UPDATED")).hasSize(before);
    }

    @Test
    void anOrdinaryProductEditStillListsOnlyTheChangedField() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "Lamp", 400, 3, "home");

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).param("price", "450")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());

        assertThat(auditEntries("PRODUCT_UPDATED").get(0).getList("changes", Document.class))
                .extracting(c -> c.getString("field")).containsExactly("price");
    }
}
