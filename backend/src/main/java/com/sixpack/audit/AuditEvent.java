package com.sixpack.audit;

import java.util.List;

/**
 * What a service tells the audit trail. The actor, time, IP and request id are not here on purpose:
 * {@code AuditService} reads them from the request, so a caller can neither forge nor forget them. Only events
 * that happen before a token exists (register, login, password reset) name the actor with {@link #by}.
 *
 * @param subject a raw identifier such as an attempted login email; it is hashed before it is stored
 */
public record AuditEvent(AuditAction action, String entityType, String entityId, Outcome outcome,
                         List<FieldChange> changes, String subject, String actorId, String actorRole) {

    public enum Outcome { SUCCESS, FAILURE }

    public AuditEvent {
        outcome = outcome == null ? Outcome.SUCCESS : outcome;
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public static AuditEvent of(AuditAction action) {
        return new AuditEvent(action, null, null, Outcome.SUCCESS, List.of(), null, null, null);
    }

    public AuditEvent on(String entityType, String entityId) {
        return new AuditEvent(action, entityType, entityId, outcome, changes, subject, actorId, actorRole);
    }

    public AuditEvent changing(List<FieldChange> newChanges) {
        return new AuditEvent(action, entityType, entityId, outcome, newChanges, subject, actorId, actorRole);
    }

    public AuditEvent failed() {
        return new AuditEvent(action, entityType, entityId, Outcome.FAILURE, changes, subject, actorId, actorRole);
    }

    public AuditEvent aboutSubject(String newSubject) {
        return new AuditEvent(action, entityType, entityId, outcome, changes, newSubject, actorId, actorRole);
    }

    public AuditEvent by(String newActorId, String newActorRole) {
        return new AuditEvent(action, entityType, entityId, outcome, changes, subject, newActorId, newActorRole);
    }

    /** Done by a background job, not by whoever's request happens to be on the thread. */
    public AuditEvent bySystem() {
        return by(null, "SYSTEM");
    }
}
