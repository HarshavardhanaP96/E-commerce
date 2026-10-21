package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class OverviewSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private String admin;

    @BeforeEach
    void seedOrders() throws Exception {
        admin = adminToken();
        AnalyticsSeed seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.save(seed.order("2026-10-05T20:00:00Z", 1000, item(SHOE, 500, 2))); // Tue 6 Oct 01:30 in India
        seed.save(seed.order("2026-10-05T10:00:00Z", 500, item(SHOE, 500, 1)));  // Mon 5 Oct 15:30 in India
        AnalyticsSeed other = new AnalyticsSeed(mongo, "another-shop");
        other.save(other.order("2026-10-05T10:00:00Z", 9999, item(SHOE, 9999, 1)));
    }

    private MockHttpServletRequestBuilder overview() {
        return get("/api/v1/dashboard/overview").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void headlineNumbersAndWindowForTheRange() throws Exception {
        mvc.perform(overview())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.from").value("2026-10-01"))
                .andExpect(jsonPath("$.section.compareFrom").value("2026-08-31"))
                .andExpect(jsonPath("$.section.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.section.current.orders").value(2))
                .andExpect(jsonPath("$.section.current.revenue").value(1500.0))
                .andExpect(jsonPath("$.section.series.length()").value(31));
    }

    @Test
    void ordersByWeekdayAndHourInTheStoreZone() throws Exception {
        mvc.perform(overview())
                .andExpect(jsonPath("$.section.byWeekday[0].orders").value(1))
                .andExpect(jsonPath("$.section.byWeekday[0].revenue").value(500.0))
                .andExpect(jsonPath("$.section.byWeekday[1].orders").value(1))
                .andExpect(jsonPath("$.section.byHour[15]").value(1))
                .andExpect(jsonPath("$.section.byHour[1]").value(1))
                .andExpect(jsonPath("$.section.heat[1][1]").value(1))
                .andExpect(jsonPath("$.section.heat[0][15]").value(1));
    }

    @Test
    void weekdayAndHourFollowTheStoreZone() throws Exception {
        try {
            storeTimeZone("UTC");
            mvc.perform(overview())
                    .andExpect(jsonPath("$.section.timezone").value("UTC"))
                    .andExpect(jsonPath("$.section.byWeekday[0].orders").value(2))
                    .andExpect(jsonPath("$.section.byHour[20]").value(1))
                    .andExpect(jsonPath("$.section.byHour[10]").value(1));
        } finally {
            storeTimeZone(null);
        }
    }

    @Test
    void exportsTheTrendAsCsv() throws Exception {
        mvc.perform(overview().param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        containsString("filename=\"overview-2026-10-01_2026-10-31.csv\"")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "start,revenue,orders\r\n2026-10-01,0,0\r\n")));

        List<Document> audits = auditEntries("ANALYTICS_EXPORTED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getString("entityType")).isEqualTo("report");
        assertThat(audits.get(0).getString("entityId")).isEqualTo("overview");
    }

    @Test
    void readingTheSectionIsNotAudited() throws Exception {
        mvc.perform(overview()).andExpect(status().isOk());
        assertThat(auditEntries("ANALYTICS_EXPORTED")).isEmpty();
    }

    @Test
    void rejectsBadParameters() throws Exception {
        mvc.perform(overview().param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(overview().param("format", "xml")).andExpect(status().isBadRequest());
        mvc.perform(overview().param("compare", "sometimes")).andExpect(status().isBadRequest());
    }

    @Test
    void customersCannotReadIt() throws Exception {
        String user = register("shopper@test.dev");
        mvc.perform(get("/api/v1/dashboard/overview").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    private void storeTimeZone(String zone) throws Exception {
        Map<String, Object> regional = new HashMap<>();
        regional.put("timeZone", zone);
        mvc.perform(put("/api/v1/store/settings").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("regional", regional))))
                .andExpect(status().isOk());
    }
}
