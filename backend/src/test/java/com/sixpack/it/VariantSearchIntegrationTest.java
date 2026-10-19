package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class VariantSearchIntegrationTest extends IntegrationTest {

    private static Map<String, Object> axis(String name, String type, String... labels) {
        var values = new ArrayList<Map<String, Object>>();
        for (String l : labels) {
            var v = new HashMap<String, Object>();
            v.put("label", l);
            if (type.equals("colour")) {
                v.put("hex", l.equals("Black") ? "#111111" : "#eeeeee");
            }
            values.add(v);
        }
        return Map.of("name", name, "type", type, "values", values);
    }

    private static Map<String, Object> variant(String sku, int stock, String... options) {
        var list = new ArrayList<Map<String, String>>();
        for (int i = 0; i < options.length; i += 2) {
            list.add(Map.of("name", options[i], "value", options[i + 1]));
        }
        var m = new HashMap<String, Object>();
        m.put("sku", sku);
        m.put("price", 100.0);
        m.put("stock", stock);
        m.put("options", list);
        return m;
    }

    private void variants(String token, String id, Object body) throws Exception {
        mvc.perform(put("/api/v1/product/" + id + "/variants").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(token)).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @BeforeEach
    void catalogue() throws Exception {
        String token = adminToken();
        // Tee: S/Black sold out, S/White 2, M/Black 3
        String tee = createProduct(token, "Tee", 100, 1, "clothing");
        variants(token, tee, Map.of("axes", List.of(axis("Size", "text", "S", "M"), axis("Colour", "colour", "Black", "White")),
                "variants", List.of(variant("TEE-S-BLK", 0, "Size", "S", "Colour", "Black"),
                        variant("TEE-S-WHT", 2, "Size", "S", "Colour", "White"),
                        variant("TEE-M-BLK", 3, "Size", "M", "Colour", "Black"))));
        // Cap: one axis
        String cap = createProduct(token, "Cap", 100, 1, "clothing");
        variants(token, cap, Map.of("axes", List.of(axis("Size", "text", "M", "L")),
                "variants", List.of(variant("CAP-M", 1, "Size", "M"), variant("CAP-L", 1, "Size", "L"))));
        // Draft hat: must stay out of search and facets
        String hat = createProduct(token, "Draft Hat", 100, 1, "clothing");
        variants(token, hat, Map.of("axes", List.of(axis("Size", "text", "XL")),
                "variants", List.of(variant("HAT-XL", 1, "Size", "XL"))));
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + hat).param("status", "DRAFT")
                .header("Authorization", bearer(token))).andExpect(status().isOk());
        createProduct(token, "Plain Mug", 50, 5, "home");
    }

    private List<String> names(String query) throws Exception {
        JsonNode products = body(mvc.perform(get("/api/v1/product/all?" + query)).andExpect(status().isOk())
                .andReturn()).get("products");
        List<String> names = new ArrayList<>();
        products.forEach(p -> names.add(p.get("name").asText()));
        return names;
    }

    private ResultActions search(String query) throws Exception {
        return mvc.perform(get("/api/v1/product/all?" + query));
    }

    @Test
    void noOptionFilterReturnsEveryPublicProduct() throws Exception {
        assertThat(names("")).containsExactlyInAnyOrder("Tee", "Cap", "Plain Mug");
    }

    @Test
    void filtersByOneOption() throws Exception {
        assertThat(names("option=Size:M")).containsExactlyInAnyOrder("Tee", "Cap");
        assertThat(names("option=Size:L")).containsExactly("Cap");
        assertThat(names("option=Size:XL")).isEmpty();   // only on a draft product
    }

    @Test
    void allOptionsMustBeMatchedByTheSameInStockVariant() throws Exception {
        assertThat(names("option=Size:S&option=Colour:White")).containsExactly("Tee");
        assertThat(names("option=Size:M&option=Colour:White")).isEmpty();      // M exists, White exists, M/White does not
        assertThat(names("option=Size:S&option=Colour:Black")).isEmpty();      // S/Black is sold out
        assertThat(names("option=Size:M&option=Colour:Black")).containsExactly("Tee");
    }

    @Test
    void combinesWithOtherFilters() throws Exception {
        assertThat(names("option=Size:M&search=cap")).containsExactly("Cap");
        assertThat(names("option=Size:M&price=50")).isEmpty();
    }

    @Test
    void malformedOrTooManyFiltersAreRejected() throws Exception {
        search("option=Size").andExpect(status().isBadRequest());
        search("option=:M").andExpect(status().isBadRequest());
        search("option=Size:").andExpect(status().isBadRequest());
        search("option=A:1&option=B:2&option=C:3&option=D:4").andExpect(status().isBadRequest());
    }

    @Test
    void facetsListOptionNamesAndValuesOfPublicProducts() throws Exception {
        JsonNode options = body(mvc.perform(get("/api/v1/product/options")).andExpect(status().isOk())
                .andReturn()).get("options");

        assertThat(options).hasSize(2);
        assertThat(options.get(0).get("name").asText()).isEqualTo("Colour");
        assertThat(options.get(0).get("type").asText()).isEqualTo("COLOUR");
        assertThat(options.get(0).get("values").get(0).get("label").asText()).isEqualTo("Black");
        assertThat(options.get(0).get("values").get(0).get("hex").asText()).isEqualTo("#111111");
        JsonNode size = options.get(1);
        assertThat(size.get("name").asText()).isEqualTo("Size");
        List<String> sizes = new ArrayList<>();
        size.get("values").forEach(v -> sizes.add(v.get("label").asText()));
        assertThat(sizes).contains("S", "M", "L").doesNotContain("XL");
    }
}
