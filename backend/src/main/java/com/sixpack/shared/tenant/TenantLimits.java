package com.sixpack.shared.tenant;

/**
 * A store's own limits. A null field means "use the platform default" ({@code app.tenant.limits.*}).
 *
 * @param products          most products the store may hold
 * @param uploadBytes       most bytes of uploaded images the store may hold
 * @param requestsPerMinute most API requests per minute for the whole store (per backend instance)
 */
public record TenantLimits(Integer products, Long uploadBytes, Integer requestsPerMinute) {

    public static final TenantLimits NONE = new TenantLimits(null, null, null);
}
