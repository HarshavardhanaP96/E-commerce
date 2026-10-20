package com.sixpack.audit.application;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.domain.AuditEntry;
import com.sixpack.audit.domain.AuditRepository;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.shared.security.EvidenceHasher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
class AuditService implements AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_USER_AGENT = 256;
    private static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private record Actor(String id, String role) {
    }

    private final AuditRepository entries;
    private final EvidenceHasher hasher;
    private final MeterRegistry meters;

    AuditService(AuditRepository entries, EvidenceHasher hasher, MeterRegistry meters) {
        this.entries = entries;
        this.hasher = hasher;
        this.meters = meters;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            entries.append(toEntry(event));
            meters.counter("sixpack.audit.write", Tags.of("result", "success").and(TenantContext.metricTag()))
                    .increment();
        } catch (RuntimeException e) {
            // Auditing must never fail the action it describes. The metric (and its alert) is what makes a gap visible.
            log.error("Audit write failed for action={} entity={}/{}", event.action(), event.entityType(),
                    event.entityId(), e);
            meters.counter("sixpack.audit.write", Tags.of("result", "failure").and(TenantContext.metricTag()))
                    .increment();
        }
    }

    private AuditEntry toEntry(AuditEvent e) {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest() : null;
        Actor actor = actor(e, request != null);
        String userAgent = request == null || request.getHeader(HttpHeaders.USER_AGENT) == null ? ""
                : truncate(request.getHeader(HttpHeaders.USER_AGENT));
        return new AuditEntry(null, Instant.now(),
                TenantContext.current().map(TenantRef::id).orElse(null), actor.id(), actor.role(), e.action(), e.entityType(),
                e.entityId(), e.outcome(), e.changes(), hasher.hash(normalise(e.subject())), traceId(),
                request == null ? "" : hasher.hash(request.getRemoteAddr()), userAgent);
    }

    private static Actor actor(AuditEvent e, boolean inRequest) {
        if (e.actorId() != null || e.actorRole() != null) {
            return new Actor(e.actorId(), e.actorRole());
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            AuthUser user = AuthUser.from(jwt);
            String role = PLATFORM_ADMIN.equals(jwt.getClaimAsString("role")) ? PLATFORM_ADMIN
                    : user.admin() ? "ADMIN" : "USER";
            return new Actor(user.id(), role);
        }
        return new Actor(null, inRequest ? "ANONYMOUS" : "SYSTEM");
    }

    private static String normalise(String subject) {
        return subject == null ? null : subject.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value) {
        return value.substring(0, Math.min(value.length(), MAX_USER_AGENT));
    }

    private static String traceId() {
        String id = MDC.get("traceId");
        return id == null ? MDC.get("trace_id") : id;
    }
}
