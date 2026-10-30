package com.sixpack.tenant.web;

import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.MarketingTags;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.tenant.application.TenantRegistry;
import com.sixpack.tenant.domain.Tenant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the storefront needs to render itself for the current domain. Public, and free of internal fields. */
@RestController
@RequestMapping("/api/v1/tenant")
class TenantConfigController {

    private final TenantRegistry registry;
    private final TenantFeatures features;
    private final StoreConfig store;
    private final MarketingTags marketing;

    TenantConfigController(TenantRegistry registry, TenantFeatures features, StoreConfig store,
                           MarketingTags marketing) {
        this.registry = registry;
        this.features = features;
        this.store = store;
        this.marketing = marketing;
    }

    @GetMapping("/config")
    Map<String, Object> config() {
        Tenant tenant = registry.byId(TenantContext.require().id());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("slug", tenant.slug());
        view.put("name", tenant.name());
        view.put("theme", tenant.theme());
        view.put("locale", tenant.locale());
        view.put("currency", tenant.currency());
        view.put("storefront", tenant.storefront());
        view.put("features", features.effective());
        StoreConfig.Effective rules = store.effective();
        // What the cart and sign-up form show before the server prices or checks anything.
        Map<String, Object> commerce = new LinkedHashMap<>();
        commerce.put("shippingFee", rules.shippingFee());
        commerce.put("freeShippingAbove", rules.freeShippingAbove());
        commerce.put("defaultTaxRate", rules.defaultTaxRate());
        commerce.put("minimumAge", rules.minimumAge());
        view.put("commerce", commerce);
        view.put("timeZone", rules.timeZone());
        // The switch is checked outside the cached tags, so turning it off takes effect at once.
        if (features.enabled(Feature.MARKETING)) {
            MarketingTags.Tags tags = marketing.current();
            if (tags.hasAny()) {
                view.put("marketing", Map.of("tags", tags));
            }
        }
        return ApiResponse.of("tenant", view);
    }
}
