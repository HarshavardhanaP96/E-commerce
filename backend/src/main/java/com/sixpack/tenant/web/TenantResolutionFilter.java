package com.sixpack.tenant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.tenant.application.TenantRegistry;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs ahead of Spring Security. Resolves the request host to a tenant and binds it to the thread for the
 * rest of the request. The host is read after the forwarded-header filter has applied {@code X-Forwarded-Host}.
 */
class TenantResolutionFilter extends OncePerRequestFilter {

    private static final List<String> EXEMPT_PREFIXES =
            List.of("/actuator", "/api-docs", "/swagger-ui", "/error", "/platform");

    private final TenantRegistry registry;
    private final ObjectMapper mapper;

    TenantResolutionFilter(TenantRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/".equals(path) || EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Tenant> tenant = registry.resolve(request.getServerName());
        if (tenant.isEmpty()) {
            reject(response, HttpServletResponse.SC_NOT_FOUND, "Store not found");
            return;
        }
        if (tenant.get().status() == TenantStatus.SUSPENDED) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "This store is currently unavailable");
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.open(new TenantRef(tenant.get().id(), tenant.get().slug()))) {
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }
}
