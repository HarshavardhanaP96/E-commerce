package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Headline numbers and trend for a date range, in the store's own time zone (platform default Asia/Kolkata). */
class DashboardSummaryIntegrationTest extends IntegrationTest {

    private static final String SHOE = new ObjectId().toHexString();
    private static final String BAG = new ObjectId().toHexString();
    private String admin;

    @BeforeEach
    void seedOrders() throws Exception {
        admin = adminToken();
        String tenant = tenantRepository.findBySlug("default").orElseThrow().id();
        // 01:30 IST on 6 Oct, still 5 Oct in UTC
        order(tenant, "2026-10-05T20:00:00Z", 1000, 100, 50, item(SHOE, 450, 2),
                refund(200, "processed", "2026-10-07T06:00:00Z"));
        order(tenant, "2026-10-05T10:00:00Z", 500, 0, 25, item(BAG, 500, 1), null);
        order(tenant, "2026-09-15T10:00:00Z", 300, 0, 0, item(BAG, 300, 1),
                refund(50, "failed", "2026-10-08T06:00:00Z"));
        order("another-shop", "2026-10-10T10:00:00Z", 9999, 0, 0, item(BAG, 9999, 1), null);
    }

    private MockHttpServletRequestBuilder summary() {
        return get("/api/v1/dashboard/summary").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void netRevenueForTheRangeComparedWithThePreviousPeriod() throws Exception {
        mvc.perform(summary())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.summary.basis").value("net"))
                .andExpect(jsonPath("$.summary.granularity").value("day"))
                .andExpect(jsonPath("$.summary.current.orders").value(2))
                .andExpect(jsonPath("$.summary.current.revenue").value(1300.0))
                .andExpect(jsonPath("$.summary.current.refunds").value(200.0))
                .andExpect(jsonPath("$.summary.current.units").value(3.0))
                .andExpect(jsonPath("$.summary.current.discount").value(100.0))
                .andExpect(jsonPath("$.summary.current.tax").value(75.0))
                .andExpect(jsonPath("$.summary.compareFrom").value("2026-08-31"))
                .andExpect(jsonPath("$.summary.compareTo").value("2026-09-30"))
                .andExpect(jsonPath("$.summary.previous.revenue").value(300.0))
                .andExpect(jsonPath("$.summary.changePercent.revenue").value(333))
                .andExpect(jsonPath("$.summary.series.length()").value(31));
    }

    @Test
    void trendBucketsOrdersByTheShopsLocalDay() throws Exception {
        mvc.perform(summary().param("basis", "gross"))
                .andExpect(jsonPath("$.summary.current.revenue").value(1500.0))
                .andExpect(jsonPath("$.summary.series[4].start").value("2026-10-05"))
                .andExpect(jsonPath("$.summary.series[4].revenue").value(500.0))
                .andExpect(jsonPath("$.summary.series[5].revenue").value(1000.0));
    }

    private void storeTimeZone(String zone) throws Exception {
        java.util.Map<String, Object> regional = new java.util.HashMap<>();
        regional.put("timeZone", zone);
        mvc.perform(put("/api/v1/store/settings").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("regional", regional))))
                .andExpect(status().isOk());
    }

    @Test
    void daysAreCutInTheStoresOwnTimeZone() throws Exception {
        try {
            storeTimeZone("UTC");
            // 20:00 UTC on 5 Oct is still 5 Oct for a store on UTC (it is 6 Oct in India)
            mvc.perform(summary().param("basis", "gross"))
                    .andExpect(jsonPath("$.summary.timezone").value("UTC"))
                    .andExpect(jsonPath("$.summary.series[4].revenue").value(1500.0))
                    .andExpect(jsonPath("$.summary.series[5].revenue").value(0.0));

            storeTimeZone("Asia/Kolkata"); // a changed zone is never answered from the cache
            mvc.perform(summary().param("basis", "gross"))
                    .andExpect(jsonPath("$.summary.timezone").value("Asia/Kolkata"))
                    .andExpect(jsonPath("$.summary.series[5].revenue").value(1000.0));
        } finally {
            storeTimeZone(null);
        }
    }

    @Test
    void productFilterCountsOnlyThatProductsItems() throws Exception {
        mvc.perform(summary().param("productId", SHOE))
                .andExpect(jsonPath("$.summary.current.orders").value(1))
                .andExpect(jsonPath("$.summary.current.revenue").value(900.0))
                .andExpect(jsonPath("$.summary.current.units").value(2.0))
                .andExpect(jsonPath("$.summary.current.refunds").doesNotExist())
                .andExpect(jsonPath("$.summary.current.discount").doesNotExist());
    }

    @Test
    void comparisonCanBeSwitchedOffAndGroupedByWeek() throws Exception {
        mvc.perform(summary().param("compare", "none").param("granularity", "week"))
                .andExpect(jsonPath("$.summary.previous").doesNotExist())
                .andExpect(jsonPath("$.summary.changePercent").doesNotExist())
                .andExpect(jsonPath("$.summary.series[0].start").value("2026-09-28"))
                .andExpect(jsonPath("$.summary.series.length()").value(5));
    }

    @Test
    void rejectsBadRanges() throws Exception {
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(admin))
                        .param("from", "2026-10-31").param("to", "2026-10-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(summary().param("compare", "sometimes")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(admin))
                        .param("from", "2024-01-01").param("to", "2026-10-01").param("granularity", "day"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customersCannotReadIt() throws Exception {
        String user = register("shopper@test.dev");
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    private void order(String tenant, String at, double total, double discount, double tax, Document item,
                       Document refund) {
        Document payment = new Document("status", "PAID");
        if (refund != null) {
            payment.append("refunds", List.of(refund));
        }
        mongo.getCollection("orders").insertOne(new Document("_id", new ObjectId()).append("tenantId", tenant)
                .append("user", "u1").append("status", "Processing").append("total", total)
                .append("discount", discount).append("tax", tax).append("orderItems", List.of(item))
                .append("payment", payment).append("createdAt", Date.from(Instant.parse(at))));
    }

    private static Document item(String productId, double price, int quantity) {
        return new Document("productId", new ObjectId(productId)).append("price", price).append("quantity", quantity);
    }

    private static Document refund(double amount, String status, String at) {
        return new Document("amount", amount).append("status", status).append("at", Date.from(Instant.parse(at)));
    }
}
