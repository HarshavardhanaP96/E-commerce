package com.sixpack.tenant.domain;

/**
 * ACTIVE stores serve requests; every other status closes the storefront. SCHEDULED_FOR_DELETION is the
 * offboarding grace period (the store can still be restored); DELETED means its data has been purged and only the
 * tenant record and its audit log remain.
 */
public enum TenantStatus {
    ACTIVE, SUSPENDED, SCHEDULED_FOR_DELETION, DELETED
}
