package com.sixpack.shared.tenant;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Component;

/**
 * The only way tenant-owned data is read or written. Every query gets {@code tenantId == current tenant},
 * every saved document is stamped with it, and nothing can be moved to or overwritten from another tenant.
 * Fails closed: with no tenant in {@link TenantContext} it throws instead of widening the query.
 */
@Component
public class TenantMongo {

    static final String FIELD = "tenantId";

    private final MongoTemplate mongo;

    TenantMongo(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public <T> Optional<T> findById(String id, Class<T> type) {
        return findOne(Query.query(Criteria.where("id").is(id)), type);
    }

    public <T> Optional<T> findOne(Query query, Class<T> type) {
        return Optional.ofNullable(mongo.findOne(scoped(query), type));
    }

    public <T> List<T> find(Query query, Class<T> type) {
        return mongo.find(scoped(query), type);
    }

    public boolean exists(Query query, Class<?> type) {
        return mongo.exists(scoped(query), type);
    }

    public long count(Query query, Class<?> type) {
        return mongo.count(scoped(query), type);
    }

    public <T> T save(T document) {
        if (!(document instanceof TenantOwned owned)) {
            throw new IllegalArgumentException(document.getClass().getSimpleName() + " is not TenantOwned");
        }
        String tenantId = TenantContext.require().id();
        if (owned.getTenantId() == null) {
            owned.setTenantId(tenantId);
        } else if (!owned.getTenantId().equals(tenantId)) {
            throw new IllegalStateException("Document belongs to another tenant");
        }
        Object id = mongo.getConverter().getMappingContext().getRequiredPersistentEntity(document.getClass())
                .getIdentifierAccessor(document).getIdentifier();
        if (id != null && mongo.exists(Query.query(Criteria.where("id").is(id).and(FIELD).ne(tenantId)),
                document.getClass())) {
            throw new IllegalStateException("Document belongs to another tenant");
        }
        return mongo.save(document);
    }

    public UpdateResult updateFirst(Query query, UpdateDefinition update, Class<?> type) {
        rejectTenantChange(update);
        return mongo.updateFirst(scoped(query), update, type);
    }

    public UpdateResult updateMulti(Query query, UpdateDefinition update, Class<?> type) {
        rejectTenantChange(update);
        return mongo.updateMulti(scoped(query), update, type);
    }

    /** The tenant in the (scoped) query is copied onto a document the upsert creates. */
    public UpdateResult upsert(Query query, UpdateDefinition update, Class<?> type) {
        rejectTenantChange(update);
        return mongo.upsert(scoped(query), update, type);
    }

    public DeleteResult remove(Query query, Class<?> type) {
        return mongo.remove(scoped(query), type);
    }

    public <R> List<R> findDistinct(Query query, String field, Class<?> entity, Class<R> result) {
        return mongo.findDistinct(scoped(query), field, entity, result);
    }

    private static Query scoped(Query query) {
        return query.addCriteria(Criteria.where(FIELD).is(TenantContext.require().id()));
    }

    private static void rejectTenantChange(UpdateDefinition update) {
        for (Object operator : update.getUpdateObject().values()) {
            if (operator instanceof Document fields && fields.containsKey(FIELD)) {
                throw new IllegalArgumentException("An update must not change " + FIELD);
            }
        }
    }
}
