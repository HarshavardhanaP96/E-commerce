package com.sixpack.marketing.web;

import com.sixpack.marketing.application.FeedSettingsService;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.web.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Marketing product feed. ADMIN-only via the existing {@code /api/v1/admin/**} rule. The literal
 * {@code /feed} path wins over {@code MarketingAdminController}'s {@code /{provider}}.
 */
@RestController
@RequestMapping("/api/v1/admin/marketing/feed")
class FeedAdminController {

    record FeedRequest(Boolean enabled) {
    }

    private final FeedSettingsService feeds;
    private final TenantFeatures features;

    FeedAdminController(FeedSettingsService feeds, TenantFeatures features) {
        this.feeds = feeds;
        this.features = features;
    }

    @PutMapping
    Map<String, Object> update(@RequestBody FeedRequest request) {
        requireEnabled();
        if (request.enabled() == null) {
            throw ApiException.badRequest("enabled: is required");
        }
        return ApiResponse.of("feed", feeds.setEnabled(request.enabled()));
    }

    @PostMapping("/rotate")
    Map<String, Object> rotate() {
        requireEnabled();
        return ApiResponse.of("feed", feeds.rotate());
    }

    private void requireEnabled() {
        if (!features.enabled(Feature.MARKETING)) {
            throw ApiException.notFound("Not found");
        }
    }
}
