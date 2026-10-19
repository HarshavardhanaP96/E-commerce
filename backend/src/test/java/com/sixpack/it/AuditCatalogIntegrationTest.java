package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

class AuditCatalogIntegrationTest extends IntegrationTest {

    @Test
    void productCreateUpdateAndDeleteAreAudited() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        String id = createProduct(admin, "Lamp", 400, 3, "home");

        Document created = auditEntries("PRODUCT_CREATED").get(0);
        assertThat(created.getString("entityType")).isEqualTo("product");
        assertThat(created.getString("entityId")).isEqualTo(id);
        assertThat(created.getString("actorId")).isEqualTo(adminId);
        assertThat(created.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(changeOf(created, "price").get("after")).isEqualTo(400.0);
        assertThat(changeOf(created, "name").get("after")).isEqualTo("Lamp");

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).param("price", "450")
                        .param("stock", "3").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Document updated = auditEntries("PRODUCT_UPDATED").get(0);
        assertThat(updated.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("price"); // stock was resubmitted unchanged, so it is not listed
        assertThat(changeOf(updated, "price").get("before")).isEqualTo(400.0);
        assertThat(changeOf(updated, "price").get("after")).isEqualTo(450.0);

        mvc.perform(delete("/api/v1/product/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Document deleted = auditEntries("PRODUCT_DELETED").get(0);
        assertThat(deleted.getString("entityId")).isEqualTo(id);
        assertThat(changeOf(deleted, "name").get("before")).isEqualTo("Lamp");
    }

    @Test
    void aRefusedProductWriteLeavesNoEntry() throws Exception {
        String admin = adminToken();
        mvc.perform(delete("/api/v1/product/000000000000000000000000").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        assertThat(auditEntries("PRODUCT_DELETED")).isEmpty();
    }

    @Test
    void categoryLifecycleIsAudited() throws Exception {
        String admin = adminToken();
        String id = body(mvc.perform(multipart("/api/v1/category/new").param("name", "Garden")
                        .header("Authorization", bearer(admin))).andExpect(status().isCreated()).andReturn())
                .get("category").get("_id").asText();
        Document created = auditEntries("CATEGORY_CREATED").get(0);
        assertThat(created.getString("entityId")).isEqualTo(id);
        assertThat(changeOf(created, "name").get("after")).isEqualTo("Garden");

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/category/" + id).param("name", "Garden & Patio")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        Document updated = auditEntries("CATEGORY_UPDATED").get(0);
        assertThat(changeOf(updated, "name").get("before")).isEqualTo("Garden");
        assertThat(changeOf(updated, "name").get("after")).isEqualTo("Garden & Patio");

        mvc.perform(delete("/api/v1/category/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(auditEntries("CATEGORY_DELETED").get(0).getString("entityId")).isEqualTo(id);
    }

    @Test
    void couponLifecycleIsAudited() throws Exception {
        String admin = adminToken();
        String id = body(mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("coupon", "SAVE100", "amount", 100))))
                .andExpect(status().isCreated()).andReturn()).get("coupon").get("_id").asText();
        Document created = auditEntries("COUPON_CREATED").get(0);
        assertThat(created.getString("entityId")).isEqualTo(id);
        assertThat(changeOf(created, "code").get("after")).isEqualTo("SAVE100");
        assertThat(changeOf(created, "type").get("after")).isEqualTo("FLAT");

        mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", 150))))
                .andExpect(status().isOk());
        Document updated = auditEntries("COUPON_UPDATED").get(0);
        assertThat(changeOf(updated, "amount").get("before")).isEqualTo(100.0);
        assertThat(changeOf(updated, "amount").get("after")).isEqualTo(150.0);

        mvc.perform(delete("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(auditEntries("COUPON_DELETED").get(0).getString("entityId")).isEqualTo(id);
    }
}
