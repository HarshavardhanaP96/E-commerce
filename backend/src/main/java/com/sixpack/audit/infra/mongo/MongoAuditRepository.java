package com.sixpack.audit.infra.mongo;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent.Outcome;
import com.sixpack.audit.FieldChange;
import com.sixpack.audit.domain.AuditEntry;
import com.sixpack.audit.domain.AuditQuery;
import com.sixpack.audit.domain.AuditRepository;
import com.sixpack.shared.PageResult;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoAuditRepository implements AuditRepository {

    // _id breaks ties between entries written in the same millisecond (ObjectIds increase).
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "at", "_id");

    private final MongoTemplate mongo;

    MongoAuditRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public AuditEntry append(AuditEntry entry) {
        return toDomain(mongo.insert(toDocument(entry)));
    }

    @Override
    public PageResult<AuditEntry> search(AuditQuery q) {
        Query query = new Query();
        if (q.entityType() != null) {
            query.addCriteria(Criteria.where("entityType").is(q.entityType()));
        }
        if (q.entityId() != null) {
            query.addCriteria(Criteria.where("entityId").is(q.entityId()));
        }
        if (q.actorId() != null) {
            query.addCriteria(Criteria.where("actorId").is(q.actorId()));
        }
        if (q.action() != null) {
            query.addCriteria(Criteria.where("action").is(q.action().name()));
        }
        if (q.outcome() != null) {
            query.addCriteria(Criteria.where("outcome").is(q.outcome().name()));
        }
        if (q.from() != null || q.to() != null) {
            Criteria range = Criteria.where("at");
            if (q.from() != null) {
                range = range.gte(q.from());
            }
            if (q.to() != null) {
                range = range.lte(q.to());
            }
            query.addCriteria(range);
        }
        long total = mongo.count(query, AuditDocument.class);
        long skip = (long) (q.page() - 1) * q.size();
        if (skip > Integer.MAX_VALUE) {
            // Spring Data narrows skip to an int for the driver; anything this far out is past the end anyway.
            return new PageResult<>(List.of(), total);
        }
        query.with(NEWEST_FIRST).skip(skip).limit(q.size());
        List<AuditEntry> items = mongo.find(query, AuditDocument.class).stream()
                .map(MongoAuditRepository::toDomain).toList();
        return new PageResult<>(items, total);
    }

    private static AuditDocument toDocument(AuditEntry e) {
        AuditDocument d = new AuditDocument();
        d.at = e.at();
        d.tenantId = e.tenantId();
        d.actorId = e.actorId();
        d.actorRole = e.actorRole();
        d.action = e.action().name();
        d.entityType = e.entityType();
        d.entityId = e.entityId();
        d.outcome = e.outcome().name();
        d.changes = e.changes().stream().map(c -> {
            AuditDocument.ChangeDocument cd = new AuditDocument.ChangeDocument();
            cd.field = c.field();
            cd.before = c.before();
            cd.after = c.after();
            cd.redacted = c.redacted();
            return cd;
        }).toList();
        d.subjectHash = e.subjectHash();
        d.requestId = e.requestId();
        d.ipHash = e.ipHash();
        d.userAgent = e.userAgent();
        return d;
    }

    private static AuditEntry toDomain(AuditDocument d) {
        List<FieldChange> changes = d.changes == null ? List.of()
                : d.changes.stream().map(c -> new FieldChange(c.field, c.before, c.after, c.redacted)).toList();
        return new AuditEntry(d.id, d.at, d.tenantId, d.actorId, d.actorRole, AuditAction.valueOf(d.action),
                d.entityType, d.entityId, Outcome.valueOf(d.outcome), changes, d.subjectHash, d.requestId,
                d.ipHash, d.userAgent);
    }
}
