package com.sixpack.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderAttributionTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final String UA = "Mozilla/5.0 test";
    private static final String URL = "https://shop.acme.test/shipping";

    private static OrderAttribution.Raw raw(Boolean analytics, Boolean marketing, String version) {
        return new OrderAttribution.Raw("google", "cpc", "diwali", "ad-1", "lamps", "Cj0KCQ-abc", "IwAR-xyz",
                "fb.1.1730000000000.1098115397", "fb.1.1730000000000.IwAR-xyz", "1234567890.1730000000",
                "/product/lamp", analytics, marketing, version);
    }

    @Test
    void withoutAConsentSnapshotNothingIsKept() {
        assertThat(OrderAttribution.from(null, UA, URL, NOW)).isNull();
        assertThat(OrderAttribution.from(raw(null, true, "2026-10-31"), UA, URL, NOW)).isNull();
        assertThat(OrderAttribution.from(raw(true, true, null), UA, URL, NOW)).isNull();
        assertThat(OrderAttribution.from(raw(true, true, "<script>"), UA, URL, NOW)).isNull();
    }

    @Test
    void marketingConsentKeepsCampaignDataClickIdsAndTheBrowserButNotTheGaId() {
        OrderAttribution a = OrderAttribution.from(raw(false, true, "2026-10-31"), UA, URL, NOW);
        assertThat(a.utmSource()).isEqualTo("google");
        assertThat(a.utmTerm()).isEqualTo("lamps");
        assertThat(a.gclid()).isEqualTo("Cj0KCQ-abc");
        assertThat(a.fbp()).isEqualTo("fb.1.1730000000000.1098115397");
        assertThat(a.fbc()).isEqualTo("fb.1.1730000000000.IwAR-xyz");
        assertThat(a.userAgent()).isEqualTo(UA);
        assertThat(a.sourceUrl()).isEqualTo(URL);
        assertThat(a.landingPath()).isEqualTo("/product/lamp");
        assertThat(a.gaClientId()).isNull();
        assertThat(a.consent()).isEqualTo(new OrderAttribution.Consent(false, true, "2026-10-31", NOW));
    }

    @Test
    void analyticsOnlyKeepsTheGaIdAndLandingPathOnly() {
        OrderAttribution a = OrderAttribution.from(raw(true, false, "2026-10-31"), UA, URL, NOW);
        assertThat(a.gaClientId()).isEqualTo("1234567890.1730000000");
        assertThat(a.landingPath()).isEqualTo("/product/lamp");
        assertThat(a.utmSource()).isNull();
        assertThat(a.gclid()).isNull();
        assertThat(a.fbp()).isNull();
        assertThat(a.userAgent()).isNull();
        assertThat(a.sourceUrl()).isNull();
    }

    @Test
    void declinedEverythingKeepsOnlyTheChoice() {
        OrderAttribution a = OrderAttribution.from(raw(false, false, "2026-10-31"), UA, URL, NOW);
        assertThat(a.landingPath()).isNull();
        assertThat(a.utmSource()).isNull();
        assertThat(a.consent().marketing()).isFalse();
    }

    @Test
    void junkIsDroppedOrCutNeverRejected() {
        OrderAttribution a = OrderAttribution.from(new OrderAttribution.Raw("x".repeat(500), " tab\tand\nnewline ",
                "  ", null, "दिवाली सेल", "<script>", "IwAR xyz", "fb.1.abc", "javascript:alert(1)", "GA1.1.1.2",
                "https://evil.example/path", true, true, "2026-10-31"), "u".repeat(900), URL, NOW);
        assertThat(a.utmSource()).hasSize(150);
        assertThat(a.utmMedium()).isEqualTo("tab and newline");
        assertThat(a.utmCampaign()).isNull();
        assertThat(a.utmTerm()).isEqualTo("दिवाली सेल"); // non-ASCII campaign names are fine
        assertThat(a.gclid()).isNull();
        assertThat(a.fbclid()).isNull();
        assertThat(a.fbp()).isNull();
        assertThat(a.fbc()).isNull();
        assertThat(a.gaClientId()).isNull();
        assertThat(a.landingPath()).isNull();
        assertThat(a.userAgent()).hasSize(512);
        assertThat(OrderAttribution.from(new OrderAttribution.Raw(null, null, null, null, null, null, null, null, null,
                null, "//evil.example", true, true, "2026-10-31"), UA, URL, NOW).landingPath()).isNull();
    }
}
