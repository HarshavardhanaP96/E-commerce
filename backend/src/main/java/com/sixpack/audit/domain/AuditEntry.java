package com.sixpack.audit.domain;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent.Outcome;
import com.sixpack.audit.FieldChange;
import java.time.Instant;
import java.util.List;

/**
 * One immutable audit record.
 *
 * @param tenantId    the store the entry belongs to (stamped from the tenant context)
 * @param actorRole   ADMIN, USER, ANONYMOUS (an unauthenticated request) or SYSTEM (no request at all)
 * @param subjectHash salted hash of an attempted login email; never the email itself
 */
public record AuditEntry(String id, Instant at, String tenantId, String actorId, String actorRole,
                         AuditAction action, String entityType, String entityId, Outcome outcome,
                         List<FieldChange> changes, String subjectHash, String requestId, String ipHash,
                         String userAgent) {
}
