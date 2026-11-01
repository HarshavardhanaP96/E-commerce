package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderBreakerTest {

    private Instant time = Instant.parse("2026-10-31T10:00:00Z");
    private final ProviderBreaker breaker = new ProviderBreaker(Duration.ofSeconds(60), () -> time);

    @Test
    void opensAfterFiveFailuresInARowAndAFailedProbeReopensIt() {
        for (int i = 0; i < 4; i++) {
            breaker.failure();
        }
        assertThat(breaker.isOpen()).isFalse();
        breaker.failure();
        assertThat(breaker.isOpen()).isTrue();

        time = time.plusSeconds(61);
        assertThat(breaker.isOpen()).isFalse();
        breaker.failure(); // the first call after the pause failed too
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void aSuccessResetsTheCount() {
        for (int i = 0; i < 4; i++) {
            breaker.failure();
        }
        breaker.success();
        for (int i = 0; i < 4; i++) {
            breaker.failure();
        }
        assertThat(breaker.isOpen()).isFalse();
    }
}
