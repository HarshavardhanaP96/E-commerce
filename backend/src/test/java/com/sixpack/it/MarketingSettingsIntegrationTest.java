package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.marketing.domain.MarketingSettings;
import com.sixpack.marketing.domain.MarketingSettingsRepository;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One marketing_settings row per store, reached only through that store. */
class MarketingSettingsIntegrationTest extends FeedIntegrationTest {

    @Autowired
    MarketingSettingsRepository settings;

    Tenant acme;
    Tenant globex;

    @BeforeEach
    void twoStores() {
        acme = feedStore("acme", "Acme", "INR", null);
        globex = feedStore("globex", "Globex", "INR", null);
    }

    private <T> T in(Tenant t, Supplier<T> action) {
        return TenantContext.callAs(ref(t), action);
    }

    @Test
    void aStoreWithoutARowHasTheFeedOff() {
        assertThat(in(acme, settings::find)).isEqualTo(MarketingSettings.NONE);
    }

    @Test
    void theFirstTokenIsKeptThroughOffAndOn() {
        assertThat(in(acme, () -> settings.enableFeed("first"))).isEqualTo(new MarketingSettings(true, "first"));
        TenantContext.runAs(ref(acme), settings::disableFeed);
        assertThat(in(acme, settings::find)).isEqualTo(new MarketingSettings(false, "first"));
        assertThat(in(acme, () -> settings.enableFeed("second"))).isEqualTo(new MarketingSettings(true, "first"));

        List<Document> rows = mongo.getCollection("marketing_settings").find().into(new ArrayList<>());
        assertThat(rows).hasSize(1);
        assertThat(String.valueOf(rows.get(0).get("_id"))).isEqualTo(acme.id());
        assertThat(rows.get(0).getString("tenantId")).isEqualTo(acme.id());
    }

    @Test
    void onlyAnEnabledFeedGetsANewToken() {
        assertThat(in(acme, () -> settings.rotateFeedToken("x"))).isFalse();
        assertThat(in(acme, settings::find)).isEqualTo(MarketingSettings.NONE);

        in(acme, () -> settings.enableFeed("first"));
        assertThat(in(acme, () -> settings.rotateFeedToken("second"))).isTrue();
        TenantContext.runAs(ref(acme), settings::disableFeed);
        assertThat(in(acme, () -> settings.rotateFeedToken("third"))).isFalse();
        assertThat(in(acme, settings::find)).isEqualTo(new MarketingSettings(false, "second"));
    }

    @Test
    void eachStoreHasItsOwnRowAndNoStoreMeansNoAccess() {
        in(acme, () -> settings.enableFeed("acme-token"));
        assertThat(in(globex, settings::find)).isEqualTo(MarketingSettings.NONE);

        in(globex, () -> settings.enableFeed("globex-token"));
        TenantContext.runAs(ref(globex), settings::disableFeed);
        assertThat(in(globex, () -> settings.rotateFeedToken("other"))).isFalse();
        assertThat(in(acme, settings::find)).isEqualTo(new MarketingSettings(true, "acme-token"));

        try (var none = TenantContext.open(null)) {
            assertThatThrownBy(settings::find).isInstanceOf(IllegalStateException.class);
        }
    }
}
