package com.sixpack.shared.tenant;

/** The tenant a piece of work is running for. */
public record TenantRef(String id, String slug) {
}
