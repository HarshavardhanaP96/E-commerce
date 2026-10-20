package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ProductStockAndSizeGuideIntegrationTest extends IntegrationTest {

    private static final Map<String, Object> CHART = Map.of(
            "columns", List.of("Size", "Chest (in)", "Length (in)"),
            "rows", List.of(List.of("S", "38", "27"), List.of("M", "40", "28")),
            "note", "Measured flat across the chest.");

    private ResultActions putChart(String token, String id, Object body) throws Exception {
        return mvc.perform(put("/api/v1/product/" + id + "/size-chart").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(token)).content(json.writeValueAsString(body)));
    }

    private ResultActions edit(String token, String id, String... params) throws Exception {
        var req = multipart(HttpMethod.PUT, "/api/v1/product/" + id);
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req.header("Authorization", bearer(token)));
    }

    @Test
    void sizeGuideIsSavedShownAndRemoved() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "Tee", 499, 10, "clothing");

        putChart(admin, id, CHART).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sizeChart.columns[1]").value("Chest (in)"))
                .andExpect(jsonPath("$.product.sizeChart.rows[1][2]").value("28"))
                .andExpect(jsonPath("$.product.sizeChart.note").value("Measured flat across the chest."));
        // a plain edit keeps the chart
        edit(admin, id, "name", "Classic Tee").andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sizeChart.rows.length()").value(2));
        mvc.perform(get("/api/v1/product/" + id)).andExpect(jsonPath("$.product.sizeChart.columns[0]").value("Size"));

        putChart(admin, id, Map.of("columns", List.of("Size"), "rows", List.of(List.of("S", "extra"))))
                .andExpect(status().isBadRequest());
        putChart(admin, id, Map.of("columns", List.of(), "rows", List.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sizeChart").doesNotExist());
        putChart(register("shopper@test.dev"), id, CHART).andExpect(status().isForbidden());
    }

    @Test
    void legacyProductsHaveNoGuideAndUseTheStoreThreshold() throws Exception {
        ObjectId id = new ObjectId();
        mongo.getCollection("products").insertOne(new Document("_id", id).append("name", "Old")
                .append("photo", "uploads/o.png").append("price", 10.0).append("stock", 2).append("category", "misc"));
        backfillLegacy();

        mvc.perform(get("/api/v1/product/" + id.toHexString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sizeChart").doesNotExist())
                .andExpect(jsonPath("$.product.lowStockThreshold").doesNotExist())
                .andExpect(jsonPath("$.product.lowStockAt").value(5));
    }

    @Test
    void thresholdIsAProductFieldWithValidation() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "Mug", 100, 10, "home");

        edit(admin, id, "lowStockThreshold", "12").andExpect(status().isOk())
                .andExpect(jsonPath("$.product.lowStockThreshold").value(12))
                .andExpect(jsonPath("$.product.lowStockAt").value(12));
        edit(admin, id, "name", "Big Mug").andExpect(status().isOk())
                .andExpect(jsonPath("$.product.lowStockThreshold").value(12));   // blank keeps it
        edit(admin, id, "lowStockThreshold", "-1").andExpect(status().isBadRequest());
    }

    private static Map<String, Object> variant(String sku, int stock, String size, String status) {
        var m = new HashMap<String, Object>();
        m.put("sku", sku);
        m.put("price", 100.0);
        m.put("stock", stock);
        m.put("status", status);
        m.put("options", List.of(Map.of("name", "Size", "value", size)));
        return m;
    }

    @Test
    void lowStockListsSellableLinesAtOrBelowTheirThresholdEmptiestFirst() throws Exception {
        String admin = adminToken();
        createProduct(admin, "Plenty", 100, 50, "home");
        String few = createProduct(admin, "Few Mugs", 100, 3, "home");             // 3 <= default 5
        String watched = createProduct(admin, "Watched Pan", 100, 8, "home");      // 8 <= own 10
        edit(admin, watched, "lowStockThreshold", "10").andExpect(status().isOk());
        String draft = createProduct(admin, "Draft Pot", 100, 0, "home");
        edit(admin, draft, "status", "DRAFT").andExpect(status().isOk());
        String tee = createProduct(admin, "Tee", 100, 1, "clothing");
        mvc.perform(put("/api/v1/product/" + tee + "/variants").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(admin))
                .content(json.writeValueAsString(Map.of(
                        "axes", List.of(Map.of("name", "Size", "values",
                                List.of(Map.of("label", "S"), Map.of("label", "M"), Map.of("label", "L")))),
                        "variants", List.of(variant("TEE-S", 0, "S", "ACTIVE"), variant("TEE-M", 20, "M", "ACTIVE"),
                                variant("TEE-L", 1, "L", "DISABLED"))))))
                .andExpect(status().isOk());

        JsonNode alerts = body(mvc.perform(get("/api/v1/product/low-stock").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn()).get("alerts");

        List<String> lines = new ArrayList<>();
        alerts.forEach(a -> lines.add(a.get("name").asText() + (a.get("label").isNull() ? "" : " " + a.get("label").asText())
                + " " + a.get("stock").asInt() + "/" + a.get("threshold").asInt() + (a.get("out").asBoolean() ? " out" : "")));
        assertThat(lines).containsExactly("Tee S 0/5 out", "Few Mugs 3/5", "Watched Pan 8/10");
        assertThat(alerts.get(0).get("sku").asText()).isEqualTo("TEE-S");
        assertThat(alerts.get(1).get("productId").asText()).isEqualTo(few);
    }

    @Test
    void lowStockIsForAdminsOnly() throws Exception {
        mvc.perform(get("/api/v1/product/low-stock")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/product/low-stock").header("Authorization", bearer(register("nosy@test.dev"))))
                .andExpect(status().isForbidden());
    }
}
