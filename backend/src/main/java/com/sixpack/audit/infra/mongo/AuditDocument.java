package com.sixpack.audit.infra.mongo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Append-only audit log; see docs/superpowers/specs/2026-10-18-admin-agreement-and-audit-design.md. */
@Document("audit_log")
@CompoundIndexes({
        @CompoundIndex(name = "entity_at", def = "{'entityType': 1, 'entityId': 1, 'at': -1}"),
        @CompoundIndex(name = "actor_at", def = "{'actorId': 1, 'at': -1}"),
        @CompoundIndex(name = "action_at", def = "{'action': 1, 'at': -1}")
})
class AuditDocument {

    static class ChangeDocument {
        String field;
        Object before;
        Object after;
        boolean redacted;
    }

    @Id
    String id;
    /**
     * TTL index: entries are deleted this long after they were written. A constant, because Spring Data does not
     * resolve ${...} placeholders in index annotations here; changing it on an existing database needs the old
     * {@code at} index dropped first.
     */
    @Indexed(expireAfter = "365d")
    Instant at;
    String tenantId;
    String actorId;
    String actorRole;
    String action;
    String entityType;
    String entityId;
    String outcome;
    List<ChangeDocument> changes;
    String subjectHash;
    String requestId;
    String ipHash;
    String userAgent;
}
