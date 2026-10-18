package com.sixpack.it;

import com.sixpack.tenant.application.TenantRegistry;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base for tests that need more than one tenant. Adds helpers in a subclass on purpose: the shared
 * {@link IntegrationTest} base is edited by several workstreams. The {@code default} tenant is created by the
 * application at startup and is never removed here.
 */
abstract class TenantIntegrationTest extends IntegrationTest {

    @Autowired
    protected TenantRepository tenantRepository;

    @Autowired
    protected TenantRegistry tenantRegistry;

    protected Tenant createTenant(String slug, String name, String domain) {
        Tenant tenant = tenantRepository.save(new Tenant(null, slug, name, TenantStatus.ACTIVE, List.of(domain),
                Map.of("--accent", "#123456"), "en-IN", "INR", null));
        tenantRegistry.refresh();
        return tenant;
    }

    protected Tenant suspend(Tenant tenant) {
        Tenant suspended = tenantRepository.save(new Tenant(tenant.id(), tenant.slug(), tenant.name(),
                TenantStatus.SUSPENDED, tenant.domains(), tenant.theme(), tenant.locale(), tenant.currency(),
                tenant.createdAt()));
        tenantRegistry.refresh();
        return suspended;
    }

    /** Makes the request look like it arrived on {@code host} (the port is ignored, as in production). */
    protected static RequestPostProcessor host(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }

    @AfterEach
    void removeExtraTenants() {
        mongo.getCollection("tenants").deleteMany(new Document("slug", new Document("$ne", "default")));
        tenantRegistry.refresh();
    }
}
