package com.sixpack.marketing.web;

import com.sixpack.marketing.application.IntegrationService;
import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.web.ApiResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Marketing. ADMIN-only via the existing {@code /api/v1/admin/**} rule; the admin agreement interceptor
 * covers it too. A store without the feature, and an unknown provider, get 404 as if the page did not exist.
 */
@RestController
@RequestMapping("/api/v1/admin/marketing")
class MarketingAdminController {

    record SaveRequest(BrowserFields browser) {
    }

    record BrowserFields(String ga4MeasurementId, String adsConversionId, String adsPurchaseLabel,
                         String gtmContainerId, String pixelId) {
    }

    /** One card. Only the provider's own fields appear in {@code browser}; unset ones are null. */
    record IntegrationView(String provider, String status, Map<String, String> browser, Instant updatedAt) {
        static IntegrationView from(Integration i) {
            BrowserTags t = i.browser();
            Map<String, String> browser = new LinkedHashMap<>();
            if (i.provider() == Provider.GOOGLE) {
                browser.put("ga4MeasurementId", t.ga4MeasurementId());
                browser.put("adsConversionId", t.adsConversionId());
                browser.put("adsPurchaseLabel", t.adsPurchaseLabel());
                browser.put("gtmContainerId", t.gtmContainerId());
            } else {
                browser.put("pixelId", t.pixelId());
            }
            return new IntegrationView(i.provider().key(), i.status().name(), browser, i.updatedAt());
        }
    }

    private final IntegrationService service;
    private final TenantFeatures features;

    MarketingAdminController(IntegrationService service, TenantFeatures features) {
        this.service = service;
        this.features = features;
    }

    @GetMapping
    Map<String, Object> all() {
        requireEnabled();
        return ApiResponse.of("integrations", service.all().stream().map(IntegrationView::from).toList());
    }

    @PutMapping("/{provider}")
    Map<String, Object> save(@PathVariable String provider, @RequestBody SaveRequest request) {
        Provider p = provider(provider);
        BrowserFields b = request.browser();
        if (b == null) {
            throw ApiException.badRequest("browser: is required");
        }
        BrowserTags tags;
        try {
            tags = BrowserTags.of(p, b.ga4MeasurementId(), b.adsConversionId(), b.adsPurchaseLabel(),
                    b.gtmContainerId(), b.pixelId());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return ApiResponse.of("integration", IntegrationView.from(service.save(p, tags)));
    }

    @DeleteMapping("/{provider}")
    Map<String, Object> disconnect(@PathVariable String provider) {
        return ApiResponse.of("integration", IntegrationView.from(service.disconnect(provider(provider))));
    }

    private Provider provider(String key) {
        requireEnabled();
        return Provider.fromKey(key).orElseThrow(() -> ApiException.notFound("Not found"));
    }

    private void requireEnabled() {
        if (!features.enabled(Feature.MARKETING)) {
            throw ApiException.notFound("Not found");
        }
    }
}
