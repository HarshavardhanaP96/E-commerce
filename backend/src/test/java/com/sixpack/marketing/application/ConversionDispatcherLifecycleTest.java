package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.ServerSettings;
import com.sixpack.order.OrderAttribution;
import com.sixpack.order.PaidOrders;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.tenant.TenantRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Graceful shutdown (D10): the send in flight finishes, nothing more is claimed. */
class ConversionDispatcherLifecycleTest {

    @Test
    void stopFinishesTheSendInFlightAndClaimsNothingMore() throws Exception {
        Instant now = Instant.now();
        TenantFeatures features = mock(TenantFeatures.class);
        when(features.enabled(Feature.MARKETING)).thenReturn(true);
        Outbox outbox = mock(Outbox.class);
        when(outbox.claim(eq(Provider.META), any(), any())).thenReturn(Optional.of(new OutboxRow("r1", Provider.META,
                "o1", "purchase", "o1", "ORD-1", OutboxRow.Status.SENDING, 1, now, "c1", null, now, now)));
        when(outbox.finish(any(), any(), any(), any(), any())).thenReturn(true);
        PaidOrders orders = mock(PaidOrders.class);
        when(orders.find("o1")).thenReturn(Optional.of(new PaidOrders.PaidOrder("o1", "ORD-1", "u1", now, 790, 90,
                200, "INR", null, List.of(), null, null, new OrderAttribution(null, null, null, null, null, null, null,
                null, null, null, null, "UA", null, new OrderAttribution.Consent(true, true, "2026-10-31", now)))));
        IntegrationRepository integrations = mock(IntegrationRepository.class);
        when(integrations.findAll()).thenReturn(List.of(new Integration(Provider.META,
                new BrowserTags(null, null, null, null, "1234567890"),
                new ServerSettings(true, null, new SealedSecret("k1", "aXY=", "Yw==")), null, null, now)));
        SecretSealer secrets = mock(SecretSealer.class);
        when(secrets.available()).thenReturn(true);
        when(secrets.open(any(), anyString())).thenReturn("fake-meta-token-aaaaaaaa");
        StoreConfig store = mock(StoreConfig.class);
        when(store.currency()).thenReturn("INR");

        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MarketingDestination meta = mock(MarketingDestination.class);
        when(meta.provider()).thenReturn(Provider.META);
        doAnswer(inv -> {
            sending.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(meta).send(any(), any());

        ConversionDispatcher dispatcher = new ConversionDispatcher(() -> List.of(new TenantRef("t1", "acme")),
                features, outbox, integrations, orders, secrets, List.of(meta), store,
                new MarketingProperties("", "2026-10-31", "91", Duration.ofSeconds(60),
                        new MarketingProperties.Meta("https://graph.test", "v25.0"),
                        new MarketingProperties.Google("https://ga.test"),
                        new MarketingProperties.Http(Duration.ofSeconds(1), Duration.ofSeconds(1))),
                new SimpleMeterRegistry());
        dispatcher.start();
        dispatcher.scheduled();
        assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();

        Thread stopper = Thread.ofVirtual().start(dispatcher::stop);
        Thread.sleep(200);
        assertThat(stopper.isAlive()).isTrue(); // waiting for the send in flight
        release.countDown();
        stopper.join(5000);

        assertThat(stopper.isAlive()).isFalse();
        assertThat(dispatcher.isRunning()).isFalse();
        verify(meta, times(1)).send(any(), any());
        verify(outbox, times(1)).claim(any(), any(), any());
    }
}
