package com.sixpack.marketing.web;

import com.sixpack.marketing.application.FeedSettingsService;
import com.sixpack.marketing.application.IntegrationService;
import com.sixpack.marketing.application.ServerEventsService;
import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.web.ApiResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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

    record SaveRequest(BrowserFields browser, ServerFields server) {
    }

    record BrowserFields(String ga4MeasurementId, String adsConversionId, String adsPurchaseLabel,
                         String gtmContainerId, String pixelId) {
    }

    /** {@code secret} is write-only: null keeps the saved one. {@code testEventCode} is Meta's only. */
    record ServerFields(Boolean enabled, String secret, String testEventCode) {
    }

    /** One card. Only the provider's own fields appear in {@code browser}; unset ones are null. Never a secret. */
    record IntegrationView(String provider, String status, Map<String, String> browser, Instant updatedAt,
                           Map<String, Object> server, Map<String, Object> lastError, Instant lastSuccessAt,
                           List<DeliveryView> deliveries) {
        static IntegrationView from(Integration i, boolean available, List<DeliveryView> deliveries) {
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
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("enabled", i.server().enabled());
            server.put("hasSecret", i.server().hasSecret());
            server.put("testEventCode", i.server().testEventCode());
            server.put("available", available);
            Map<String, Object> lastError = null;
            if (i.lastError() != null) {
                lastError = new LinkedHashMap<>();
                lastError.put("at", i.lastError().at());
                lastError.put("code", i.lastError().code());
                lastError.put("message", i.lastError().message());
            }
            return new IntegrationView(i.provider().key(), i.status().name(), browser, i.updatedAt(), server,
                    lastError, i.lastSuccessAt(), deliveries);
        }
    }

    private final IntegrationService service;
    private final FeedSettingsService feeds;
    private final ServerEventsService events;
    private final TenantFeatures features;

    MarketingAdminController(IntegrationService service, FeedSettingsService feeds, ServerEventsService events,
                             TenantFeatures features) {
        this.service = service;
        this.feeds = feeds;
        this.events = events;
        this.features = features;
    }

    @GetMapping
    Map<String, Object> all() {
        requireEnabled();
        return ApiResponse.of("integrations", service.all().stream().map(this::view).toList(),
                "feed", feeds.view());
    }

    @PutMapping("/{provider}")
    Map<String, Object> save(@PathVariable String provider, @RequestBody SaveRequest request) {
        Provider p = provider(provider);
        BrowserFields b = request.browser();
        ServerFields s = request.server();
        if (b == null && s == null) {
            throw ApiException.badRequest("browser: is required");
        }
        BrowserTags tags = null;
        if (b != null) {
            try {
                tags = BrowserTags.of(p, b.ga4MeasurementId(), b.adsConversionId(), b.adsPurchaseLabel(),
                        b.gtmContainerId(), b.pixelId());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
        }
        IntegrationService.ServerChange change = s == null ? null
                : new IntegrationService.ServerChange(Boolean.TRUE.equals(s.enabled()), s.secret(), s.testEventCode());
        return ApiResponse.of("integration", view(service.save(p, tags, change)));
    }

    @DeleteMapping("/{provider}")
    Map<String, Object> disconnect(@PathVariable String provider) {
        return ApiResponse.of("integration", view(service.disconnect(provider(provider))));
    }

    private IntegrationView view(Integration i) {
        return IntegrationView.from(i, service.serverAvailable(),
                events.recent(i.provider()).stream().map(DeliveryView::from).toList());
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
