package com.sixpack.audit.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.audit.FieldChange;
import com.sixpack.audit.domain.AuditEntry;
import com.sixpack.audit.domain.AuditRepository;
import com.sixpack.shared.PageResult;
import com.sixpack.shared.web.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of every store's audit log for platform administrators (role PLATFORM_ADMIN, enforced in the
 * security configuration). Same filters as the store view, plus {@code tenantId}.
 */
@RestController
@RequestMapping("/api/v1/platform/audit")
class PlatformAuditController {

    record EntryView(@JsonProperty("_id") String id, String tenantId, Instant at, String actorId, String actorRole,
                     String action, String entityType, String entityId, String outcome, List<FieldChange> changes,
                     String requestId) {
        static EntryView from(AuditEntry e) {
            return new EntryView(e.id(), e.tenantId(), e.at(), e.actorId(), e.actorRole(), e.action().name(),
                    e.entityType(), e.entityId(), e.outcome().name(), e.changes(), e.requestId());
        }
    }

    private final AuditRepository entries;

    PlatformAuditController(AuditRepository entries) {
        this.entries = entries;
    }

    @GetMapping
    Map<String, Object> search(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page) {
        PageResult<AuditEntry> result = entries.searchAllTenants(
                AuditController.query(entityType, entityId, actorId, action, outcome, from, to, page),
                AuditController.blankToNull(tenantId));
        return ApiResponse.of("entries", result.items().stream().map(EntryView::from).toList(),
                "totalPage", AuditController.totalPages(result));
    }
}
