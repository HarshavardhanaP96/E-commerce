package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void followsTheStepsWithTwentyPercentJitter() {
        assertThat(Backoff.after(1, null, 0.0)).isEqualTo(Duration.ofSeconds(48));
        assertThat(Backoff.after(1, null, 0.999)).isBetween(Duration.ofSeconds(71), Duration.ofSeconds(72));
        assertThat(Backoff.after(2, null, 0.5)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Backoff.after(3, null, 0.5)).isEqualTo(Duration.ofMinutes(30));
        assertThat(Backoff.after(4, null, 0.5)).isEqualTo(Duration.ofHours(2));
        assertThat(Backoff.after(5, null, 0.0)).isEqualTo(Duration.ofMinutes(576)); // 0.8 × 12 h
    }

    @Test
    void neverWaitsMoreThanTwelveHoursAndHonoursRetryAfter() {
        assertThat(Backoff.after(5, null, 0.999)).isEqualTo(Duration.ofHours(12));
        assertThat(Backoff.after(9, null, 0.5)).isEqualTo(Duration.ofHours(12));
        assertThat(Backoff.after(1, Duration.ofMinutes(10), 0.5)).isEqualTo(Duration.ofMinutes(10));
        assertThat(Backoff.after(1, Duration.ofDays(2), 0.5)).isEqualTo(Duration.ofHours(12));
        assertThat(Backoff.after(3, Duration.ofSeconds(5), 0.5)).isEqualTo(Duration.ofMinutes(30));
    }
}
