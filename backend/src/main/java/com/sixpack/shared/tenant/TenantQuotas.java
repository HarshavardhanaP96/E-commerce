package com.sixpack.shared.tenant;

/** The limits in force for the tenant in {@link TenantContext}: its own, else the platform default. */
public interface TenantQuotas {

    int maxProducts();

    long maxUploadBytes();

    int requestsPerMinute();
}
