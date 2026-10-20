package com.sixpack.dashboard.domain;

/**
 * What every analytics section is asked for: a scope, a period of store-local days, how to bucket it, what to
 * compare with, which revenue basis, and how long ranked lists may be.
 *
 * @param granularity null picks one from the length of the period
 */
public record SectionQuery(Scope scope, Period period, Granularity granularity, Compare compare, Basis basis,
                           int limit) {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;

    public SectionQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit: must be between 1 and " + MAX_LIMIT);
        }
        granularity = granularity == null ? Granularity.auto(period) : granularity;
        granularity.buckets(period); // fails fast on too many points, before any query runs
    }

    /** @return the period to compare with, or null when the comparison is off */
    public Period comparePeriod() {
        return compare.apply(period);
    }

    /** Narrowed to a product or category: only the matching order items count. */
    public boolean itemScoped() {
        return scope.productId() != null || scope.category() != null;
    }
}
