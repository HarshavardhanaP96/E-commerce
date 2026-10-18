package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.products.variants-enabled=false")
class ProductVariantsDisabledIntegrationTest extends IntegrationTest {

    @Test
    void variantsCannotBeCreatedWhileTheFlagIsOff() throws Exception {
        String token = adminToken();
        String id = createProduct(token, "T-Shirt", 1, 1, "clothing");

        mvc.perform(put("/api/v1/product/" + id + "/variants").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("axes", List.of(Map.of("name", "Size", "values",
                                        List.of(Map.of("label", "S")))),
                                "variants", List.of(Map.of("price", 10, "options",
                                        List.of(Map.of("name", "Size", "value", "S")))))))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
