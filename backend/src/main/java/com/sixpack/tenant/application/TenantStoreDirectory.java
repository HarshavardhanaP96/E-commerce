package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.StoreDirectory;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class TenantStoreDirectory implements StoreDirectory {

    private final TenantRepository tenants;

    TenantStoreDirectory(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    public List<TenantRef> stores() {
        return tenants.findAll().stream().filter(t -> t.status() != TenantStatus.DELETED)
                .map(t -> new TenantRef(t.id(), t.slug())).toList();
    }
}
