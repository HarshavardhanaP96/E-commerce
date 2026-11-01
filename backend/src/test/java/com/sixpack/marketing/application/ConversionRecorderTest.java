package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sixpack.marketing.domain.IntegrationRepository;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.PaidOrders;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ConversionRecorderTest {

    @Test
    void aFailingRecorderNeverFailsCheckout() {
        TenantFeatures features = mock(TenantFeatures.class);
        when(features.enabled(Feature.MARKETING)).thenReturn(true);
        SecretSealer secrets = mock(SecretSealer.class);
        when(secrets.available()).thenReturn(true);
        PaidOrders orders = mock(PaidOrders.class);
        when(orders.find(anyString())).thenThrow(new IllegalStateException("database down"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ConversionRecorder recorder = new ConversionRecorder(features, orders, mock(IntegrationRepository.class),
                mock(Outbox.class), secrets, meters);

        assertThatCode(() -> recorder.onOrderChanged(new OrderChangedEvent("o1"))).doesNotThrowAnyException();
        assertThat(meters.counter("marketing.record.failed", "tenant", "none").count()).isEqualTo(1);
    }
}
