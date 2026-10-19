package com.sixpack.product.domain;

import java.util.List;

/**
 * @param search   case-insensitive substring of the name (matched literally, never as a regex)
 * @param maxPrice inclusive upper bound or null
 * @param category exact (normalised) category or null
 * @param sortAsc  null = newest first, true = price ascending, false = price descending
 * @param options  all must be matched by one and the same sellable, in-stock variant
 */
public record ProductSearch(String search, Double maxPrice, String category, Boolean sortAsc, int page, int size,
                            List<OptionFilter> options) {

    public ProductSearch {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public ProductSearch(String search, Double maxPrice, String category, Boolean sortAsc, int page, int size) {
        this(search, maxPrice, category, sortAsc, page, size, null);
    }
}
