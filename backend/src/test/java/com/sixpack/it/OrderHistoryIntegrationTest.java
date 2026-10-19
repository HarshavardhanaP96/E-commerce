package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class OrderHistoryIntegrationTest extends IntegrationTest {

    @Test
    void advancingRecordsWhoAndWhenAndStopsAtDelivered() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        String product = createProduct(admin, "Mug", 300, 5, "home");
        String user = register("hist@test.dev");
        String orderId = placeOrder(user, product);

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Shipped"));
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("Delivered"));
        // A third click on a delivered order changes nothing and adds no entry.
        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Delivered"));

        mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.order.statusHistory.length()").value(3))
                .andExpect(jsonPath("$.order.statusHistory[0].status").value("Processing"))
                .andExpect(jsonPath("$.order.statusHistory[0].actor").value(userId(user)))
                .andExpect(jsonPath("$.order.statusHistory[1].status").value("Shipped"))
                .andExpect(jsonPath("$.order.statusHistory[1].actor").value(adminId))
                .andExpect(jsonPath("$.order.statusHistory[2].status").value("Delivered"))
                .andExpect(jsonPath("$.order.statusHistory[2].at").exists());
    }

    @Test
    void customersCannotAdvanceOrders() throws Exception {
        String admin = adminToken();
        String user = register("nope@test.dev");
        String orderId = placeOrder(user, createProduct(admin, "Pen", 100, 5, "office"));

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.order.statusHistory.length()").value(1));
    }

    @Test
    void legacyOrdersDisplayAndAdvance() throws Exception {
        String admin = adminToken();
        String user = register("old@test.dev");
        ObjectId id = new ObjectId();
        mongo.getCollection("orders").insertOne(new Document("_id", id).append("user", userId(user))
                .append("status", "Processing").append("subtotal", 500.0).append("tax", 90.0)
                .append("shippingCharges", 200.0).append("discount", 0.0).append("total", 790.0)
                .append("shippingInfo", new Document("address", "1 MG Road").append("city", "Pune")
                        .append("state", "MH").append("country", "India").append("pinCode", 411001L))
                .append("orderItems", List.of(new Document("name", "Tee").append("photo", "").append("price", 250.0)
                        .append("quantity", 2).append("productId", new ObjectId())))
                .append("createdAt", new Date()));

        mvc.perform(get("/api/v1/order/" + id.toHexString()).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderNumber").doesNotExist())
                .andExpect(jsonPath("$.order.payment").doesNotExist())
                .andExpect(jsonPath("$.order.statusHistory.length()").value(0))
                .andExpect(jsonPath("$.order.total").value(790.0));
        mvc.perform(get("/api/v1/order/my").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.orders.length()").value(1));

        mvc.perform(put("/api/v1/order/" + id.toHexString()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.status").value("Shipped"));
        mvc.perform(get("/api/v1/order/" + id.toHexString()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.order.statusHistory.length()").value(1))
                .andExpect(jsonPath("$.order.statusHistory[0].status").value("Shipped"));
    }
}
