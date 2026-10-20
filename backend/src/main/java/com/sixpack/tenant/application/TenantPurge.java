package com.sixpack.tenant.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.shared.tenant.BackfillTarget;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.storage.FileStorage;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Deletes the data of stores whose offboarding grace period is over. Every tenant-owned collection is emptied
 * for the store except {@code audit_log}, which keeps its own 365-day retention so the deletion itself stays on
 * record. The tenant record stays as {@code DELETED} (without domains) so its slug is not reused and its audit
 * entries still name a store.
 */
@Service
public class TenantPurge {

    private static final Logger log = LoggerFactory.getLogger(TenantPurge.class);
    static final String KEPT = "audit_log";

    private final TenantRepository tenants;
    private final TenantRegistry registry;
    private final TenantMongo mongo;
    private final FileStorage storage;
    private final List<BackfillTarget> collections;
    private final TenantAdminService admin;

    TenantPurge(TenantRepository tenants, TenantRegistry registry, TenantMongo mongo, FileStorage storage,
                List<BackfillTarget> collections, TenantAdminService admin) {
        this.tenants = tenants;
        this.registry = registry;
        this.mongo = mongo;
        this.storage = storage;
        this.collections = collections;
        this.admin = admin;
    }

    @Scheduled(initialDelayString = "${app.tenant.purge-interval:PT1H}",
            fixedDelayString = "${app.tenant.purge-interval:PT1H}")
    void scheduled() {
        purgeDue(Instant.now());
    }

    /** Purges every store scheduled for deletion on or before {@code now}. One failure does not stop the rest. */
    public void purgeDue(Instant now) {
        for (Tenant t : tenants.findAll()) {
            if (t.status() == TenantStatus.SCHEDULED_FOR_DELETION && t.deletionDueAt() != null
                    && !t.deletionDueAt().isAfter(now)) {
                try {
                    purge(t);
                } catch (RuntimeException e) {
                    log.error("Purge of tenant '{}' failed; it stays scheduled and is retried", t.slug(), e);
                }
            }
        }
    }

    private void purge(Tenant t) {
        TenantContext.runAs(new TenantRef(t.id(), t.slug()), () -> {
            for (BackfillTarget target : collections) {
                if (!KEPT.equals(target.collection())) {
                    long removed = mongo.removeAll(target.collection());
                    log.info("Purged {} document(s) from '{}' for tenant '{}'", removed, target.collection(), t.slug());
                }
            }
            storage.deleteTenantFiles();
        });
        Tenant deleted = tenants.save(t.withStatus(TenantStatus.DELETED, null));
        registry.refresh();
        admin.record(AuditAction.TENANT_PURGED, t, deleted);
        log.info("Tenant '{}' purged", t.slug());
    }
}
