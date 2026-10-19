package com.sixpack.shared.tenant;

/** The tenant that owns data from before multi-tenancy. Startup jobs that need a tenant run as this one. */
public interface DefaultTenant {
    TenantRef get();
}
