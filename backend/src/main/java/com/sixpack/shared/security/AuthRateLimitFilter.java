package com.sixpack.shared.security;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window, per-IP limiter for credential endpoints. In-memory per pod: good enough to stop brute
 * force from one client; move to a shared store (Redis) or the ingress if stricter limits are needed.
 */
class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of(
            "/api/v1/user/login", "/api/v1/user/register",
            "/api/v1/user/forgot-password", "/api/v1/user/reset-password", "/api/v1/user/verify-email",
            "/api/v1/user/me/verify-email/resend", "/api/v1/platform/login");

    private final int limitPerMinute;
    private final JsonSecurityHandlers handlers;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    AuthRateLimitFilter(int limitPerMinute, JsonSecurityHandlers handlers) {
        this.limitPerMinute = limitPerMinute;
        this.handlers = handlers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000;
        String tenant = TenantContext.current().map(TenantRef::id).orElse("-");
        String key = tenant + "|" + request.getRemoteAddr() + "|" + request.getRequestURI();
        Window window = windows.compute(key, (k, w) -> w == null || w.minute != minute ? new Window(minute) : w);
        if (window.count.incrementAndGet() > limitPerMinute) {
            response.setHeader("Retry-After", "60");
            handlers.write(response, 429, "Too many requests, please try again later");
            return;
        }
        if (windows.size() > 10_000) {
            windows.values().removeIf(w -> w.minute != minute);
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
