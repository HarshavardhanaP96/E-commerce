package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.util.List;

/** Read side of the Location tab, on the shipping address saved with each order. */
public interface LocationReads {

    /** @param key the raw stored value (country or state as typed, PIN prefix or PIN as digits); null if missing */
    record RegionCount(String key, long orders, double sales) {
    }

    /** Raw values to match; a null field means no constraint. */
    record RegionMatch(List<String> countries, List<String> states, Integer pinPrefix) {
        public static final RegionMatch ALL = new RegionMatch(null, null, null);
    }

    /** Orders placed in [from, to) in the matched region, grouped at {@code level}; narrowed to items like the trend. */
    List<RegionCount> count(Scope scope, Instant from, Instant to, RegionMatch match, RegionLevel level);

    /** One product's sales among the orders shipped to a region; narrowed to items like the trend. */
    record RegionProduct(String productId, String name, long units, double revenue) {
    }

    /** Best sellers by units among orders shipped to the matched region. */
    List<RegionProduct> topProducts(Scope scope, Instant from, Instant to, RegionMatch match, int limit);
}
