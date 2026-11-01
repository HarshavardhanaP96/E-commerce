package com.sixpack.marketing.infra.mongo;

import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.LastError;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Instant;
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

    @Override
    public void saveServer(Provider provider, ServerSettings server, boolean newSecret, Instant updatedAt) {
        Update update = new Update().set("server.enabled", server.enabled())
                .set("server.testEventCode", server.testEventCode()).set("updatedAt", updatedAt);
        if (newSecret) {
            SealedSecret s = server.secret();
            update.set("secrets.server.keyId", s.keyId()).set("secrets.server.iv", s.iv())
                    .set("secrets.server.ciphertext", s.ciphertext()).unset("lastError");
        }
        mongo.updateFirst(byProvider(provider), update, MarketingIntegrationDocument.class);
    }

    @Override
    public void recordFailure(Provider provider, LastError error) {
        mongo.updateFirst(byProvider(provider), new Update().set("lastError.at", error.at())
                .set("lastError.code", error.code()).set("lastError.message", error.message()),
                MarketingIntegrationDocument.class);
    }

    @Override
    public void recordSuccess(Provider provider, Instant at) {
        mongo.updateFirst(byProvider(provider), new Update().set("lastSuccessAt", at),
                MarketingIntegrationDocument.class);
    }

    /** Re-validates on read: a value edited by hand in the database never reaches a storefront script. */
    private static Integration toDomain(MarketingIntegrationDocument d) {
        MarketingIntegrationDocument.Browser b = d.browser == null ? new MarketingIntegrationDocument.Browser()
                : d.browser;
        MarketingIntegrationDocument.Sealed s = d.secrets == null ? null : d.secrets.server;
        ServerSettings server = new ServerSettings(d.server != null && Boolean.TRUE.equals(d.server.enabled),
                d.server == null ? null : d.server.testEventCode,
                s == null ? null : new SealedSecret(s.keyId, s.iv, s.ciphertext));
        LastError error = d.lastError == null ? null
                : new LastError(d.lastError.at, d.lastError.code, d.lastError.message);
        return new Integration(Provider.valueOf(d.provider), new BrowserTags(b.ga4MeasurementId, b.adsConversionId,
                b.adsPurchaseLabel, b.gtmContainerId, b.pixelId), server, error, d.lastSuccessAt, d.updatedAt);
    }
}
