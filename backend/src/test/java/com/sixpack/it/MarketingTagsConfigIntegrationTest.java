package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** /tenant/config ships a store's own public tag IDs, only with marketing on and at least one ID saved. */
class MarketingTagsConfigIntegrationTest extends MarketingIntegrationTest {

    Tenant acme;
    Tenant globex;
    String acmeAdmin;
    String globexAdmin;

    @BeforeEach
    void twoStoresWithMarketing() throws Exception {
        acme = marketing(createTenant("acme", "Acme", "shop.acme.test"), true);
        globex = marketing(createTenant("globex", "Globex", "shop.globex.test"), true);
        acmeAdmin = adminTokenFor(acme);
        globexAdmin = adminTokenFor(globex);
    }

    private JsonNode config(Tenant t) throws Exception {
        return body(mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(t))))
                .andExpect(status().isOk()).andReturn()).get("tenant");
    }

    @Test
    void savedIdsAreShippedAsTheFiveTagsAndNothingElse() throws Exception {
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234", "adsConversionId", "AW-111111111",
                "adsPurchaseLabel", "acme_buy", "gtmContainerId", "GTM-ACME12")).andExpect(status().isOk());
        save(acme, acmeAdmin, "meta", browser("pixelId", "1111111111")).andExpect(status().isOk());

        JsonNode marketing = config(acme).get("marketing");
        assertThat(fieldNames(marketing)).containsExactly("tags");
        JsonNode tags = marketing.get("tags");
        assertThat(fieldNames(tags)).containsExactly("ga4", "adsId", "adsPurchaseLabel", "gtm", "metaPixel");
        assertThat(tags.get("ga4").asText()).isEqualTo("G-ACME1234");
        assertThat(tags.get("adsId").asText()).isEqualTo("AW-111111111");
        assertThat(tags.get("adsPurchaseLabel").asText()).isEqualTo("acme_buy");
        assertThat(tags.get("gtm").asText()).isEqualTo("GTM-ACME12");
        assertThat(tags.get("metaPixel").asText()).isEqualTo("1111111111");
    }

    @Test
    void withNothingSavedThereIsNoMarketingKey() throws Exception {
        assertThat(config(acme).has("marketing")).isFalse();
    }

    @Test
    void turningTheFeatureOffHidesSavedTagsAtOnce() throws Exception {
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234")).andExpect(status().isOk());
        assertThat(config(acme).get("marketing").get("tags").get("ga4").asText()).isEqualTo("G-ACME1234");

        acme = marketing(acme, false);

        assertThat(config(acme).has("marketing")).isFalse();
    }

    @Test
    void aSaveOrDisconnectShowsOnTheNextConfig() throws Exception {
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234")).andExpect(status().isOk());
        assertThat(config(acme).get("marketing").get("tags").get("ga4").asText()).isEqualTo("G-ACME1234");

        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME5678")).andExpect(status().isOk());
        assertThat(config(acme).get("marketing").get("tags").get("ga4").asText()).isEqualTo("G-ACME5678");

        disconnect(acme, acmeAdmin, "google").andExpect(status().isOk());
        assertThat(config(acme).has("marketing")).isFalse();
    }

    @Test
    void oneStoresTagsNeverAppearInAnothers() throws Exception {
        save(acme, acmeAdmin, "google", browser("ga4MeasurementId", "G-ACME1234")).andExpect(status().isOk());
        assertThat(config(acme).get("marketing").get("tags").get("ga4").asText()).isEqualTo("G-ACME1234");
        assertThat(config(globex).has("marketing")).isFalse(); // acme's tags are cached now; globex must not get them

        save(globex, globexAdmin, "meta", browser("pixelId", "2222222222")).andExpect(status().isOk());

        JsonNode acmeTags = config(acme).get("marketing").get("tags");
        JsonNode globexTags = config(globex).get("marketing").get("tags");
        assertThat(acmeTags.get("metaPixel").isNull()).isTrue();
        assertThat(globexTags.get("ga4").isNull()).isTrue();
        assertThat(globexTags.get("metaPixel").asText()).isEqualTo("2222222222");
        assertThat(config(globex).toString()).doesNotContain("G-ACME1234");
    }
}
