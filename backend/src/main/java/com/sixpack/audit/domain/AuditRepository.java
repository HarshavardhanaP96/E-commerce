package com.sixpack.audit.domain;

import com.sixpack.shared.PageResult;

/** Persistence port for the audit log. There is deliberately no update or delete. */
public interface AuditRepository {

    AuditEntry append(AuditEntry entry);

    /** @return the matching entries, newest first, one page at a time (1-based) */
    PageResult<AuditEntry> search(AuditQuery query);

    /**
     * The same search over every store's log, for platform administrators only.
     *
     * @param tenantId limits the search to one store; null searches them all
     */
    PageResult<AuditEntry> searchAllTenants(AuditQuery query, String tenantId);
}
