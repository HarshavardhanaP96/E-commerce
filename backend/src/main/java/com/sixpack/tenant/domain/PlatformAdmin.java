package com.sixpack.tenant.domain;

/** Operates the platform (creates and suspends tenants). Not a user of any store and cannot see store data. */
public record PlatformAdmin(String id, String name, String email, String passwordHash) {
}
