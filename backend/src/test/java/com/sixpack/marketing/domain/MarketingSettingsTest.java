package com.sixpack.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketingSettingsTest {

    @Test
    void onlyTheExactTokenOfAnEnabledFeedOpensIt() {
        MarketingSettings on = new MarketingSettings(true, "abc_DEF-123");

        assertThat(on.opensFeed("abc_DEF-123")).isTrue();
        assertThat(on.opensFeed("abc_def-123")).isFalse();
        assertThat(on.opensFeed("abc_DEF-12")).isFalse();
        assertThat(on.opensFeed("abc_DEF-1234")).isFalse();
        assertThat(on.opensFeed("")).isFalse();
        assertThat(on.opensFeed(null)).isFalse();
        assertThat(new MarketingSettings(false, "abc_DEF-123").opensFeed("abc_DEF-123")).isFalse();
        assertThat(MarketingSettings.NONE.opensFeed(null)).isFalse();
    }

    @Test
    void theTokenNeverShowsInItsTextForm() {
        assertThat(new MarketingSettings(true, "secret-token").toString())
                .doesNotContain("secret-token").contains("feedEnabled=true");
    }
}
