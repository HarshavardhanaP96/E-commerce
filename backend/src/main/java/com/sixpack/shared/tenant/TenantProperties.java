package com.sixpack.shared.tenant;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultSlug    slug of the tenant that owns all data from before multi-tenancy
 * @param defaultName    display name used when that tenant is first created
 * @param defaultDomains hosts mapped to it when it is first created
 */
@ConfigurationProperties("app.tenant")
public record TenantProperties(String defaultSlug, String defaultName, List<String> defaultDomains) {

    public TenantProperties {
        defaultSlug = defaultSlug == null || defaultSlug.isBlank() ? "default" : defaultSlug.trim();
        defaultName = defaultName == null || defaultName.isBlank() ? "6Pack" : defaultName.trim();
        List<String> cleaned = defaultDomains == null ? List.of() : defaultDomains.stream()
                .map(d -> d == null ? "" : d.trim().toLowerCase()).filter(d -> !d.isEmpty()).toList();
        defaultDomains = cleaned.isEmpty() ? List.of("localhost", "127.0.0.1") : cleaned;
    }
}
