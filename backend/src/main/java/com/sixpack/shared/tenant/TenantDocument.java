package com.sixpack.shared.tenant;

import org.springframework.data.mongodb.core.index.Indexed;

/** Base for stored documents owned by one tenant. {@link TenantMongo} stamps and checks {@code tenantId}. */
public abstract class TenantDocument implements TenantOwned {

    @Indexed
    private String tenantId;

    @Override
    public String getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
