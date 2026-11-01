package com.sixpack.marketing.application;

import java.time.Duration;
import java.util.List;

/** When to try a transient failure again (D6): fixed steps × jitter U(0.8, 1.2), Retry-After honoured, 12 h cap. */
final class Backoff {

    static final List<Duration> STEPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12));
    private static final Duration CAP = Duration.ofHours(12);

    private Backoff() {
    }

    /** @param attempts attempts made so far (≥ 1) @param random uniform in [0, 1) */
    static Duration after(int attempts, Duration retryAfter, double random) {
        Duration step = STEPS.get(Math.min(Math.max(attempts, 1), STEPS.size()) - 1);
        long wanted = Math.round(step.toMillis() * (0.8 + 0.4 * random));
        if (retryAfter != null) {
            wanted = Math.max(wanted, retryAfter.toMillis());
        }
        return Duration.ofMillis(Math.min(wanted, CAP.toMillis()));
    }
}
