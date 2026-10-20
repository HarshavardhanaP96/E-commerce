package com.sixpack.shared.tenant;

/**
 * Declares a collection whose documents predate multi-tenancy. At startup every document in it without a
 * {@code tenantId} is assigned to the default tenant. Register one bean per tenant-owned collection: offboarding
 * exports and purges exactly these collections.
 */
public interface BackfillTarget {
    String collection();
}
