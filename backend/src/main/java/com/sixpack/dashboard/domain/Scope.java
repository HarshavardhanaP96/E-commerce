package com.sixpack.dashboard.domain;

/** Optional filters applied to every dashboard query. */
public record Scope(String productId, String category, String userId) {
}
