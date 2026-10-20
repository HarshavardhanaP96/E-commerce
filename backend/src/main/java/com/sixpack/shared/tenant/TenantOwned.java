package com.sixpack.shared.tenant;

/** A stored document that belongs to exactly one tenant. {@link TenantMongo} stamps and checks the id. */
public interface TenantOwned {
    String getTenantId();

    void setTenantId(String tenantId);
}
