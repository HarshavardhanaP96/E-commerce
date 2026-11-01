package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.order.PaidOrders;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.StoreDirectory;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.tenant.TenantRef;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciliation for {@link ConversionRecorder} (no Mongo transactions: the order is saved, then queued). Every few
 * minutes it re-queues consented paid orders of the last 72 h (GA4's limit) that have no row. Safe on every replica:
 * queueing is idempotent.
 */
@Component
public class ConversionSweep {

    private static final Logger log = LoggerFactory.getLogger(ConversionSweep.class);
    static final Duration WINDOW = Duration.ofHours(72);
    /** Leaves the newest minute to the listener, which normally queues within milliseconds. */
    static final Duration GRACE = Duration.ofMinutes(1);
    /** shortcut: at most this many orders per store per run, oldest first; switch to keyset paging past it. */
    static final int LIMIT = 2000;

    private final StoreDirectory stores;
    private final TenantFeatures features;
    private final IntegrationRepository integrations;
    private final PaidOrders orders;
    private final Outbox outbox;
    private final SecretSealer secrets;
    private final ConversionRecorder recorder;

    ConversionSweep(StoreDirectory stores, TenantFeatures features, IntegrationRepository integrations,
                    PaidOrders orders, Outbox outbox, SecretSealer secrets, ConversionRecorder recorder) {
        this.stores = stores;
        this.features = features;
        this.integrations = integrations;
        this.orders = orders;
        this.outbox = outbox;
        this.secrets = secrets;
        this.recorder = recorder;
    }

    @Scheduled(initialDelayString = "${app.marketing.sweep-interval:PT5M}",
            fixedDelayString = "${app.marketing.sweep-interval:PT5M}")
    void scheduled() {
        sweep(Instant.now());
    }

    public void sweep(Instant now) {
        if (!secrets.available()) {
            return;
        }
        for (TenantRef store : stores.stores()) {
            try {
                TenantContext.runAs(store, () -> sweepStore(now));
            } catch (RuntimeException e) {
                log.warn("Marketing sweep failed for store {}", store.slug(), e);
            }
        }
    }

    private void sweepStore(Instant now) {
        if (!features.enabled(Feature.MARKETING)) {
            return;
        }
        List<Provider> on = integrations.findAll().stream().filter(i -> i.server().enabled())
                .map(Integration::provider).toList();
        if (on.isEmpty()) {
            return;
        }
        List<String> ids = orders.consentedBetween(now.minus(WINDOW), now.minus(GRACE), LIMIT);
        Set<String> complete = new HashSet<>(ids);
        for (Provider p : on) {
            complete.retainAll(outbox.existingEventIds(p, ids));
        }
        ids.stream().filter(id -> !complete.contains(id)).forEach(recorder::record);
    }
}
