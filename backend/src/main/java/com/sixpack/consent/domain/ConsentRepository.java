package com.sixpack.consent.domain;

import java.util.List;

/** Persistence port for consent records. There is deliberately no update or delete. */
public interface ConsentRepository {

    ConsentRecord append(ConsentRecord record);

    /** @return the user's records, newest first */
    List<ConsentRecord> findByUser(String userId);
}
