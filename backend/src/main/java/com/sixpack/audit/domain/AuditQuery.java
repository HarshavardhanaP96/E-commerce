package com.sixpack.audit.domain;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent.Outcome;
import java.time.Instant;

/** Filters for the admin audit search. Null means "don't filter on this". */
public record AuditQuery(String entityType, String entityId, String actorId, AuditAction action, Outcome outcome,
                         Instant from, Instant to, int page, int size) {
}
