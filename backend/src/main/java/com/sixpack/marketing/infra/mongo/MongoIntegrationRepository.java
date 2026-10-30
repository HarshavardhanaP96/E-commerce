package com.sixpack.marketing.infra.mongo;

import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.tenant.TenantMongo;
import java.util.List;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoIntegrationRepository implements IntegrationRepository {

    private final TenantMongo mongo;

    MongoIntegrationRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<Integration> findAll() {
        return mongo.find(new Query(), MarketingIntegrationDocument.class).stream()
                .map(MongoIntegrationRepository::toDomain).toList();
    }

    /** One atomic upsert per {tenantId, provider}; TenantMongo copies the tenant onto a new row. */
    @Override
    public Integration save(Integration integration) {
        BrowserTags t = integration.browser();
        mongo.upsert(byProvider(integration.provider()), new Update()
                .set("browser.ga4MeasurementId", t.ga4MeasurementId())
                .set("browser.adsConversionId", t.adsConversionId())
                .set("browser.adsPurchaseLabel", t.adsPurchaseLabel())
                .set("browser.gtmContainerId", t.gtmContainerId())
                .set("browser.pixelId", t.pixelId())
                .set("updatedAt", integration.updatedAt()), MarketingIntegrationDocument.class);
        return integration;
    }

    @Override
    public boolean delete(Provider provider) {
        return mongo.remove(byProvider(provider), MarketingIntegrationDocument.class).getDeletedCount() > 0;
    }

    private static Query byProvider(Provider provider) {
        return Query.query(Criteria.where("provider").is(provider.name()));
    }

    /** Re-validates on read: a value edited by hand in the database never reaches a storefront script. */
    private static Integration toDomain(MarketingIntegrationDocument d) {
        MarketingIntegrationDocument.Browser b = d.browser == null ? new MarketingIntegrationDocument.Browser()
                : d.browser;
        return new Integration(Provider.valueOf(d.provider), new BrowserTags(b.ga4MeasurementId, b.adsConversionId,
                b.adsPurchaseLabel, b.gtmContainerId, b.pixelId), d.updatedAt);
    }
}
