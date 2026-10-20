package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantFeatures;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Store setting first, then the platform default. Reads the tenant through the registry's cache. */
@Component
class TenantFeatureService implements TenantFeatures {

    private final TenantRegistry registry;
    private final FeatureProperties props;

    TenantFeatureService(TenantRegistry registry, FeatureProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @Override
    public boolean enabled(Feature feature) {
        Map<String, Boolean> overrides = TenantContext.current()
                .map(ref -> registry.byId(ref.id()).features()).orElse(Map.of());
        Boolean own = overrides.get(feature.key());
        return own != null ? own : props.defaults().getOrDefault(feature.key(), false);
    }

    @Override
    public Map<String, Boolean> effective() {
        Map<String, Boolean> view = new LinkedHashMap<>();
        for (Feature f : Feature.values()) {
            view.put(f.key(), enabled(f));
        }
        return view;
    }
}
