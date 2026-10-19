package com.sixpack.consent.infra.mongo;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Append-only consent log; see docs/foundations/compliance-consent.md §6. */
@Document("consents")
class ConsentDocument {

    @Id
    String id;
    @Indexed
    String userId;
    String purpose;
    boolean granted;
    String policyVersion;
    String source;
    String ipHash;
    String userAgent;
    @CreatedDate
    Instant createdAt;
    /** Set once the account is erased; MongoDB's TTL monitor then deletes the record. */
    @Indexed(expireAfter = "0s")
    Instant expireAt;
}
