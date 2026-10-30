package com.sixpack.marketing.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.config.CacheConfig;
import com.sixpack.shared.tenant.MarketingTags;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The store's marketing integrations. Callers check {@code Feature.MARKETING} first. */
@Service
public class IntegrationService implements MarketingTags {

    private final IntegrationRepository integrations;
    private final AuditTrail audit;

    IntegrationService(IntegrationRepository integrations, AuditTrail audit) {
        this.integrations = integrations;
        this.audit = audit;
    }

    /** One entry per provider in {@link Provider} order; a provider with nothing saved is NOT_CONNECTED. */
    public List<Integration> all() {
        Map<Provider, Integration> saved = new EnumMap<>(Provider.class);
        integrations.findAll().forEach(i -> saved.put(i.provider(), i));
        return Arrays.stream(Provider.values())
                .map(p -> saved.getOrDefault(p, Integration.notConnected(p))).toList();
    }

    /** Saves the provider's IDs; with none left the row is removed and the card is NOT_CONNECTED again. */
    @CacheEvict(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
    public Integration save(Provider provider, BrowserTags tags) {
        Integration before = find(provider);
        Integration after;
        if (tags.isEmpty()) {
            integrations.delete(provider);
            after = Integration.notConnected(provider);
        } else {
            // Millisecond precision: what Mongo keeps, so the response matches the next GET.
            after = integrations.save(new Integration(provider, tags, Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        }
        audit.record(AuditEvent.of(AuditAction.INTEGRATION_UPDATED).on(MarketingAudit.ENTITY, provider.key())
                .changing(Changes.diff(MarketingAudit.view(before.browser()), MarketingAudit.view(after.browser()),
                        MarketingAudit.POLICY)));
        return after;
    }

    /** Removes the provider's row; audited only when there was one. */
    @CacheEvict(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
    public Integration disconnect(Provider provider) {
        Integration before = find(provider);
        if (integrations.delete(provider)) {
            audit.record(AuditEvent.of(AuditAction.INTEGRATION_DISCONNECTED).on(MarketingAudit.ENTITY, provider.key())
                    .changing(Changes.diff(MarketingAudit.view(before.browser()), Map.of(), MarketingAudit.POLICY)));
        }
        return Integration.notConnected(provider);
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
    public Tags current() {
        Map<Provider, BrowserTags> by = new EnumMap<>(Provider.class);
        all().forEach(i -> by.put(i.provider(), i.browser()));
        BrowserTags google = by.get(Provider.GOOGLE);
        BrowserTags meta = by.get(Provider.META);
        return new Tags(google.ga4MeasurementId(), google.adsConversionId(), google.adsPurchaseLabel(),
                google.gtmContainerId(), meta.pixelId());
    }

    private Integration find(Provider provider) {
        return all().stream().filter(i -> i.provider() == provider).findFirst().orElseThrow();
    }
}
