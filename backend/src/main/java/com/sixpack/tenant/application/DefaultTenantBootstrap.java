package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Makes sure the tenant that owns all pre-tenancy data exists. Never edits or deletes an existing tenant. */
@Component
@Order(1)
public class DefaultTenantBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantBootstrap.class);

    private final TenantRepository tenants;
    private final TenantProperties props;
    private final TenantRegistry registry;

    DefaultTenantBootstrap(TenantRepository tenants, TenantProperties props, TenantRegistry registry) {
        this.tenants = tenants;
        this.props = props;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureDefault();
    }

    public Tenant ensureDefault() {
        return tenants.findBySlug(props.defaultSlug()).orElseGet(() -> {
            Tenant created = tenants.save(new Tenant(null, props.defaultSlug(), props.defaultName(),
                    TenantStatus.ACTIVE, props.defaultDomains(), Map.of(), "en-IN", "INR", null));
            registry.refresh();
            log.info("Created default tenant '{}' for hosts {}", created.slug(), created.domains());
            return created;
        });
    }
}
