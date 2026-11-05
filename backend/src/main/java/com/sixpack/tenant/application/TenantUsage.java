package com.sixpack.tenant.application;

import com.sixpack.product.CatalogReads;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.storage.FileStorage;
import com.sixpack.tenant.domain.Tenant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** What one store uses of its limits, counted inside that store's context (like an export). */
@Component
public class TenantUsage {

    private static final Logger log = LoggerFactory.getLogger(TenantUsage.class);

    public record Usage(long products, long uploadBytes) {
    }

    private final CatalogReads catalog;
    private final FileStorage storage;

    TenantUsage(CatalogReads catalog, FileStorage storage) {
        this.catalog = catalog;
        this.storage = storage;
    }

    /** Empty when counting fails: the store details still load, without usage. */
    public Optional<Usage> of(Tenant tenant) {
        try {
            return Optional.of(TenantContext.callAs(new TenantRef(tenant.id(), tenant.slug()),
                    () -> new Usage(catalog.count(), storage.usedBytes())));
        } catch (RuntimeException e) {
            log.warn("Could not count usage of tenant '{}'", tenant.slug(), e);
            return Optional.empty();
        }
    }
}
