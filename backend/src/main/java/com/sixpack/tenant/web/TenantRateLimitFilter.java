package com.sixpack.tenant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantQuotas;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.shared.web.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed one-minute window of requests per store, so one busy or abusive store cannot slow the others. Runs right
 * after {@link TenantResolutionFilter}; tenant-less paths (platform, actuator) are never limited. In memory per
 * backend instance, like the login limiter: with N replicas a store gets up to N times its limit.
 */
class TenantRateLimitFilter extends OncePerRequestFilter {

    private final TenantQuotas quotas;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    TenantRateLimitFilter(TenantQuotas quotas, ObjectMapper mapper, MeterRegistry meters) {
        this.quotas = quotas;
        this.mapper = mapper;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantRef tenant = TenantContext.current().orElse(null);
        if (tenant == null) {
            chain.doFilter(request, response);
            return;
        }
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(tenant.id(), (k, w) -> w == null || w.minute != minute ? new Window(minute) : w);
        if (window.count.incrementAndGet() > quotas.requestsPerMinute()) {
            meters.counter("sixpack.ratelimit.rejected", Tags.of(TenantContext.metricTag())).increment();
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(60 - (System.currentTimeMillis() / 1000) % 60));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),
                    ApiResponse.error("This store is receiving too many requests, please try again shortly"));
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
