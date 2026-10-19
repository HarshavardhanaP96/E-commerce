package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.bson.Document;
import org.junit.jupiter.api.Test;

class AuditOrdersIntegrationTest extends IntegrationTest {

    @Test
    void placingAdvancingAndDeletingAnOrderAreAudited() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Book", 300, 5, "books");
        String customer = register("orders-audit@test.dev");
        String customerId = userId(customer);

        String orderId = placeOrder(customer, product);

        Document placed = auditEntries("ORDER_PLACED").get(0);
        assertThat(placed.getString("entityType")).isEqualTo("order");
        assertThat(placed.getString("entityId")).isEqualTo(orderId);
        assertThat(placed.getString("actorId")).isEqualTo(customerId);
        assertThat(placed.getString("actorRole")).isEqualTo("USER");
        assertThat(changeOf(placed, "status").get("after")).isEqualTo("PROCESSING");
        assertThat(changeOf(placed, "total").get("after")).isNotNull();

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Document advanced = auditEntries("ORDER_STATUS_CHANGED").get(0);
        assertThat(advanced.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(advanced.getString("entityId")).isEqualTo(orderId);
        assertThat(changeOf(advanced, "status").get("before")).isEqualTo("PROCESSING");
        assertThat(changeOf(advanced, "status").get("after")).isEqualTo("SHIPPED");

        mvc.perform(delete("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(auditEntries("ORDER_DELETED").get(0).getString("entityId")).isEqualTo(orderId);
    }

    @Test
    void advancingADeliveredOrderRecordsNothing() throws Exception {
        String admin = adminToken();
        String customer = register("delivered-audit@test.dev");
        String orderId = placeOrder(customer, createProduct(admin, "Pen", 50, 5, "stationery"));
        for (int i = 0; i < 3; i++) { // processing → shipped → delivered → (no-op)
            mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                    .andExpect(status().isOk());
        }
        assertThat(auditEntries("ORDER_STATUS_CHANGED")).hasSize(2);
    }
}
