package com.sixpack.user.infra.mongo;

import com.sixpack.user.domain.PasswordResetToken;
import com.sixpack.user.domain.PasswordResetTokenRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final MongoTemplate mongo;

    MongoPasswordResetTokenRepository(MongoTemplate mongo) {
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
        PasswordResetTokenDocument doc = mongo.findAndRemove(
                Query.query(Criteria.where("tokenHash").is(tokenHash)), PasswordResetTokenDocument.class);
        return Optional.ofNullable(doc)
                .map(d -> new PasswordResetToken(d.id, d.userId, d.tokenHash, d.expiresAt));
    }

    @Override
    public void deleteByUserId(String userId) {
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), PasswordResetTokenDocument.class);
    }
}
