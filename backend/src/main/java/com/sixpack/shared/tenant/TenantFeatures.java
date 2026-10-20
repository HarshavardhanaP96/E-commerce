package com.sixpack.shared.tenant;

import java.util.Map;

/** Whether a {@link Feature} is on for the tenant in {@link TenantContext}, or the platform default without one. */
public interface TenantFeatures {

    boolean enabled(Feature feature);

    /** Every feature by key, with the store's setting applied over the platform default. */
    Map<String, Boolean> effective();
}
