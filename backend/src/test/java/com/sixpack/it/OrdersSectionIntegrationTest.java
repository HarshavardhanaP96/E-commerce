package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.history;
import static com.sixpack.it.AnalyticsSeed.item;
import static com.sixpack.it.AnalyticsSeed.refund;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class OrdersSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private static final String BAG = AnalyticsSeed.newId();
    private String admin;
    private AnalyticsSeed seed;
    private String ancient;

    @BeforeEach
    void seedOrders() throws Exception {
        admin = adminToken();
        seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.saveProduct(seed.product(SHOE, "Shoe", "footwear", 500, 10));
        seed.saveProduct(seed.product(BAG, "Bag", "bags", 100, 10));
        seed.save(seed.order("2026-10-02T04:30:00Z", 950, item(SHOE, 500, 1), item(BAG, 100, 2))
                .append("orderNumber", "ORD-1").append("status", "Delivered").append("discount", 50.0)
                .append("couponCode", "diwali10")
                .append("statusHistory", List.of(history("Processing", "2026-10-02T04:30:00Z"),
                        history("Shipped", "2026-10-03T04:30:00Z"), history("Delivered", "2026-10-05T04:30:00Z"))));
        seed.save(seed.order("2026-10-04T04:30:00Z", 500, item(SHOE, 500, 1))
                .append("orderNumber", "ORD-2").append("status", "Shipped")
                .append("statusHistory", List.of(history("Shipped", "2026-10-06T04:30:00Z")))
                .append("payment", new Document("status", "PAID")
                        .append("refunds", List.of(refund(100, "processed", "2026-10-08T06:00:00Z")))));
        seed.save(seed.order("2026-10-06T04:30:00Z", 300, item(BAG, 300, 1))
                .append("orderNumber", "ORD-3").append("status", "Delivered").append("payment", null)); // legacy
        seed.save(seed.order("2026-10-20T04:30:00Z", 600, item(BAG, 100, 6))
                .append("orderNumber", "ORD-4").append("couponCode", "DIWALI10").append("discount", 60.0));
        ancient = seed.save(seed.order("2020-01-01T04:30:00Z", 100, item(BAG, 100, 1))
                .append("orderNumber", "ORD-0")).toHexString();
    }

    private MockHttpServletRequestBuilder orders() {
        return get("/api/v1/dashboard/orders").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31").param("compare", "none");
    }

    @Test
    void statusFulfilmentSizeAndRefunds() throws Exception {
        mvc.perform(orders())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.byStatus.processing").value(1))
                .andExpect(jsonPath("$.section.byStatus.shipped").value(1))
                .andExpect(jsonPath("$.section.byStatus.delivered").value(2))
                .andExpect(jsonPath("$.section.fulfilment.placedToShipped.medianHours").value(24.0))
                .andExpect(jsonPath("$.section.fulfilment.placedToShipped.p90Hours").value(48.0))
                .andExpect(jsonPath("$.section.fulfilment.shippedToDelivered.medianHours").value(48.0))
                .andExpect(jsonPath("$.section.fulfilment.placedToDelivered.medianHours").value(72.0))
                .andExpect(jsonPath("$.section.fulfilment.withoutHistory").value(1))
                .andExpect(jsonPath("$.section.size.averageItems").value(2.75))
                .andExpect(jsonPath("$.section.size.bands['6+']").value(1))
                .andExpect(jsonPath("$.section.size.topCoupons[0].code").value("DIWALI10"))
                .andExpect(jsonPath("$.section.size.topCoupons[0].orders").value(2))
                .andExpect(jsonPath("$.section.refunds.orders").value(1))
                .andExpect(jsonPath("$.section.refunds.amount").value(100.0))
                .andExpect(jsonPath("$.section.refunds.rate").value(25.0))
                .andExpect(jsonPath("$.section.truncated").value(false))
                .andExpect(jsonPath("$.section.rows").doesNotExist());
    }

    @Test
    void stuckOrdersFollowTheStoreSetting() throws Exception {
        String recent = seed.save(seed.order(Instant.now().minus(Duration.ofDays(10)).toString(), 100,
                item(BAG, 100, 1))).toHexString();
        mvc.perform(orders())
                .andExpect(jsonPath("$.section.stuckAfterDays").value(3))
                .andExpect(jsonPath("$.section.stuck[0].id").value(ancient))
                .andExpect(jsonPath("$.section.stuck[*].id", hasItem(recent)));
        try {
            stuckOrderDays(60);
            mvc.perform(orders())
                    .andExpect(jsonPath("$.section.stuckAfterDays").value(60))
                    .andExpect(jsonPath("$.section.stuck[*].id", not(hasItem(recent))));
        } finally {
            stuckOrderDays(null);
        }
    }

    @Test
    void categoryFilterHidesRefunds() throws Exception {
        mvc.perform(orders().param("category", "footwear"))
                .andExpect(jsonPath("$.section.refunds").doesNotExist())
                .andExpect(jsonPath("$.section.byStatus.delivered").value(1));
    }

    @Test
    void exportsOneRowPerOrderNewestFirst() throws Exception {
        mvc.perform(orders().param("format", "csv"))
                .andExpect(content().string(startsWith(
                        "orderNumber,placedAt,status,items,total,discount,paymentMethod,hoursToShip,hoursToDeliver\r\n"
                                + "ORD-4,2026-10-20T04:30:00Z,Processing,6,600,60,,,\r\n")));
    }

    private void stuckOrderDays(Integer days) throws Exception {
        Map<String, Object> commerce = new HashMap<>();
        commerce.put("stuckOrderDays", days);
        mvc.perform(put("/api/v1/store/settings").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("commerce", commerce))))
                .andExpect(status().isOk());
    }
}
