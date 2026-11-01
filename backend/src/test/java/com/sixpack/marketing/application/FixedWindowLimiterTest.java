package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FixedWindowLimiterTest {

    @Test
    void allowsTheLimitPerKeyPerMinute() {
        FixedWindowLimiter limiter = new FixedWindowLimiter(10);
        long t = 1_793_440_800_000L; // a minute boundary
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("t_a", t + i)).isTrue();
        }
        assertThat(limiter.tryAcquire("t_a", t + 59_999)).isFalse();
        assertThat(limiter.tryAcquire("t_b", t)).isTrue();
        assertThat(limiter.tryAcquire("t_a", t + 60_000)).isTrue();
    }
}
