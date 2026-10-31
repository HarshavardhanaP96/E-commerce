package com.sixpack.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketingDomainTest {

    @Test
    void providersUseLowerCaseKeysWithGoogleFirst() {
        assertThat(Provider.values()).containsExactly(Provider.GOOGLE, Provider.META);
        assertThat(Provider.GOOGLE.key()).isEqualTo("google");
        assertThat(Provider.fromKey("google")).contains(Provider.GOOGLE);
        assertThat(Provider.fromKey("meta")).contains(Provider.META);
        assertThat(Provider.fromKey("GOOGLE")).isEmpty();
        assertThat(Provider.fromKey("tiktok")).isEmpty();
        assertThat(Provider.fromKey(null)).isEmpty();
    }

    @Test
    void anIntegrationWithIdsIsActiveAndWithoutIdsIsNotConnected() {
        Integration none = Integration.notConnected(Provider.META);
        assertThat(none.status()).isEqualTo(Integration.Status.NOT_CONNECTED);
        assertThat(none.browser()).isEqualTo(BrowserTags.NONE);
        assertThat(none.updatedAt()).isNull();

        Integration pixel = new Integration(Provider.META, new BrowserTags(null, null, null, null, "1234567890"),
                Instant.EPOCH);
        assertThat(pixel.status()).isEqualTo(Integration.Status.ACTIVE);
    }
}
