package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.dashboard.application.DailySummaryJob;
import com.sixpack.tenant.domain.Tenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The daily summary table (analytics step 2): one row per store per local day, written by the nightly job, read by
 * the shop dashboard for days it covers, with live numbers for the days after.
 */
class DailySummaryIntegrationTest extends TenantIntegrationTest {

    private static final String SHOE = new ObjectId().toHexString();
    private static final Instant OCT_20_NOON = Instant.parse("2026-10-20T06:30:00Z"); // 12:00 in India

    @Autowired
    DailySummaryJob job;

    private String admin;
    private String defaultTenant;

    @BeforeEach
    void seedOrders() throws Exception {
        admin = adminToken();
        defaultTenant = tenantRepository.findBySlug("default").orElseThrow().id();
        // 01:30 IST on 6 Oct, still 5 Oct in UTC
        order(defaultTenant, "u1", "2026-10-05T20:00:00Z", 1000, 100, 50, refund(200, "processed", "2026-10-07T06:00:00Z"));
        order(defaultTenant, "u2", "2026-10-05T10:00:00Z", 500, 0, 25, null);
        order(defaultTenant, "u2", "2026-09-15T10:00:00Z", 300, 0, 0, refund(50, "failed", "2026-10-08T06:00:00Z"));
    }

    @AfterEach
    void clearSummaries() {
        mongo.getCollection("daily_sales").deleteMany(new Document());
        mongo.getCollection("daily_sales_state").deleteMany(new Document());
    }

    private List<Document> rows(String tenantId) {
        return mongo.getCollection("daily_sales").find(new Document("tenantId", tenantId))
                .sort(new Document("day", 1)).into(new ArrayList<>());
    }

    private Document row(String tenantId, String day) {
        return rows(tenantId).stream().filter(r -> day.equals(r.getString("day"))).findFirst().orElse(null);
    }

    private MockHttpServletRequestBuilder summary() {
        return get("/api/v1/dashboard/summary").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31").param("basis", "gross");
    }

    @Test
    void theJobWritesOneRowPerLocalDayWithTheDaysTotals() {
        job.refreshAll(OCT_20_NOON);

        assertThat(rows(defaultTenant)).extracting(r -> r.getString("day"))
                .containsExactly("2026-09-15", "2026-10-05", "2026-10-06", "2026-10-07");
        Document oct6 = row(defaultTenant, "2026-10-06");
        assertThat(oct6.getLong("orders")).isEqualTo(1);
        assertThat(oct6.getDouble("grossRevenue")).isEqualTo(1000.0);
        assertThat(oct6.getDouble("discount")).isEqualTo(100.0);
        assertThat(oct6.getDouble("tax")).isEqualTo(50.0);
        assertThat(oct6.getString("currency")).isEqualTo("INR");
        assertThat(oct6.getString("timeZone")).isEqualTo("Asia/Kolkata");
        // a refund counts on the day it was issued; a failed one not at all
        Document oct7 = row(defaultTenant, "2026-10-07");
        assertThat(oct7.getLong("orders")).isZero();
        assertThat(oct7.getDouble("refunds")).isEqualTo(200.0);
        assertThat(oct7.getDouble("netRevenue")).isEqualTo(-200.0);
        // new customers: shoppers whose first order fell on that day
        assertThat(row(defaultTenant, "2026-09-15").getLong("newCustomers")).isEqualTo(1);
        assertThat(row(defaultTenant, "2026-10-05").getLong("newCustomers")).isZero();
        assertThat(oct6.getLong("newCustomers")).isEqualTo(1);

        Document state = mongo.getCollection("daily_sales_state").find(new Document("tenantId", defaultTenant)).first();
        assertThat(state.getString("through")).isEqualTo("2026-10-19"); // today is still open
    }

    @Test
    void eachStoreGetsItsOwnRowsOnly() {
        Tenant acme = createTenant("acme", "Acme", "shop.acme.test");
        order(acme.id(), "a1", "2026-10-06T06:00:00Z", 777, 0, 0, null);

        job.refreshAll(OCT_20_NOON);

        assertThat(rows(acme.id())).extracting(r -> r.getString("day")).containsExactly("2026-10-06");
        assertThat(row(acme.id(), "2026-10-06").getDouble("grossRevenue")).isEqualTo(777.0);
        assertThat(row(defaultTenant, "2026-10-06").getDouble("grossRevenue")).isEqualTo(1000.0);
    }

    @Test
    void theDashboardReadsCoveredDaysFromTheTableAndLaterDaysLive() throws Exception {
        job.refreshAll(Instant.parse("2026-10-07T06:30:00Z")); // covers up to 6 Oct
        // changed behind the job's back: a covered day keeps its stored total, a later day is read live
        mongo.getCollection("orders").deleteMany(new Document("total", 500));
        order(defaultTenant, "u3", "2026-10-10T06:00:00Z", 250, 0, 0, null);

        mvc.perform(summary()).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.current.revenue").value(1750.0))
                .andExpect(jsonPath("$.summary.current.orders").value(3))
                .andExpect(jsonPath("$.summary.series[4].revenue").value(500.0))
                .andExpect(jsonPath("$.summary.series[9].revenue").value(250.0));
    }

    @Test
    void theSameNumbersComeOutWithOrWithoutTheTable() throws Exception {
        String live = body(mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(admin))
                .param("from", "2026-09-01").param("to", "2026-10-19")).andReturn()).get("summary").toString();
        job.refreshAll(OCT_20_NOON);
        String fromTable = body(mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(admin))
                .param("from", "2026-09-01").param("to", "2026-10-19")).andReturn()).get("summary").toString();
        assertThat(fromTable).isEqualTo(live);
    }

    @Test
    void aRunRefreshesTheCachedDashboard() throws Exception {
        job.refreshAll(Instant.parse("2026-10-07T06:30:00Z"));
        mvc.perform(summary()).andExpect(jsonPath("$.summary.current.revenue").value(1500.0));

        mongo.getCollection("orders").deleteMany(new Document("total", 500)); // no event, so the cache stays
        job.refreshAll(Instant.parse("2026-10-07T06:30:00Z"));

        mvc.perform(summary()).andExpect(jsonPath("$.summary.current.revenue").value(1000.0));
    }

    @Test
    void onlyTheRecentWindowIsRecomputed() {
        job.refreshAll(OCT_20_NOON);
        mongo.getCollection("orders").deleteMany(new Document("total", 300)); // 15 Sep: older than 30 days
        mongo.getCollection("orders").deleteMany(new Document("total", 500)); // 5 Oct: inside the window

        job.refreshAll(OCT_20_NOON);

        assertThat(row(defaultTenant, "2026-09-15")).isNotNull();
        assertThat(row(defaultTenant, "2026-10-05")).isNull();
    }

    @Test
    void aChangedTimeZoneRebuildsTheWholeHistoryInTheNewZone() throws Exception {
        job.refreshAll(OCT_20_NOON);
        storeTimeZone("UTC");
        try {
            // until the next run the table is in the old zone, so the dashboard reads live
            mvc.perform(summary()).andExpect(jsonPath("$.summary.series[4].revenue").value(1500.0));

            job.refreshAll(OCT_20_NOON);
            assertThat(rows(defaultTenant)).allMatch(r -> "UTC".equals(r.getString("timeZone")));
            assertThat(row(defaultTenant, "2026-10-05").getDouble("grossRevenue")).isEqualTo(1500.0);
            assertThat(row(defaultTenant, "2026-10-06")).isNull();
            assertThat(row(defaultTenant, "2026-09-15")).isNotNull(); // the rebuild goes back past the 30-day window
        } finally {
            storeTimeZone(null);
        }
    }

    private void storeTimeZone(String zone) throws Exception {
        Map<String, Object> regional = new HashMap<>();
        regional.put("timeZone", zone);
        mvc.perform(put("/api/v1/store/settings").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("regional", regional))))
                .andExpect(status().isOk());
    }

    private void order(String tenant, String user, String at, double total, double discount, double tax, Document refund) {
        Document payment = new Document("status", "PAID");
        if (refund != null) {
            payment.append("refunds", List.of(refund));
        }
        mongo.getCollection("orders").insertOne(new Document("_id", new ObjectId()).append("tenantId", tenant)
                .append("user", user).append("status", "Processing").append("total", total)
                .append("discount", discount).append("tax", tax)
                .append("orderItems", List.of(new Document("productId", new ObjectId(SHOE)).append("price", total)
                        .append("quantity", 1)))
                .append("payment", payment).append("createdAt", Date.from(Instant.parse(at))));
    }

    private static Document refund(double amount, String status, String at) {
        return new Document("amount", amount).append("status", status).append("at", Date.from(Instant.parse(at)));
    }
}
