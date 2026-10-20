package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class VariantSalesIntegrationTest extends IntegrationTest {

    private static Document line(ObjectId productId, String variantId, String label, String sku, double price, int qty) {
        Document d = new Document("productId", productId).append("name", "Tee").append("photo", "p.png")
                .append("price", price).append("quantity", qty).append("sku", sku);
        if (variantId != null) {
            d.append("variantId", variantId).append("variantLabel", label);
        }
        return d;
    }

    private void order(Instant at, Document... lines) {
        mongo.getCollection("orders").insertOne(new Document("user", "u1").append("status", "Processing")
                .append("total", 1.0).append("createdAt", Date.from(at)).append("orderItems", List.of(lines)));
    }

    @Test
    void salesAreGroupedPerVariantBestSellersFirst() throws Exception {
        ObjectId tee = new ObjectId();
        ObjectId mug = new ObjectId();
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        order(t0, line(tee, null, null, "TEE", 400, 1));                                  // sold before it had variants
        order(t0.plusSeconds(60), line(tee, "v-m", "M / Blk", "TEE-M", 500, 2), line(mug, null, null, "MUG", 99, 5));
        order(t0.plusSeconds(120), line(tee, "v-m", "M / Black", "TEE-M", 550, 1),     // label renamed later
                line(tee, "v-s", "S / Black", "TEE-S", 500, 1));
        String admin = adminToken();

        JsonNode sales = body(mvc.perform(get("/api/v1/dashboard/products/" + tee.toHexString() + "/variants")
                .header("Authorization", bearer(admin))).andExpect(status().isOk()).andReturn()).get("sales");

        assertThat(sales).hasSize(3);
        JsonNode m = sales.get(0);
        assertThat(m.get("variantId").asText()).isEqualTo("v-m");
        assertThat(m.get("label").asText()).isEqualTo("M / Black");
        assertThat(m.get("sku").asText()).isEqualTo("TEE-M");
        assertThat(m.get("units").asLong()).isEqualTo(3);
        assertThat(m.get("revenue").asDouble()).isEqualTo(1550.0);
        assertThat(m.get("orders").asLong()).isEqualTo(2);
        assertThat(sales.findValuesAsText("sku")).containsExactlyInAnyOrder("TEE-M", "TEE", "TEE-S");
        // the mug's 5 units never leak into the tee
        sales.forEach(s -> assertThat(s.get("sku").asText()).isNotEqualTo("MUG"));
    }

    @Test
    void variantSalesAreForAdminsOnly() throws Exception {
        mvc.perform(get("/api/v1/dashboard/products/x/variants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dashboard/products/x/variants")
                .header("Authorization", bearer(register("peek@test.dev")))).andExpect(status().isForbidden());
    }
}
