package com.sixpack.it;

import static com.sixpack.it.AnalyticsSeed.item;
import static com.sixpack.it.AnalyticsSeed.shipTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class LocationSectionIntegrationTest extends IntegrationTest {

    private static final String SHOE = AnalyticsSeed.newId();
    private String admin;

    @BeforeEach
    void seed() throws Exception {
        admin = adminToken();
        AnalyticsSeed seed = new AnalyticsSeed(mongo, tenantRepository.findBySlug("default").orElseThrow().id());
        seed.save(seed.order("2026-10-05T10:00:00Z", 1000, item(SHOE, 1000, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Karnataka", "India", 560001L)));
        seed.save(seed.order("2026-10-06T10:00:00Z", 500, item(SHOE, 500, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("karnataka ", "india", 560034L)));
        seed.save(seed.order("2026-10-07T10:00:00Z", 300, item(SHOE, 300, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("KA", "India", 562157L)));
        seed.save(seed.order("2026-10-08T10:00:00Z", 200, item(SHOE, 200, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Tamil Nadu", "India", 600001L)));
        seed.save(seed.order("2026-10-09T10:00:00Z", 100, item(SHOE, 100, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Bangalore", "India", 560100L)));
        seed.save(seed.order("2026-10-10T10:00:00Z", 50, item(SHOE, 50, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Bagmati", "Nepal", 44600L)));
        seed.save(seed.order("2026-10-11T10:00:00Z", 10, item(SHOE, 10, 1).append("name", "Shoe"))); // legacy: no address
        seed.save(seed.order("2026-09-15T10:00:00Z", 400, item(SHOE, 400, 1).append("name", "Shoe"))
                .append("shippingInfo", shipTo("Karnataka", "India", 560001L)));
    }

    private MockHttpServletRequestBuilder location() {
        return get("/api/v1/dashboard/location").header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31");
    }

    @Test
    void byCountryWithMissingAddressesAsUnknown() throws Exception {
        mvc.perform(location())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section.level").value("country"))
                .andExpect(jsonPath("$.section.rows[0].key").value("india"))
                .andExpect(jsonPath("$.section.rows[0].label").value("India"))
                .andExpect(jsonPath("$.section.rows[0].orders").value(5))
                .andExpect(jsonPath("$.section.rows[0].revenue").value(2100.0))
                .andExpect(jsonPath("$.section.rows[0].share").value(71.4))
                .andExpect(jsonPath("$.section.rows[0].change").value(400))
                .andExpect(jsonPath("$.section.rows[1].label").value("Nepal"))
                .andExpect(jsonPath("$.section.rows[1].small").value(true))
                .andExpect(jsonPath("$.section.rows[2].label").value("Unknown"));
    }

    @Test
    void byStateMergesSpellingsAndShowsWhatWasNotRecognised() throws Exception {
        mvc.perform(location().param("country", "India"))
                .andExpect(jsonPath("$.section.level").value("state"))
                .andExpect(jsonPath("$.section.rows[0].key").value("KA"))
                .andExpect(jsonPath("$.section.rows[0].orders").value(3))
                .andExpect(jsonPath("$.section.rows[0].change").value(200))
                .andExpect(jsonPath("$.section.unrecognised.orders").value(1))
                .andExpect(jsonPath("$.section.unrecognised.samples[0].raw").value("Bangalore"))
                .andExpect(jsonPath("$.section.topProducts[0].name").value("Shoe"));
    }

    @Test
    void byPinPrefixThenFullPin() throws Exception {
        mvc.perform(location().param("country", "India").param("state", "KA"))
                .andExpect(jsonPath("$.section.level").value("pin_prefix"))
                .andExpect(jsonPath("$.section.rows[0].key").value("560"))
                .andExpect(jsonPath("$.section.rows[0].orders").value(2))
                .andExpect(jsonPath("$.section.rows[1].key").value("562"));
        mvc.perform(location().param("country", "India").param("state", "KA").param("pinPrefix", "560"))
                .andExpect(jsonPath("$.section.level").value("pin"))
                .andExpect(jsonPath("$.section.rows.length()").value(2));
    }

    @Test
    void exportNeverHasFullPinCodes() throws Exception {
        mvc.perform(location().param("country", "India").param("state", "KA").param("pinPrefix", "560")
                        .param("format", "csv"))
                .andExpect(content().string(containsString("pin_prefix,560,560,2,")))
                .andExpect(content().string(not(containsString("560001"))))
                .andExpect(content().string(not(containsString("560034"))));

        List<Document> audits = auditEntries("ANALYTICS_EXPORTED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getString("entityId")).isEqualTo("location");
    }

    @Test
    void rejectsDrillsThatMakeNoSense() throws Exception {
        mvc.perform(location().param("state", "KA")).andExpect(status().isBadRequest());
        mvc.perform(location().param("country", "Nepal").param("state", "KA")).andExpect(status().isBadRequest());
        mvc.perform(location().param("country", "India").param("state", "ZZ")).andExpect(status().isBadRequest());
        mvc.perform(location().param("country", "India").param("state", "KA").param("pinPrefix", "56"))
                .andExpect(status().isBadRequest());
    }
}
