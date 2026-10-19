package com.sixpack.user.infra.mongo;

import com.sixpack.user.domain.EmailVerificationToken;
import com.sixpack.user.domain.EmailVerificationTokenRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoEmailVerificationTokenRepository implements EmailVerificationTokenRepository {

    private final MongoTemplate mongo;

    MongoEmailVerificationTokenRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(EmailVerificationToken token) {
        EmailVerificationTokenDocument doc = new EmailVerificationTokenDocument();
        doc.userId = token.userId();
        doc.tokenHash = token.tokenHash();
        doc.expiresAt = token.expiresAt();
        mongo.insert(doc);
    }

    @Override
    public Optional<EmailVerificationToken> consumeByHash(String tokenHash) {
        EmailVerificationTokenDocument doc = mongo.findAndRemove(
                Query.query(Criteria.where("tokenHash").is(tokenHash)), EmailVerificationTokenDocument.class);
        return Optional.ofNullable(doc)
                .map(d -> new EmailVerificationToken(d.id, d.userId, d.tokenHash, d.expiresAt));
    }

    @Override
    public void deleteByUserId(String userId) {
        mongo.remove(Query.query(Criteria.where("userId").is(userId)), EmailVerificationTokenDocument.class);
    }
}
