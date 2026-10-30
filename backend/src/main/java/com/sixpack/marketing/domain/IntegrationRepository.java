package com.sixpack.marketing.domain;

import java.util.List;

/** The current store's integrations. Implementations scope every call to the tenant in context and fail without one. */
public interface IntegrationRepository {

    List<Integration> findAll();

    /** Creates or replaces the store's row for {@code integration.provider()}. */
    Integration save(Integration integration);

    /** @return whether a row was removed */
    boolean delete(Provider provider);
}
