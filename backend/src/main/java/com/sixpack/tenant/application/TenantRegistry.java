package com.sixpack.tenant.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps a request host to its tenant. Results (including misses) are cached for a minute so junk hosts cannot
 * hammer the database; {@link #refresh()} clears the cache after tenants change.
 *
 * <p>While exactly one tenant exists, every host resolves to it, so a single-store deployment needs no
 * domain configuration. A second tenant switches the registry to strict, exact-host matching.
 */
@Component
public class TenantRegistry {

    private final TenantRepository tenants;
    private final Cache<String, Optional<Tenant>> byHost = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1_000).build();
    private final Cache<String, Tenant> byId = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1_000).build();

    TenantRegistry(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public Optional<Tenant> resolve(String host) {
        return byHost.get(normalise(host), this::lookup);
    }

    public Tenant byId(String id) {
        Tenant cached = byId.get(id, key -> tenants.findById(key).orElse(null));
        if (cached == null) {
            throw new IllegalStateException("Unknown tenant " + id);
        }
        return cached;
    }

    public void refresh() {
        byHost.invalidateAll();
        byId.invalidateAll();
    }

    private Optional<Tenant> lookup(String host) {
        Optional<Tenant> exact = tenants.findByDomain(host);
        if (exact.isPresent()) {
            return exact;
        }
        List<Tenant> all = tenants.findAll();
        return all.size() == 1 ? Optional.of(all.get(0)) : Optional.empty();
    }

    static String normalise(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) {
            h = h.substring(0, colon);
        }
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }
}
