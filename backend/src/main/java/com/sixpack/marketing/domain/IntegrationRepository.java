package com.sixpack.marketing.domain;

import java.time.Instant;
import java.util.List;

/** The current store's integrations. Implementations scope every call to the tenant in context and fail without one. */
public interface IntegrationRepository {

    List<Integration> findAll();

    /** Creates or replaces the store's row for {@code integration.provider()}. */
    Integration save(Integration integration);

    /** @return whether a row was removed */
    boolean delete(Provider provider);

    /** Sets the server settings on the existing row (never creates one); a new secret also clears the last error. */
    void saveServer(Provider provider, ServerSettings server, boolean newSecret, Instant updatedAt);

    /** Dispatcher only. */
    void recordFailure(Provider provider, LastError error);

    /** Dispatcher only. */
    void recordSuccess(Provider provider, Instant at);
}
