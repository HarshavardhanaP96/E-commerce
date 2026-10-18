package com.sixpack.audit.domain;

import com.sixpack.shared.PageResult;

/** Persistence port for the audit log. There is deliberately no update or delete. */
public interface AuditRepository {

    AuditEntry append(AuditEntry entry);

    /** @return the matching entries, newest first, one page at a time (1-based) */
    PageResult<AuditEntry> search(AuditQuery query);
}
