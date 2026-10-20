package com.sixpack.tenant.web;

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

    TenantConfigController(TenantRegistry registry, TenantFeatures features, StoreConfig store) {
        this.registry = registry;
        this.features = features;
        this.store = store;
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
        return ApiResponse.of("tenant", view);
    }
}
