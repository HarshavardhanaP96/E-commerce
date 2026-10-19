package com.sixpack.consent.infra.mongo;

import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.consent.Purpose;
import com.sixpack.consent.domain.ConsentRecord;
import com.sixpack.consent.domain.ConsentRecord.Source;
import com.sixpack.consent.domain.ConsentRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoConsentRepository implements ConsentRepository {

    // _id breaks ties between records written in the same millisecond (ObjectIds increase).
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "_id");

    private final TenantMongo mongo;

    MongoConsentRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public ConsentRecord append(ConsentRecord record) {
        ConsentDocument doc = new ConsentDocument();
        doc.userId = record.userId();
        doc.purpose = record.purpose().wireValue();
        doc.granted = record.granted();
        doc.policyVersion = record.policyVersion();
        doc.source = record.source().wireValue();
        doc.ipHash = record.ipHash();
        doc.userAgent = record.userAgent();
        return toDomain(mongo.insert(doc));
    }

    @Override
    public List<ConsentRecord> findByUser(String userId) {
        return mongo.find(Query.query(Criteria.where("userId").is(userId)).with(NEWEST_FIRST), ConsentDocument.class)
                .stream().map(MongoConsentRepository::toDomain).toList();
    }

    private static ConsentRecord toDomain(ConsentDocument d) {
        return new ConsentRecord(d.id, d.userId, Purpose.fromWire(d.purpose), d.granted, d.policyVersion,
                Source.fromWire(d.source), d.ipHash, d.userAgent, d.createdAt);
    }
}
