package com.sixpack.marketing.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.config.CacheConfig;
import com.sixpack.shared.tenant.MarketingTags;
import com.sixpack.shared.tenant.TenantContext;
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

    /** A change of the server-side settings. {@code secret} null keeps the stored one. */
    public record ServerChange(boolean enabled, String secret, String testEventCode) {
    }

    private final IntegrationRepository integrations;
    private final AuditTrail audit;
    private final SecretSealer secrets;

    IntegrationService(IntegrationRepository integrations, AuditTrail audit, SecretSealer secrets) {
        this.integrations = integrations;
        this.audit = audit;
        this.secrets = secrets;
    }

    /** False without MARKETING_KEYS: the cards say server events are unavailable. */
    public boolean serverAvailable() {
        return secrets.available();
    }

    /** One entry per provider in {@link Provider} order; a provider with nothing saved is NOT_CONNECTED. */
    public List<Integration> all() {
        Map<Provider, Integration> saved = new EnumMap<>(Provider.class);
        integrations.findAll().forEach(i -> saved.put(i.provider(), i));
        return Arrays.stream(Provider.values())
                .map(p -> saved.getOrDefault(p, Integration.notConnected(p))).toList();
    }

    /** Phase 1 entry point: IDs only, server settings kept. */
    public Integration save(Provider provider, BrowserTags tags) {
        return save(provider, tags, null);
    }

    /**
     * Saves the provider's IDs ({@code tags} null keeps the saved ones) and, when {@code change} is given, its server
     * settings. With no IDs left the row, secret included, is removed and the card is NOT_CONNECTED again.
     */
    @CacheEvict(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
    public Integration save(Provider provider, BrowserTags tags, ServerChange change) {
        Integration before = find(provider);
        BrowserTags browser = tags != null ? tags : before.browser();
        Integration after;
        if (browser.isEmpty()) {
            if (tags == null) {
                throw ApiException.badRequest("server: save the " + idName(provider) + " first");
            }
            integrations.delete(provider);
            after = Integration.notConnected(provider);
        } else {
            ServerSettings server = change == null ? before.server() : server(provider, browser, before.server(), change);
            boolean newSecret = change != null && change.secret() != null;
            // Millisecond precision: what Mongo keeps, so the response matches the next GET.
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            if (tags != null) {
                integrations.save(new Integration(provider, browser, now));
            }
            if (change != null) {
                integrations.saveServer(provider, server, newSecret, now);
            }
            after = new Integration(provider, browser, server, newSecret ? null : before.lastError(),
                    before.lastSuccessAt(), now);
        }
        audit.record(AuditEvent.of(AuditAction.INTEGRATION_UPDATED).on(MarketingAudit.ENTITY, provider.key())
                .changing(Changes.diff(MarketingAudit.view(before), MarketingAudit.view(after), MarketingAudit.POLICY)));
        return after;
    }

    /** Removes the provider's row (and its secret); audited only when there was one. */
    @CacheEvict(cacheNames = CacheConfig.MARKETING_TAGS, key = "'tags'")
    public Integration disconnect(Provider provider) {
        Integration before = find(provider);
        Integration after = Integration.notConnected(provider);
        if (integrations.delete(provider)) {
            audit.record(AuditEvent.of(AuditAction.INTEGRATION_DISCONNECTED).on(MarketingAudit.ENTITY, provider.key())
                    .changing(Changes.diff(MarketingAudit.view(before), MarketingAudit.view(after),
                            MarketingAudit.POLICY)));
        }
        return after;
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

    Integration find(Provider provider) {
        return all().stream().filter(i -> i.provider() == provider).findFirst().orElseThrow();
    }

    static String idName(Provider provider) {
        return provider == Provider.META ? "Meta Pixel ID" : "GA4 measurement ID";
    }

    private ServerSettings server(Provider provider, BrowserTags browser, ServerSettings current, ServerChange change) {
        try {
            String secret = change.secret() == null ? null : ServerSettings.checkSecret(change.secret());
            boolean wantsServer = change.enabled() || secret != null;
            if (wantsServer && !secrets.available()) {
                throw ApiException.badRequest("server: not available on this platform yet");
            }
            if (wantsServer && Integration.serverId(provider, browser) == null) {
                throw ApiException.badRequest("server: save the " + idName(provider) + " first");
            }
            SealedSecret sealed = secret == null ? current.secret() : secrets.seal(secret,
                    SecretSealer.aad(TenantContext.require().id(), provider, ServerSettings.SECRET_FIELD));
            if (change.enabled() && sealed == null) {
                throw ApiException.badRequest("server.secret: is required to turn on server events");
            }
            return new ServerSettings(change.enabled(), provider == Provider.META ? change.testEventCode() : null,
                    sealed);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage()); // our own field checks only
        }
    }
}
