package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.LastError;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.MarketingDestination.Credentials;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.order.PaidOrders;
import com.sixpack.order.PaidOrders.PaidOrder;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.StoreDirectory;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.tenant.TenantRef;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends queued purchases (Consistency & failure D3–D11). One thread per provider (bulkhead); each pass walks the
 * stores, claims one row at a time with a lease, builds the payload from the order as it is now and calls the
 * provider. Safe to run on several replicas at once.
 */
@Service
public class ConversionDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConversionDispatcher.class);
    static final int MAX_ATTEMPTS = 6;
    static final int PER_STORE_PASS = 50;
    static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(20);

    private final StoreDirectory stores;
    private final TenantFeatures features;
    private final Outbox outbox;
    private final IntegrationRepository integrations;
    private final PaidOrders orders;
    private final SecretSealer secrets;
    private final StoreConfig store;
    private final MarketingProperties props;
    private final MeterRegistry meters;
    private final Map<Provider, MarketingDestination> destinations = new EnumMap<>(Provider.class);
    private final Map<Provider, ExecutorService> lanes = new EnumMap<>(Provider.class);
    private final Map<Provider, AtomicBoolean> busy = new EnumMap<>(Provider.class);
    private final Map<Provider, ProviderBreaker> breakers = new EnumMap<>(Provider.class);
    private final Map<Provider, AtomicLong> dueAge = new EnumMap<>(Provider.class);
    private volatile boolean running;

    ConversionDispatcher(StoreDirectory stores, TenantFeatures features, Outbox outbox,
                         IntegrationRepository integrations, PaidOrders orders, SecretSealer secrets,
                         List<MarketingDestination> destinations, StoreConfig store, MarketingProperties props,
                         MeterRegistry meters) {
        this.stores = stores;
        this.features = features;
        this.outbox = outbox;
        this.integrations = integrations;
        this.orders = orders;
        this.secrets = secrets;
        this.store = store;
        this.props = props;
        this.meters = meters;
        for (MarketingDestination d : destinations) {
            Provider p = d.provider();
            this.destinations.put(p, d);
            lanes.put(p, Executors.newSingleThreadExecutor(Thread.ofVirtual().name("marketing-" + p.key()).factory()));
            busy.put(p, new AtomicBoolean());
            breakers.put(p, new ProviderBreaker(props.breakerOpenFor(), Instant::now));
            AtomicLong age = new AtomicLong();
            dueAge.put(p, age);
            Gauge.builder("marketing.outbox.due.age", age, AtomicLong::get).baseUnit("seconds")
                    .description("How long the oldest due conversion waited before it was claimed")
                    .tag("provider", p.key()).register(meters);
        }
    }

    @Scheduled(initialDelayString = "${app.marketing.dispatch-interval:PT30S}",
            fixedDelayString = "${app.marketing.dispatch-interval:PT30S}")
    void scheduled() {
        for (Provider p : destinations.keySet()) {
            if (!running || !busy.get(p).compareAndSet(false, true)) {
                continue; // stopping, or the previous pass for p is still going
            }
            try {
                lanes.get(p).execute(() -> {
                    try {
                        dispatch(p);
                    } finally {
                        busy.get(p).set(false);
                    }
                });
            } catch (RejectedExecutionException e) {
                busy.get(p).set(false); // shutting down
            }
        }
    }

    /** One pass over every store for one provider. Public for tests; concurrent calls are safe (D3). */
    public void dispatch(Provider provider) {
        if (!running || !secrets.available()) {
            return;
        }
        long oldest = 0;
        for (TenantRef s : stores.stores()) {
            if (!running || breakers.get(provider).isOpen()) {
                break;
            }
            try {
                oldest = Math.max(oldest, TenantContext.callAs(s, () -> drainStore(provider)));
            } catch (RuntimeException e) {
                log.warn("Marketing dispatch failed for store {}", s.slug(), e);
            }
        }
        dueAge.get(provider).set(oldest);
    }

    /** @return seconds the oldest row claimed in this pass had been due */
    private long drainStore(Provider provider) {
        if (!features.enabled(Feature.MARKETING)) {
            return 0;
        }
        long oldest = 0;
        for (int n = 0; n < PER_STORE_PASS && running && !breakers.get(provider).isOpen(); n++) {
            Instant now = Instant.now();
            Optional<OutboxRow> row = outbox.claim(provider, now, LEASE);
            if (row.isEmpty()) {
                break;
            }
            oldest = Math.max(oldest, Duration.between(row.get().nextAttemptAt(), now).toSeconds());
            deliver(row.get());
        }
        return oldest;
    }

    private void deliver(OutboxRow row) {
        Provider p = row.provider();
        Optional<PaidOrder> order = orders.find(row.orderId());
        Integration integration = integrations.findAll().stream().filter(i -> i.provider() == p).findFirst()
                .orElse(null);
        DeliveryCode skip = skipReason(p, order, integration);
        if (skip != null) {
            finish(row, OutboxRow.Status.SKIPPED, skip, null);
            count(p, "skipped");
            return;
        }
        String secret;
        try {
            secret = secrets.open(integration.server().secret(),
                    SecretSealer.aad(TenantContext.require().id(), p, ServerSettings.SECRET_FIELD));
        } catch (SecretSealer.UnreadableSecretException e) {
            fail(row, new DeliveryException(DeliveryCode.SECRET_UNREADABLE, null, null));
            return;
        }
        try {
            destinations.get(p).send(new Credentials(integration.serverId(), secret), Conversions.of(order.get(), p,
                    store.currency(), props.piiConsentVersion(), props.callingCode()));
            finish(row, OutboxRow.Status.SENT, null, null);
            integrations.recordSuccess(p, Instant.now());
            breakers.get(p).success();
            count(p, "sent");
        } catch (DeliveryException e) {
            if (e.code().retryable()) {
                breakers.get(p).failure();
                retryOrGiveUp(row, e);
            } else {
                fail(row, e);
            }
        } catch (RuntimeException e) {
            // A bug of ours (payload mapping): retry rather than lose the row; never log the exception's text.
            log.warn("Unexpected {} sending {} conversion {}", e.getClass().getSimpleName(), p.key(), row.eventId());
            retryOrGiveUp(row, new DeliveryException(DeliveryCode.PROVIDER_DOWN, null, null));
        }
    }

    private static DeliveryCode skipReason(Provider p, Optional<PaidOrder> order, Integration integration) {
        if (order.isEmpty()) {
            return DeliveryCode.ORDER_GONE;
        }
        if (order.get().attribution() == null
                || !ConversionRecorder.allowed(p, order.get().attribution().consent())) {
            return DeliveryCode.NO_CONSENT; // also after account erasure removed the attribution
        }
        if (integration == null || !integration.server().enabled() || !integration.server().hasSecret()
                || integration.serverId() == null) {
            return DeliveryCode.SERVER_OFF;
        }
        Duration maxAge = p == Provider.META ? Duration.ofDays(7) : Duration.ofHours(72);
        return order.get().placedAt() == null || order.get().placedAt().isBefore(Instant.now().minus(maxAge))
                ? DeliveryCode.TOO_OLD : null;
    }

    private void retryOrGiveUp(OutboxRow row, DeliveryException e) {
        if (row.attempts() >= MAX_ATTEMPTS) {
            fail(row, new DeliveryException(DeliveryCode.GAVE_UP, null, null));
            return;
        }
        Duration wait = Backoff.after(row.attempts(), e.retryAfter(), ThreadLocalRandom.current().nextDouble());
        finish(row, OutboxRow.Status.PENDING, e.code(), Instant.now().plus(wait));
        count(row.provider(), "retry");
    }

    /** Dead letter: the row stays FAILED in the log and the card shows why (D9). */
    private void fail(OutboxRow row, DeliveryException e) {
        finish(row, OutboxRow.Status.FAILED, e.code(), null);
        integrations.recordFailure(row.provider(), new LastError(Instant.now(), e.code().name(),
                e.ownerMessage(row.provider())));
        count(row.provider(), "failed");
    }

    private void finish(OutboxRow row, OutboxRow.Status status, DeliveryCode error, Instant next) {
        if (!outbox.finish(row, status, error, next, Instant.now())) {
            log.warn("Lost the claim on {} conversion {}: its lease ran out and another dispatcher took it",
                    row.provider().key(), row.eventId());
        }
    }

    private void count(Provider p, String outcome) {
        meters.counter("marketing.events", Tags.of("provider", p.key(), "outcome", outcome)
                .and(TenantContext.metricTag())).increment();
    }

    @Override
    public void start() {
        running = true;
    }

    /** Stops claiming, then waits up to 20 s for the sends in flight; a row left SENDING is recovered by its lease. */
    @Override
    public void stop() {
        running = false;
        lanes.values().forEach(ExecutorService::shutdown);
        long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
        for (ExecutorService lane : lanes.values()) {
            try {
                if (!lane.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    log.warn("A marketing send was still running at shutdown; its lease expires within 2 minutes");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
