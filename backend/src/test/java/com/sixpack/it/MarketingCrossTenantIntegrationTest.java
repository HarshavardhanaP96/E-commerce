package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Store A's marketing IDs are never read, changed or removed through store B. */
class MarketingCrossTenantIntegrationTest extends MarketingIntegrationTest {

    private static final Instant AT = Instant.parse("2026-10-30T10:00:00Z");
    private static final BrowserTags ACME = new BrowserTags("G-ACME1234", "AW-111111111", "acme_buy", null, null);
    private static final BrowserTags GLOBEX = new BrowserTags("G-GLOBEX12", null, null, null, null);

    @Autowired
    IntegrationRepository integrations;

    Tenant acme;
    Tenant globex;

    @BeforeEach
    void twoStoresWithMarketing() {
        acme = marketing(createTenant("acme", "Acme", "shop.acme.test"), true);
        globex = marketing(createTenant("globex", "Globex", "shop.globex.test"), true);
    }

    private List<Integration> savedIn(Tenant t) {
        return TenantContext.callAs(ref(t), integrations::findAll);
    }

    private void saveIn(Tenant t, BrowserTags tags) {
        TenantContext.runAs(ref(t), () -> integrations.save(new Integration(Provider.GOOGLE, tags, AT)));
    }

    @Test
    void eachStoreReadsAndRemovesOnlyItsOwnRow() {
        saveIn(acme, ACME);
        saveIn(globex, GLOBEX);

        assertThat(savedIn(acme)).extracting(Integration::browser).containsExactly(ACME);
        assertThat(savedIn(globex)).extracting(Integration::browser).containsExactly(GLOBEX);

        assertThat(TenantContext.callAs(ref(globex), () -> integrations.delete(Provider.GOOGLE))).isTrue();
        assertThat(savedIn(globex)).isEmpty();
        assertThat(savedIn(acme)).extracting(Integration::browser).containsExactly(ACME);
    }

    @Test
    void savingAgainReplacesTheStoresOneRowPerProvider() {
        saveIn(acme, ACME);
        BrowserTags changed = new BrowserTags("G-NEW12345", null, null, null, null);
        saveIn(acme, changed);

        assertThat(savedIn(acme)).containsExactly(new Integration(Provider.GOOGLE, changed, AT));
        List<Document> rows = rows(acme);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getString("provider")).isEqualTo("GOOGLE");
        assertThat(rows.get(0).get("browser", Document.class).getString("ga4MeasurementId")).isEqualTo("G-NEW12345");
    }

    @Test
    void theUniqueIndexIsPerStoreAndProvider() {
        saveIn(acme, ACME);

        List<Document> unique = mongo.getCollection("marketing_integrations").listIndexes().into(new ArrayList<>())
                .stream().filter(i -> Boolean.TRUE.equals(i.getBoolean("unique")))
                .map(i -> i.get("key", Document.class)).toList();
        assertThat(unique).contains(new Document("tenantId", 1).append("provider", 1));
    }

    @Test
    void withoutATenantTheRepositoryFailsClosed() {
        assertThatThrownBy(() -> TenantContext.callAs(null, integrations::findAll))
                .isInstanceOf(IllegalStateException.class).hasMessage("No tenant in context");
        assertThatThrownBy(() -> TenantContext.runAs(null, () -> integrations.delete(Provider.GOOGLE)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(null,
                () -> integrations.save(new Integration(Provider.GOOGLE, ACME, AT))))
                .isInstanceOf(IllegalStateException.class);
    }
}
