package com.sixpack.marketing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Per provider, per instance (D8): five transient failures in a row open it for {@code openFor}; after the pause the
 * first failure opens it again at once, a success closes it. While open the dispatcher claims nothing for the provider.
 */
final class ProviderBreaker {

    static final int THRESHOLD = 5;

    private final Duration openFor;
    private final Supplier<Instant> now;
    private int failures;
    private Instant openUntil = Instant.MIN;

    ProviderBreaker(Duration openFor, Supplier<Instant> now) {
        this.openFor = openFor;
        this.now = now;
    }

    synchronized boolean isOpen() {
        return now.get().isBefore(openUntil);
    }

    synchronized void success() {
        failures = 0;
    }

    synchronized void failure() {
        if (++failures >= THRESHOLD) {
            openUntil = now.get().plus(openFor);
            failures = THRESHOLD - 1;
        }
    }
}
