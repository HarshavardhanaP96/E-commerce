package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static com.sixpack.it.AnalyticsSeed.refund;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SalesSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private static final String BAG = AnalyticsSeed.newId();
    private static final String GONE = AnalyticsSeed.newId(); // ordered, then deleted
    private String admin;

    @BeforeEach
    void seed() throws Exception {
        admin = adminToken();
        AnalyticsSeed seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.saveProduct(seed.product(SHOE, "Shoe", "footwear", 500, 10));
        seed.saveProduct(seed.product(BAG, "Bag", "bags", 300, 10));
        seed.save(seed.order("2026-10-05T10:00:00Z", 1050, item(SHOE, 500, 2))
                .append("discount", 100.0).append("tax", 50.0).append("shippingCharges", 100.0)
                .append("couponCode", "DIWALI10")
                .append("payment", new Document("status", "PAID").append("method", "upi")
                        .append("refunds", List.of(refund(200, "processed", "2026-10-07T06:00:00Z")))));
        seed.save(seed.order("2026-10-06T10:00:00Z", 300, item(BAG, 300, 1))
                .append("payment", new Document("status", "PAID").append("method", "card")));
        seed.save(seed.order("2026-10-07T10:00:00Z", 200, item(GONE, 200, 1))
                .append("payment", null)); // legacy order without payment details
        seed.save(seed.order("2026-09-15T10:00:00Z", 500, item(SHOE, 250, 2))
                .append("payment", new Document("status", "PAID").append("method", "upi")));
        AnalyticsSeed other = new AnalyticsSeed(mongo, "another-shop");
        other.save(other.order("2026-10-05T10:00:00Z", 9999, item(SHOE, 9999, 1)));
    }

    private MockHttpServletRequestBuilder sales() {
        return get("/api/v1/dashboard/sales").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void breakdownOfWhatWasChargedAndKept() throws Exception {
        mvc.perform(sales())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.breakdown.charged").value(1550.0))
                .andExpect(jsonPath("$.section.breakdown.discount").value(100.0))
                .andExpect(jsonPath("$.section.breakdown.tax").value(50.0))
                .andExpect(jsonPath("$.section.breakdown.shipping").value(100.0))
                .andExpect(jsonPath("$.section.breakdown.refunds").value(200.0))
                .andExpect(jsonPath("$.section.breakdown.net").value(1350.0))
                .andExpect(jsonPath("$.section.breakdown.couponOrders").value(1))
                .andExpect(jsonPath("$.section.breakdown.couponOrderShare").value(33.3))
                // the Products and Payments tabs show these
                .andExpect(jsonPath("$.section.byCategory").doesNotExist())
                .andExpect(jsonPath("$.section.byPaymentMethod").doesNotExist());
    }

    @Test
    void netAgreesWithTheOverview() throws Exception {
        mvc.perform(get("/api/v1/dashboard/overview").header("Authorization", bearer(admin))
                        .param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(jsonPath("$.section.current.revenue").value(1350.0));
    }

    @Test
    void comparisonTrendIsReturnedBesideTheCurrentOne() throws Exception {
        mvc.perform(sales())
                .andExpect(jsonPath("$.section.series.length()").value(31))
                .andExpect(jsonPath("$.section.previousSeries.length()").value(31))
                .andExpect(jsonPath("$.section.previousSeries[15].revenue").value(500.0));
        mvc.perform(sales().param("compare", "none"))
                .andExpect(jsonPath("$.section.previousSeries").doesNotExist());
    }

    @Test
    void categoryFilterKeepsOnlyItemFigures() throws Exception {
        mvc.perform(sales().param("category", "footwear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.breakdown").doesNotExist());
    }

    @Test
    void exportsTheTrendWithTheComparison() throws Exception {
        mvc.perform(sales().param("format", "csv"))
                .andExpect(content().string(startsWith("start,revenue,orders,previousRevenue\r\n")));
    }
}
