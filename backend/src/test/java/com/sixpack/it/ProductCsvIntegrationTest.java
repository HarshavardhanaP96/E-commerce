package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class ProductCsvIntegrationTest extends IntegrationTest {

    private String admin;
    private String mug;
    private String tee;
    private String teeS;
    private String teeM;

    private static Map<String, Object> variant(String sku, double price, int stock, String size) {
        var m = new HashMap<String, Object>();
        m.put("sku", sku);
        m.put("price", price);
        m.put("stock", stock);
        m.put("options", List.of(Map.of("name", "Size", "value", size)));
        return m;
    }

    @BeforeEach
    void catalogue() throws Exception {
        admin = adminToken();
        mug = createProduct(admin, "Mug", 300, 10, "home");
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + mug).param("sku", "MUG-1")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        tee = createProduct(admin, "Tee", 1, 1, "clothing");
        mvc.perform(put("/api/v1/product/" + tee + "/variants").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(admin))
                .content(json.writeValueAsString(Map.of(
                        "axes", List.of(Map.of("name", "Size", "values", List.of(Map.of("label", "S"), Map.of("label", "M")))),
                        "variants", List.of(variant("TEE-S", 499, 5, "S"), variant("TEE-M", 549, 2, "M"))))))
                .andExpect(status().isOk());
        JsonNode items = product(tee).get("variants").get("items");
        teeS = items.get(0).get("id").asText();
        teeM = items.get(1).get("id").asText();
    }

    private JsonNode product(String id) throws Exception {
        return body(mvc.perform(get("/api/v1/product/" + id)).andReturn()).get("product");
    }

    private ResultActions upload(String csv, boolean dryRun) throws Exception {
        return mvc.perform(multipart("/api/v1/product/import")
                .file(new MockMultipartFile("file", "p.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .param("dryRun", String.valueOf(dryRun)).header("Authorization", bearer(admin)));
    }

    private String export() throws Exception {
        return mvc.perform(get("/api/v1/product/export").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void exportHasOneRowPerProductOrVariant() throws Exception {
        String csv = export();

        assertThat(csv).startsWith("﻿product_id,product_name,variant_id,variant,sku,price,mrp,stock,status,low_stock_threshold");
        assertThat(csv).contains(mug + ",Mug,,,MUG-1,300,,10,ACTIVE,");
        assertThat(csv).contains(tee + ",Tee," + teeS + ",S,TEE-S,499,,5,ACTIVE,");
        assertThat(csv).contains(tee + ",Tee," + teeM + ",M,TEE-M,549,,2,ACTIVE,");
        assertThat(csv.lines().count()).isEqualTo(4);
    }

    @Test
    void textThatSpreadsheetsWouldRunAsAFormulaIsQuoted() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + mug).param("name", "=HYPERLINK(\"x\")")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());

        assertThat(export()).contains("\"'=HYPERLINK(\"\"x\"\")\"");
    }

    @Test
    void exportedFileCanBeEditedPreviewedAndApplied() throws Exception {
        String edited = export()
                .replace(mug + ",Mug,,,MUG-1,300,,10,ACTIVE,", mug + ",Mug,,,MUG-1,320,399,12,ACTIVE,8")
                .replace(tee + ",Tee," + teeM + ",M,TEE-M,549,,2,ACTIVE,", tee + ",Tee," + teeM + ",M,TEE-M,579,,7,DISABLED,");

        upload(edited, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.unchanged").value(1))
                .andExpect(jsonPath("$.changes.length()").value(2))
                .andExpect(jsonPath("$.changes[?(@.productId == '" + mug + "')].changes[*]").value(
                        org.hamcrest.Matchers.containsInAnyOrder("price 300 → 320", "MRP none → 399", "stock 10 → 12",
                                "low-stock threshold default → 8")))
                .andExpect(jsonPath("$.changes[?(@.productId == '" + tee + "')].variant").value(
                        org.hamcrest.Matchers.contains("M")));
        assertThat(product(mug).get("price").asDouble()).isEqualTo(300.0);   // dry run wrote nothing

        upload(edited, false).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));

        JsonNode m = product(mug);
        assertThat(m.get("price").asDouble()).isEqualTo(320.0);
        assertThat(m.get("mrp").asDouble()).isEqualTo(399.0);
        assertThat(m.get("stock").asInt()).isEqualTo(12);
        assertThat(m.get("lowStockThreshold").asInt()).isEqualTo(8);
        JsonNode t = product(tee);
        JsonNode medium = t.get("variants").get("items").get(1);
        assertThat(medium.get("price").asDouble()).isEqualTo(579.0);
        assertThat(medium.get("stock").asInt()).isEqualTo(7);
        assertThat(medium.get("status").asText()).isEqualTo("DISABLED");
        assertThat(t.get("stock").asInt()).isEqualTo(12);      // 5 + 7, kept in step
        assertThat(t.get("price").asDouble()).isEqualTo(499.0); // lowest sellable variant
    }

    @Test
    void skuOnlyFilesWork() throws Exception {
        upload("sku,stock\nTEE-S,9\nMUG-1,1\n", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        assertThat(product(tee).get("variants").get("items").get(0).get("stock").asInt()).isEqualTo(9);
        assertThat(product(mug).get("stock").asInt()).isEqualTo(1);
    }

    @Test
    void anyBadRowRejectsTheWholeFileWithLineNumbers() throws Exception {
        String csv = String.join("\n",
                "product_id,variant_id,sku,price,mrp,stock,status,low_stock_threshold",
                mug + ",,,350,,,,",                               // valid on its own
                "nope,,,10,,,,",                                  // line 3: unknown product
                tee + ",,,10,,,,",                                // line 4: variant product without a variant
                tee + "," + teeS + ",,abc,,,,",                   // line 5: bad price
                tee + "," + teeM + ",,600,500,,,",                // line 6: mrp below price
                tee + "," + teeS + ",,,,,,",                      // line 7: same variant again (line 5 failed, so it is new) -> fine
                mug + ",,,,,,,",                                  // line 8: duplicate of line 2
                ",,,,,-1,,",                                      // line 9: no id
                ",,TEE-M,,,,PAUSED,",                             // line 10: bad status
                tee + "," + teeS + ",,,,,,3",                     // line 11: duplicate of line 7
                "");
        JsonNode res = body(upload(csv, false).andExpect(status().isBadRequest()).andReturn());

        assertThat(res.get("message").asText()).contains("nothing was changed");
        assertThat(res.get("errors").findValuesAsText("line")).containsExactly("3", "4", "5", "6", "8", "9", "10", "11");
        assertThat(res.get("errors").get(0).get("message").asText()).contains("unknown product_id");
        assertThat(product(mug).get("price").asDouble()).isEqualTo(300.0);
    }

    @Test
    void thresholdMustAgreeAcrossAProductsRows() throws Exception {
        String csv = "product_id,variant_id,low_stock_threshold\n" + tee + "," + teeS + ",3\n" + tee + "," + teeM + ",4\n";
        upload(csv, true).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("line 2")));
    }

    @Test
    void headerAndAccessAreChecked() throws Exception {
        upload("name,price\nMug,1\n", true).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/product/export")).andExpect(status().isUnauthorized());
        String user = register("csv@test.dev");
        mvc.perform(get("/api/v1/product/export").header("Authorization", bearer(user))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/product/import")
                .file(new MockMultipartFile("file", "p.csv", "text/csv", "sku,stock\nMUG-1,1\n".getBytes()))
                .header("Authorization", bearer(user))).andExpect(status().isForbidden());
    }
}
