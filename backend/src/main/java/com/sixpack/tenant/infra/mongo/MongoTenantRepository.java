package com.sixpack.tenant.infra.mongo;

import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoTenantRepository implements TenantRepository {

    private final MongoTemplate mongo;

    MongoTenantRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return one(Query.query(Criteria.where("slug").is(slug)));
    }

    @Override
    public Optional<Tenant> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, TenantDocument.class)).map(MongoTenantRepository::toDomain);
    }

    @Override
    public Optional<Tenant> findByDomain(String domain) {
        return one(Query.query(Criteria.where("domains").is(domain)));
    }

    @Override
    public List<Tenant> findAll() {
        return mongo.findAll(TenantDocument.class).stream().map(MongoTenantRepository::toDomain).toList();
    }

    @Override
    public Tenant save(Tenant t) {
        TenantDocument doc = t.id() == null ? null : mongo.findById(t.id(), TenantDocument.class);
        if (doc == null) {
            doc = new TenantDocument();
        }
        doc.id = t.id();
        doc.slug = t.slug();
        doc.name = t.name();
        doc.status = t.status().name();
        doc.domains = t.domains();
        doc.theme = t.theme();
        doc.locale = t.locale();
        doc.currency = t.currency();
        return toDomain(mongo.save(doc));
    }

    private Optional<Tenant> one(Query query) {
        return Optional.ofNullable(mongo.findOne(query, TenantDocument.class)).map(MongoTenantRepository::toDomain);
    }

    private static Tenant toDomain(TenantDocument d) {
        return new Tenant(d.id, d.slug, d.name, d.status == null ? TenantStatus.ACTIVE : TenantStatus.valueOf(d.status),
                d.domains, d.theme, d.locale, d.currency, d.createdAt);
    }
}
