package com.sixpack.user.infra.mongo;

import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.user.domain.PasswordResetToken;
import com.sixpack.user.domain.PasswordResetTokenRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final TenantMongo mongo;

    MongoPasswordResetTokenRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(PasswordResetToken token) {
        PasswordResetTokenDocument doc = new PasswordResetTokenDocument();
        doc.userId = token.userId();
        doc.tokenHash = token.tokenHash();
        doc.expiresAt = token.expiresAt();
        mongo.insert(doc);
    }

    @Override
    public Optional<PasswordResetToken> consumeByHash(String tokenHash) {
        return mongo.findAndRemove(Query.query(Criteria.where("tokenHash").is(tokenHash)),
                        PasswordResetTokenDocument.class)
                .map(d -> new PasswordResetToken(d.id, d.userId, d.tokenHash, d.expiresAt));
    }

    @Override
    public void deleteByUserId(String userId) {
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), PasswordResetTokenDocument.class);
    }
}
