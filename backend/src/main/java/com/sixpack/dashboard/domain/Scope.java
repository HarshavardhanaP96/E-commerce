package com.sixpack.dashboard.domain;

import java.util.Collection;

/**
 * Optional filters applied to every dashboard query.
 *
 * @param buyers when not null, only orders whose {@code user} is one of these ids count (an audience segment)
 */
public record Scope(String productId, String category, String userId, Collection<String> buyers) {

    public Scope(String productId, String category, String userId) {
        this(productId, category, userId, null);
    }

    /** This scope narrowed to orders by {@code ids}. */
    public Scope withBuyers(Collection<String> ids) {
        return new Scope(productId, category, userId, ids);
    }
}
