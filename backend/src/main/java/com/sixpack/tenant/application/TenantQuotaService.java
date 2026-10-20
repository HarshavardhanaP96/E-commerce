package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantLimits;
import com.sixpack.shared.tenant.TenantQuotas;
import org.springframework.stereotype.Component;

/** Store limit first, then the platform default. Reads the tenant through the registry's cache. */
@Component
class TenantQuotaService implements TenantQuotas {

    private final TenantRegistry registry;
    private final LimitProperties defaults;

    TenantQuotaService(TenantRegistry registry, LimitProperties defaults) {
        this.registry = registry;
        this.defaults = defaults;
    }

    private TenantLimits own() {
        return registry.byId(TenantContext.require().id()).limits();
    }

    @Override
    public int maxProducts() {
        Integer own = own().products();
        return own != null ? own : defaults.products();
    }

    @Override
    public long maxUploadBytes() {
        Long own = own().uploadBytes();
        return own != null ? own : defaults.uploads().toBytes();
    }

    @Override
    public int requestsPerMinute() {
        Integer own = own().requestsPerMinute();
        return own != null ? own : defaults.requestsPerMinute();
    }
}
