package com.sixpack.tenant.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A store. {@code theme} maps CSS design-token names to values for the storefront (ADR-013).
 */
public record Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                     Map<String, String> theme, String locale, String currency, Instant createdAt) {

    public Tenant {
        status = status == null ? TenantStatus.ACTIVE : status;
        domains = domains == null ? List.of() : List.copyOf(domains);
        theme = theme == null ? Map.of() : Map.copyOf(theme);
    }
}
