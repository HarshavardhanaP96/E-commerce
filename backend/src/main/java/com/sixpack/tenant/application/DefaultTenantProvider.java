package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.DefaultTenant;
import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.tenant.domain.TenantRepository;
import org.springframework.stereotype.Component;

@Component
class DefaultTenantProvider implements DefaultTenant {

    private final TenantRepository tenants;
    private final TenantProperties props;

    DefaultTenantProvider(TenantRepository tenants, TenantProperties props) {
        this.tenants = tenants;
        this.props = props;
    }

    @Override
    public TenantRef get() {
        return tenants.findBySlug(props.defaultSlug()).map(t -> new TenantRef(t.id(), t.slug()))
                .orElseThrow(() -> new IllegalStateException("Default tenant is missing"));
    }
}
