package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.invoice.seller-name=Acme Traders", "app.invoice.seller-address=7 Residency Road, Bengaluru 560025",
        "app.invoice.seller-gstin=29ABCDE1234F1Z5", "app.invoice.seller-state=Karnataka",
        "app.invoice.seller-email=billing@acme.test", "app.invoice.seller-phone=+918012345678"})
class InvoiceIntegrationTest extends IntegrationTest {

    private ResultActions invoice(String token, String orderId) throws Exception {
        return mvc.perform(get("/api/v1/order/" + orderId + "/invoice").header("Authorization", bearer(token)));
    }

    /** Buys one 500 rupee item at 18% tax and one 200 rupee item at 5% tax, shipped to {@code state}. */
    private String orderTo(String admin, String token, String state) throws Exception {
        String lamp = createProduct(admin, "Lamp", 500, 20, "home");
        String pen = createProduct(admin, "Pen", 200, 20, "office");
        mongo.getCollection("products").updateOne(new Document("_id", new ObjectId(pen)),
                new Document("$set", new Document("taxRate", 5.0)));
        String intent = body(mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items", List.of(
                                Map.of("productId", lamp, "quantity", 1), Map.of("productId", pen, "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        Map<String, Object> ship = new HashMap<>(OrderCheckoutDetailsIntegrationTest.shipping());
        ship.put("state", state);
        return body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                                "signature", "fake", "shippingInfo", ship))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    @Test
    void anInSameStateOrderSplitsTheTaxIntoCgstAndSgst() throws Exception {
        String admin = adminToken();
        String user = register("invoice@test.dev");
        String orderId = orderTo(admin, user, "Karnataka");

        JsonNode invoice = body(invoice(user, orderId).andExpect(status().isOk()).andReturn()).get("invoice");

        assertThat(invoice.get("number").asText()).matches("INV-\\d{8}-[A-Z0-9]{6}");
        String orderNumber = body(mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andReturn()).get("order").get("orderNumber").asText();
        assertThat(invoice.get("orderNumber").asText()).isEqualTo(orderNumber);
        assertThat(invoice.get("number").asText()).isEqualTo("INV-" + orderNumber.substring(4));

        assertThat(invoice.get("seller").get("name").asText()).isEqualTo("Acme Traders");
        assertThat(invoice.get("seller").get("gstin").asText()).isEqualTo("29ABCDE1234F1Z5");
        assertThat(invoice.get("buyer").get("name").asText()).isEqualTo("Test User");
        assertThat(invoice.get("buyer").get("email").asText()).isEqualTo("invoice@test.dev");
        assertThat(invoice.get("shipTo").get("city").asText()).isEqualTo("Bengaluru");

        assertThat(invoice.get("lines")).hasSize(2);
        JsonNode lamp = invoice.get("lines").get(0);
        assertThat(lamp.get("description").asText()).isEqualTo("Lamp");
        assertThat(lamp.get("quantity").asInt()).isEqualTo(1);
        assertThat(lamp.get("unitPrice").asDouble()).isEqualTo(500.0);
        assertThat(lamp.get("amount").asDouble()).isEqualTo(500.0);
        assertThat(lamp.get("taxRate").asDouble()).isEqualTo(18.0);

        // 500 at 18% = 90, 200 at 5% = 10. Same state: each tax is halved into CGST and SGST.
        JsonNode summary = invoice.get("taxSummary");
        assertThat(summary).hasSize(2);
        assertThat(summary.get(0).get("rate").asDouble()).isEqualTo(5.0);
        assertThat(summary.get(0).get("taxableValue").asDouble()).isEqualTo(200.0);
        assertThat(summary.get(0).get("cgst").asDouble()).isEqualTo(5.0);
        assertThat(summary.get(0).get("sgst").asDouble()).isEqualTo(5.0);
        assertThat(summary.get(0).get("igst").asDouble()).isZero();
        assertThat(summary.get(1).get("rate").asDouble()).isEqualTo(18.0);
        assertThat(summary.get(1).get("cgst").asDouble()).isEqualTo(45.0);

        assertThat(invoice.get("subtotal").asDouble()).isEqualTo(700.0);
        assertThat(invoice.get("tax").asDouble()).isEqualTo(100.0);
        assertThat(invoice.get("shippingCharges").asDouble()).isEqualTo(200.0);
        assertThat(invoice.get("total").asDouble()).isEqualTo(1000.0);
        assertThat(invoice.get("paymentMethod").asText()).isEqualTo("fake");
    }

    @Test
    void anOutOfStateOrderUsesIgst() throws Exception {
        String admin = adminToken();
        String user = register("igst@test.dev");
        String orderId = orderTo(admin, user, "Maharashtra");

        JsonNode summary = body(invoice(user, orderId).andExpect(status().isOk()).andReturn())
                .get("invoice").get("taxSummary");

        assertThat(summary.get(1).get("rate").asDouble()).isEqualTo(18.0);
        assertThat(summary.get(1).get("igst").asDouble()).isEqualTo(90.0);
        assertThat(summary.get(1).get("cgst").asDouble()).isZero();
        assertThat(summary.get(1).get("sgst").asDouble()).isZero();
    }

    @Test
    void theBillingAddressDecidesWhereTheBuyerIsWhenOneWasGiven() throws Exception {
        String admin = adminToken();
        String user = register("billing@test.dev");
        String orderId = orderTo(admin, user, "Maharashtra");
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(orderId)),
                new Document("$set", new Document("billingInfo", new Document("address", "1 Bill St")
                        .append("city", "Mysuru").append("state", "karnataka ").append("country", "India")
                        .append("pinCode", 570001L))));

        JsonNode invoice = body(invoice(user, orderId).andExpect(status().isOk()).andReturn()).get("invoice");

        assertThat(invoice.get("billTo").get("city").asText()).isEqualTo("Mysuru");
        assertThat(invoice.get("taxSummary").get(1).get("cgst").asDouble()).isEqualTo(45.0);
    }

    @Test
    void onlyTheOwnerAndAdminsCanOpenAnInvoice() throws Exception {
        String admin = adminToken();
        String owner = register("owner@test.dev");
        String orderId = orderTo(admin, owner, "Karnataka");

        invoice(admin, orderId).andExpect(status().isOk());
        invoice(register("nosy@test.dev"), orderId).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/order/" + orderId + "/invoice")).andExpect(status().isUnauthorized());
        invoice(admin, new ObjectId().toHexString()).andExpect(status().isNotFound());
    }

    @Test
    void refundsAppearOnTheInvoice() throws Exception {
        String admin = adminToken();
        String owner = register("refunded@test.dev");
        String orderId = orderTo(admin, owner, "Karnataka");
        mvc.perform(post("/api/v1/order/" + orderId + "/refund").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50,\"reason\":\"Goodwill\"}"))
                .andExpect(status().isOk());

        invoice(owner, orderId).andExpect(jsonPath("$.invoice.refunded").value(50.0))
                .andExpect(jsonPath("$.invoice.paymentStatus").value("PARTIALLY_REFUNDED"));
    }

    @Test
    void ordersFromBeforeOrderNumbersStillGetAnInvoice() throws Exception {
        String admin = adminToken();
        String owner = register("old@test.dev");
        String orderId = orderTo(admin, owner, "Karnataka");
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(orderId)),
                new Document("$unset", new Document("orderNumber", "").append("customer", "")));

        invoice(owner, orderId).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.number").value("INV-" + orderId))
                .andExpect(jsonPath("$.invoice.buyer.name").doesNotExist());
    }
}
