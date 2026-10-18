package com.sixpack.tenant.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for tenants. Tenants themselves are platform-global data. */
public interface TenantRepository {
    Optional<Tenant> findBySlug(String slug);

    Optional<Tenant> findById(String id);

    Optional<Tenant> findByDomain(String domain);

    List<Tenant> findAll();

    Tenant save(Tenant tenant);
}
