package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.LegalSettings;
import com.sixpack.shared.tenant.MailSettings;
import com.sixpack.shared.tenant.PaymentSettings;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.shared.tenant.TenantSettings;
import com.sixpack.tenant.domain.Tenant;
import org.springframework.stereotype.Component;

/** Reads the settings of the tenant in {@link TenantContext}; the registry caches the tenant for a minute. */
@Component
class TenantSettingsService implements TenantSettings {

    private final TenantRegistry registry;
    private final TenantProperties props;

    TenantSettingsService(TenantRegistry registry, TenantProperties props) {
        this.registry = registry;
        this.props = props;
    }

    private Tenant tenant() {
        return registry.byId(TenantContext.require().id());
    }

    @Override
    public String slug() {
        return tenant().slug();
    }

    @Override
    public boolean isDefaultTenant() {
        return tenant().slug().equals(props.defaultSlug());
    }

    @Override
    public LegalSettings legal() {
        return tenant().legal();
    }

    @Override
    public PaymentSettings payment() {
        return tenant().payment();
    }

    @Override
    public MailSettings mail() {
        return tenant().mail();
    }

    @Override
    public String primaryDomain() {
        Tenant t = tenant();
        return t.domains().isEmpty() ? null : t.domains().get(0);
    }
}
