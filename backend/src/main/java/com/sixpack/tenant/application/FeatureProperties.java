package com.sixpack.tenant.application;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param defaults platform default per feature key; a feature missing here is off unless a store turns it on */
@ConfigurationProperties("app.features")
public record FeatureProperties(Map<String, Boolean> defaults) {

    public FeatureProperties {
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
    }
}
