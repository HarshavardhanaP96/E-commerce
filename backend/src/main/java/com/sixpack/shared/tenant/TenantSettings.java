package com.sixpack.shared.tenant;

/**
 * Per-tenant configuration for the tenant in {@link TenantContext}. Each accessor returns what the tenant has
 * set (fields may be null); callers fall back to the platform defaults for anything unset, so the default tenant
 * keeps working with today's environment variables.
 */
public interface TenantSettings {

    /** The tenant's slug; the default tenant is the only one allowed to fall back to platform payment keys. */
    String slug();

    boolean isDefaultTenant();

    LegalSettings legal();

    PaymentSettings payment();

    MailSettings mail();

    /** The storefront base URL derived from the tenant's first domain, or null if it has none. */
    String primaryDomain();
}
