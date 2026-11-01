package com.sixpack.marketing.web;

import com.sixpack.marketing.application.ServerEventsService;
import com.sixpack.marketing.domain.MarketingDestination.TestVerdict;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.web.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Server events on Admin → Marketing: test event and delivery log. Same guards as MarketingAdminController. */
@RestController
@RequestMapping("/api/v1/admin/marketing/{provider}")
class MarketingServerController {

    private final ServerEventsService events;
    private final TenantFeatures features;

    MarketingServerController(ServerEventsService events, TenantFeatures features) {
        this.events = events;
        this.features = features;
    }

    @PostMapping("/test")
    Map<String, Object> test(@PathVariable String provider) {
        TestVerdict verdict = events.test(provider(provider));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", verdict.ok());
        body.put("code", verdict.code());
        body.put("message", verdict.message());
        return ApiResponse.of("test", body);
    }

    @GetMapping("/deliveries")
    Map<String, Object> deliveries(@PathVariable String provider, @RequestParam(defaultValue = "1") String page) {
        Provider p = provider(provider);
        int n;
        try {
            n = Integer.parseInt(page.strip());
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n < 1) {
            throw ApiException.badRequest("page: must be a positive number");
        }
        Outbox.Page result = events.page(p, n);
        return ApiResponse.of("deliveries", result.rows().stream().map(DeliveryView::from).toList(), "page", n,
                "totalPage", result.totalPage());
    }

    private Provider provider(String key) {
        if (!features.enabled(Feature.MARKETING)) {
            throw ApiException.notFound("Not found");
        }
        return Provider.fromKey(key).orElseThrow(() -> ApiException.notFound("Not found"));
    }
}
