package com.sixpack.tenant.domain;

/**
 * Where a store is in its life. ACTIVE and TRIAL stores serve requests; every other status closes the storefront.
 * TRIAL ends at {@code trialEndsAt} (the store is then suspended). SUSPENDED is the platform closing a store;
 * CANCELLED is the store owner leaving. SCHEDULED_FOR_DELETION is the offboarding grace period (the store can still
 * be restored); DELETED means its data has been purged and only the tenant record and its audit log remain.
 */
public enum TenantStatus {
    TRIAL, ACTIVE, SUSPENDED, CANCELLED, SCHEDULED_FOR_DELETION, DELETED;

    public boolean serves() {
        return this == ACTIVE || this == TRIAL;
    }
}
