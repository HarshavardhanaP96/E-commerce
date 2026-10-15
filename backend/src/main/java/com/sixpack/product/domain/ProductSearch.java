package com.sixpack.product.domain;

/**
 * @param search   case-insensitive substring of the name (matched literally, never as a regex)
 * @param maxPrice inclusive upper bound or null
 * @param category exact (normalised) category or null
 * @param sortAsc  null = newest first, true = price ascending, false = price descending
 */
public record ProductSearch(String search, Double maxPrice, String category, Boolean sortAsc, int page, int size) {
}
