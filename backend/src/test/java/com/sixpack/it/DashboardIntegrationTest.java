package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class DashboardIntegrationTest extends IntegrationTest {

    @Test
    void returnsWellFormedPayloads() throws Exception {
        String admin = adminToken();
        createProduct(admin, "Shoe", 1000, 0, "footwear");
        createProduct(admin, "Laptop", 50000, 3, "electronics");
        register("dash@test.dev");

        mvc.perform(get("/api/v1/dashboard/stats").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.count.product").value(2))
                .andExpect(jsonPath("$.stats.count.user").value(2))
                .andExpect(jsonPath("$.stats.chart.order.length()").value(6))
                .andExpect(jsonPath("$.stats.categoryCount[0].percent").value(50))
                .andExpect(jsonPath("$.stats.genderRatio.female").value(1));
        mvc.perform(get("/api/v1/dashboard/pie").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.charts.stockAvailability.outOfStock").value(1))
                .andExpect(jsonPath("$.charts.userCategory.admin").value(1));
        mvc.perform(get("/api/v1/dashboard/bar").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.charts.orders.length()").value(12))
                .andExpect(jsonPath("$.charts.products[5]").value(2.0));
        mvc.perform(get("/api/v1/dashboard/line").param("category", "footwear").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.charts.products[11]").value(1.0));
    }
}
