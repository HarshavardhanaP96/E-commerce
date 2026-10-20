package com.sixpack.shared.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The tenant the current thread is working for. Set by the request filter (or by {@link #runAs} for jobs and
 * tests) and always cleared afterwards. There is no ambient default: code that needs a tenant calls
 * {@link #require()} and fails if there is none.
 */
public final class TenantContext {

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

    /** Binds {@code ref} to this thread until the returned scope is closed, then restores the previous value. */
    public static Scope open(TenantRef ref) {
        TenantRef previous = CURRENT.get();
        CURRENT.set(ref);
        return new Scope(previous);
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
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
