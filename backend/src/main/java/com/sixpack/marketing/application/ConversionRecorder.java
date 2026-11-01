package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.order.OrderAttribution;
import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.PaidOrders;
import com.sixpack.order.PaidOrders.PaidOrder;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantFeatures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Queues each paid order for the providers the shopper allowed and the store set up (spec §6 server purchase).
 * Runs inside checkout's thread, so it makes no network call and never throws; {@link ConversionSweep} catches what
 * a crash between saving the order and queueing it would lose.
 */
@Service
public class ConversionRecorder {

    private static final Logger log = LoggerFactory.getLogger(ConversionRecorder.class);

    private final TenantFeatures features;
    private final PaidOrders orders;
    private final IntegrationRepository integrations;
    private final Outbox outbox;
    private final SecretSealer secrets;
    private final MeterRegistry meters;

    ConversionRecorder(TenantFeatures features, PaidOrders orders, IntegrationRepository integrations, Outbox outbox,
                       SecretSealer secrets, MeterRegistry meters) {
        this.features = features;
        this.orders = orders;
        this.integrations = integrations;
        this.outbox = outbox;
        this.secrets = secrets;
        this.meters = meters;
    }

    /** Idempotent: the outbox keeps one row per provider and order. @return rows added */
    public int record(String orderId) {
        if (!secrets.available() || !features.enabled(Feature.MARKETING)) {
            return 0;
        }
        PaidOrder order = orders.find(orderId).orElse(null);
        if (order == null || order.attribution() == null) {
            return 0;
        }
        int added = 0;
        for (Integration i : integrations.findAll()) {
            if (i.server().enabled() && i.server().hasSecret() && allowed(i.provider(), order.attribution().consent())
                    && outbox.addIfAbsent(i.provider(), order.id(), order.id(), order.orderNumber(), Instant.now())) {
                added++;
            }
        }
        return added;
    }

    @EventListener
    void onOrderChanged(OrderChangedEvent event) {
        try {
            record(event.orderId());
        } catch (RuntimeException e) {
            log.warn("Could not queue marketing conversions for order {}; the sweep retries", event.orderId(), e);
            meters.counter("marketing.record.failed", Tags.of(TenantContext.metricTag())).increment();
        }
    }

    /** Meta follows the marketing choice, GA4 the analytics choice (as the browser tags do). */
    static boolean allowed(Provider provider, OrderAttribution.Consent consent) {
        return consent != null && (provider == Provider.META ? consent.marketing() : consent.analytics());
    }
}
