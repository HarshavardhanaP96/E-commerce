package com.sixpack.shared.tenant;

import java.util.Arrays;
import java.util.Optional;

/**
 * Features a platform administrator can switch on or off per store. The platform default for each comes from
 * {@code app.features.defaults.<key>}; a store's own setting, when present, wins.
 */
public enum Feature {
    /** Products with size/colour options (docs/superpowers/plans/2026-10-18-product-variants.md). */
    VARIANTS("variants"),
    /** Admin → Marketing: Google and Meta tags (docs/superpowers/specs/2026-10-30-marketing-integrations-design.md). */
    MARKETING("marketing");

    private final String key;

    Feature(String key) {
        this.key = key;
    }

    /** The name used in the API, the tenant document and the configuration. */
    public String key() {
        return key;
    }

    public static Optional<Feature> fromKey(String key) {
        return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
    }
}
