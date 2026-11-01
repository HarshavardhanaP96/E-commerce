package com.sixpack.marketing.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.MarketingDestination.Credentials;
import com.sixpack.marketing.domain.MarketingDestination.TestVerdict;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.TenantContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The owner's view of server events: the delivery log and the test-event button. */
@Service
public class ServerEventsService {

    static final int CARD_DELIVERIES = 10;
    static final int PAGE_SIZE = 20;
    static final int TESTS_PER_MINUTE = 10;

    private final Outbox outbox;
    private final IntegrationService integrations;
    private final SecretSealer secrets;
    private final Map<Provider, MarketingDestination> destinations = new EnumMap<>(Provider.class);
    private final StoreConfig store;
    private final AuditTrail audit;
    private final FixedWindowLimiter limiter = new FixedWindowLimiter(TESTS_PER_MINUTE);

    ServerEventsService(Outbox outbox, IntegrationService integrations, SecretSealer secrets,
                        List<MarketingDestination> destinations, StoreConfig store, AuditTrail audit) {
        this.outbox = outbox;
        this.integrations = integrations;
        this.secrets = secrets;
        destinations.forEach(d -> this.destinations.put(d.provider(), d));
        this.store = store;
        this.audit = audit;
    }

    public List<OutboxRow> recent(Provider provider) {
        return outbox.recent(provider, 1, CARD_DELIVERIES).rows();
    }

    public Outbox.Page page(Provider provider, int page) {
        return outbox.recent(provider, page, PAGE_SIZE);
    }

    /** One synthetic purchase to the provider's test channel, synchronously: the owner waits for the verdict. */
    public TestVerdict test(Provider provider) {
        if (!secrets.available()) {
            throw ApiException.badRequest("server: not available on this platform yet");
        }
        Integration i = integrations.find(provider);
        if (i.serverId() == null) {
            throw ApiException.badRequest("server: save the " + IntegrationService.idName(provider) + " first");
        }
        if (!i.server().hasSecret()) {
            throw ApiException.badRequest("server.secret: save the secret first");
        }
        if (provider == Provider.META && i.server().testEventCode() == null) {
            throw ApiException.badRequest("server.testEventCode: set a test event code from Events Manager first");
        }
        String tenantId = TenantContext.require().id();
        if (!limiter.tryAcquire(tenantId, System.currentTimeMillis())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many test events, please wait a minute");
        }
        TestVerdict verdict;
        try {
            String secret = secrets.open(i.server().secret(),
                    SecretSealer.aad(tenantId, provider, ServerSettings.SECRET_FIELD));
            verdict = destinations.get(provider).test(new Credentials(i.serverId(), secret),
                    i.server().testEventCode(), store.currency());
        } catch (SecretSealer.UnreadableSecretException e) {
            verdict = new TestVerdict(false, DeliveryCode.SECRET_UNREADABLE.name(),
                    DeliveryCode.SECRET_UNREADABLE.message(provider));
        }
        AuditEvent event = AuditEvent.of(AuditAction.INTEGRATION_TESTED).on(MarketingAudit.ENTITY, provider.key());
        audit.record(verdict.ok() ? event : event.failed());
        return verdict;
    }
}
