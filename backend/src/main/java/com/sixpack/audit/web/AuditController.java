package com.sixpack.audit.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent.Outcome;
import com.sixpack.audit.FieldChange;
import com.sixpack.audit.domain.AuditEntry;
import com.sixpack.audit.domain.AuditQuery;
import com.sixpack.audit.domain.AuditRepository;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.PageResult;
import com.sixpack.shared.web.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the audit log for admins. There is deliberately no write endpoint. */
@RestController
@RequestMapping("/api/v1/admin/audit")
class AuditController {

    static final int PAGE_SIZE = 100;

    /** Hashes and request evidence stay in the database; they are not useful to read and not worth exposing. */
    record EntryView(@JsonProperty("_id") String id, Instant at, String actorId, String actorRole, String action,
                     String entityType, String entityId, String outcome, List<FieldChange> changes,
                     String requestId) {
        static EntryView from(AuditEntry e) {
            return new EntryView(e.id(), e.at(), e.actorId(), e.actorRole(), e.action().name(), e.entityType(),
                    e.entityId(), e.outcome().name(), e.changes(), e.requestId());
        }
    }

    private final AuditRepository entries;

    AuditController(AuditRepository entries) {
        this.entries = entries;
    }

    @GetMapping
    Map<String, Object> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page) {
        PageResult<AuditEntry> result = entries.search(
                query(entityType, entityId, actorId, action, outcome, from, to, page));
        return ApiResponse.of("entries", result.items().stream().map(EntryView::from).toList(),
                "totalPage", totalPages(result));
    }

    static AuditQuery query(String entityType, String entityId, String actorId, String action, String outcome,
                            Instant from, Instant to, int page) {
        return new AuditQuery(blankToNull(entityType), blankToNull(entityId), blankToNull(actorId),
                blankToNull(action) == null ? null : AuditAction.parse(action),
                blankToNull(outcome) == null ? null : parseOutcome(outcome), from, to, Math.max(page, 1), PAGE_SIZE);
    }

    static long totalPages(PageResult<?> result) {
        return (result.total() + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private static Outcome parseOutcome(String value) {
        try {
            return Outcome.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("outcome: must be success or failure");
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
