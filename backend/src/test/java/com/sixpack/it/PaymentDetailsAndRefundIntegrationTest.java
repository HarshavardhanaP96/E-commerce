package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.PaymentProviderException;
import java.util.HashMap;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

class PaymentDetailsAndRefundIntegrationTest extends IntegrationTest {

    @MockitoSpyBean
    PaymentGateway gateway;

    @AfterEach
    void resetSpy() {
        reset(gateway);
    }

    private ResultActions refund(String token, String orderId, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/order/" + orderId + "/refund").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode payment(String token, String orderId) throws Exception {
        return body(mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()).get("order").get("payment");
    }

    /** A 500 rupee product: 500 + 90 tax + 200 shipping = 790. */
    private String paidOrder(String admin, String email) throws Exception {
        String product = createProduct(admin, "Lamp", 500, 20, "home");
        return placeOrder(register(email), product);
    }

    @Test
    void theOrderRecordsHowItWasPaid() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "method@test.dev");

        assertThat(payment(admin, orderId).get("method").asText()).isEqualTo("fake");
        assertThat(payment(admin, orderId).get("status").asText()).isEqualTo("PAID");
        assertThat(payment(admin, orderId).get("refunded").asDouble()).isZero();
        assertThat(payment(admin, orderId).get("refunds")).isEmpty();
    }

    @Test
    void anOrderStillGetsPlacedWhenTheProviderCannotDescribeThePayment() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Lamp", 500, 20, "home");
        doThrow(new PaymentProviderException("down")).when(gateway).fetchPayment(anyString());

        String orderId = placeOrder(register("nofetch@test.dev"), product);

        assertThat(payment(admin, orderId).get("method").isNull()).isTrue();
        assertThat(payment(admin, orderId).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void anAdminCanRefundInPartsUntilNothingIsLeft() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "parts@test.dev");

        refund(admin, orderId, Map.of("amount", 100, "reason", "Scratched shade")).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.payment.refunded").value(100.0))
                .andExpect(jsonPath("$.payment.refunds[0].amount").value(100.0))
                .andExpect(jsonPath("$.payment.refunds[0].reason").value("Scratched shade"));
        verify(gateway).refund(anyString(), eq(10_000L), anyString(), eq("Scratched shade"));

        // No amount means everything that is left.
        refund(admin, orderId, Map.of("reason", "Returned")).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"))
                .andExpect(jsonPath("$.payment.refunded").value(790.0));
        verify(gateway).refund(anyString(), eq(69_000L), anyString(), eq("Returned"));

        refund(admin, orderId, Map.of("reason", "Again")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This order has already been fully refunded"));
        assertThat(payment(admin, orderId).get("refunds")).hasSize(2);
    }

    @Test
    void youCannotRefundMoreThanWasPaid() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "over@test.dev");
        refund(admin, orderId, Map.of("amount", 700, "reason", "Part")).andExpect(status().isOk());

        refund(admin, orderId, Map.of("amount", 100, "reason", "Too much")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only ₹90 can still be refunded on this order"));
        refund(admin, orderId, Map.of("amount", 0, "reason", "Zero")).andExpect(status().isBadRequest());
        refund(admin, orderId, Map.of("amount", 10)).andExpect(status().isBadRequest()); // reason is required
        verify(gateway, org.mockito.Mockito.times(1)).refund(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void onlyAdminsRefundAndUnknownOrdersAre404() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "owner@test.dev");
        String stranger = register("stranger@test.dev");

        refund(stranger, orderId, Map.of("reason", "Please")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/order/" + orderId + "/refund").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
        refund(admin, new ObjectId().toHexString(), Map.of("reason", "Nope")).andExpect(status().isNotFound());
    }

    @Test
    void aFailedRefundIsRecordedAndCanBeRetried() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "fail@test.dev");
        doThrow(new PaymentProviderException("Payment provider is unavailable, please try again"))
                .when(gateway).refund(anyString(), anyLong(), anyString(), anyString());

        refund(admin, orderId, Map.of("amount", 100, "reason", "Try")).andExpect(status().isBadGateway());
        JsonNode afterFailure = payment(admin, orderId);
        assertThat(afterFailure.get("status").asText()).isEqualTo("PAID");
        assertThat(afterFailure.get("refunded").asDouble()).isZero();
        assertThat(afterFailure.get("failureReason").asText()).contains("Refund of ₹100 failed")
                .contains("unavailable");

        reset(gateway);
        refund(admin, orderId, Map.of("amount", 100, "reason", "Retry")).andExpect(status().isOk());
        JsonNode afterRetry = payment(admin, orderId);
        assertThat(afterRetry.get("refunded").asDouble()).isEqualTo(100.0);
        assertThat(afterRetry.get("failureReason").isNull()).isTrue();
    }

    @Test
    void anOrderPaidThroughAnotherProviderIsNotRefundedThroughTheActiveOne() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "other@test.dev");
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(orderId)),
                new Document("$set", new Document("payment.provider", "razorpay")));

        refund(admin, orderId, Map.of("reason", "Wrong gateway")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This order was paid through razorpay, so it has to be refunded there"));
    }

    @Test
    void refundsAreAuditedWithoutTheFreeTextReason() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "audit@test.dev");

        refund(admin, orderId, Map.of("amount", 50, "reason", "Customer Jane Doe called")).andExpect(status().isOk());

        Document entry = auditEntries("ORDER_REFUNDED").get(0);
        assertThat(entry.getString("entityId")).isEqualTo(orderId);
        assertThat(changeOf(entry, "refunded").get("after")).isEqualTo(50.0);
        assertThat(entry.toJson()).doesNotContain("Jane");
    }

    @Test
    void ordersPlacedBeforeRefundsExistedLoadAndCanBeRefunded() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "legacy@test.dev");
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(orderId)),
                new Document("$unset", new Document("payment.method", "").append("payment.refunds", "")
                        .append("payment.refundedPaise", "")));

        assertThat(payment(admin, orderId).get("refunded").asDouble()).isZero();
        refund(admin, orderId, Map.of("amount", 10, "reason", "Goodwill")).andExpect(status().isOk());
    }

    @Test
    void movingTheOrderAlongKeepsTheRefundState() throws Exception {
        String admin = adminToken();
        String orderId = paidOrder(admin, "advance@test.dev");
        refund(admin, orderId, Map.of("amount", 100, "reason", "Part")).andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/order/" + orderId)
                .header("Authorization", bearer(admin))).andExpect(status().isOk());

        JsonNode payment = payment(admin, orderId);
        assertThat(payment.get("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(payment.get("refunded").asDouble()).isEqualTo(100.0);
        assertThat(payment.get("refunds")).hasSize(1);
    }
}
