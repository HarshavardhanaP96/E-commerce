package com.sixpack.marketing.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed one-minute window per key.
 * shortcut: in memory per instance, so N replicas allow N × the limit; move to a shared store if that matters.
 */
final class FixedWindowLimiter {

    private record Window(long minute, AtomicInteger count) {
    }

    private final int perMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    FixedWindowLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    boolean tryAcquire(String key, long nowMillis) {
        long minute = nowMillis / 60_000;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != minute
                ? new Window(minute, new AtomicInteger()) : old);
        if (windows.size() > 10_000) {
            windows.values().removeIf(x -> x.minute() != minute);
        }
        return w.count().incrementAndGet() <= perMinute;
    }
}
