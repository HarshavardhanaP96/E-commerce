package com.sixpack.product.domain;

import java.time.Instant;

public record Category(String id, String name, String slug, String parentId, String image, Instant createdAt) {
}
