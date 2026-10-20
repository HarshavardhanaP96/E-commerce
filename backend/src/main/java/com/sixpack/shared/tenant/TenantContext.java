package com.sixpack.shared.tenant;

import io.micrometer.core.instrument.Tag;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * The tenant the current thread is working for. Set by the request filter (or by {@link #runAs} for jobs and
 * tests) and always cleared afterwards. There is no ambient default: code that needs a tenant calls
 * {@link #require()} and fails if there is none.
 *
 * <p>The bound tenant's slug is mirrored into the logging context under {@value #MDC_KEY}, so every log line
 * written while it is bound names the store.
 */
public final class TenantContext {

    public static final String MDC_KEY = "tenant";
    /** Metric tag value when no tenant is bound (platform, operational and background paths). */
    public static final String NONE = "none";

    private static final ThreadLocal<TenantRef> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<TenantRef> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantRef require() {
        TenantRef ref = CURRENT.get();
        if (ref == null) {
            throw new IllegalStateException("No tenant in context");
        }
        return ref;
    }

    /** A {@code tenant} tag for business metrics, so they can be broken down per store. */
    public static Tag metricTag() {
        return Tag.of(MDC_KEY, current().map(TenantRef::slug).orElse(NONE));
    }

    /** Binds {@code ref} to this thread until the returned scope is closed, then restores the previous value. */
    public static Scope open(TenantRef ref) {
        TenantRef previous = CURRENT.get();
        bind(ref);
        return new Scope(previous);
    }

    private static void bind(TenantRef ref) {
        if (ref == null) {
            CURRENT.remove();
            MDC.remove(MDC_KEY);
        } else {
            CURRENT.set(ref);
            MDC.put(MDC_KEY, ref.slug());
        }
    }

    public static <T> T callAs(TenantRef ref, Supplier<T> action) {
        try (Scope ignored = open(ref)) {
            return action.get();
        }
    }

    public static void runAs(TenantRef ref, Runnable action) {
        try (Scope ignored = open(ref)) {
            action.run();
        }
    }

    public static final class Scope implements AutoCloseable {
        private final TenantRef previous;

        private Scope(TenantRef previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            bind(previous);
        }
    }
}
