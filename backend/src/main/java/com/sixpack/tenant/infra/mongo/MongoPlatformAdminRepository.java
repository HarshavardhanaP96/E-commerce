package com.sixpack.tenant.infra.mongo;

import com.sixpack.tenant.domain.PlatformAdmin;
import com.sixpack.tenant.domain.PlatformAdminRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoPlatformAdminRepository implements PlatformAdminRepository {

    private final MongoTemplate mongo;

    MongoPlatformAdminRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<PlatformAdmin> findByEmail(String email) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("email").is(email)),
                PlatformAdminDocument.class)).map(MongoPlatformAdminRepository::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return mongo.exists(Query.query(Criteria.where("email").is(email)), PlatformAdminDocument.class);
    }

    @Override
    public PlatformAdmin save(PlatformAdmin admin) {
        PlatformAdminDocument doc = new PlatformAdminDocument();
        doc.id = admin.id();
        doc.name = admin.name();
        doc.email = admin.email();
        doc.passwordHash = admin.passwordHash();
        return toDomain(mongo.save(doc));
    }

    private static PlatformAdmin toDomain(PlatformAdminDocument d) {
        return new PlatformAdmin(d.id, d.name, d.email, d.passwordHash);
    }
}
