package com.sixpack.marketing.web;

import com.sixpack.marketing.application.CatalogFeedService;
import com.sixpack.marketing.application.CatalogFeedService.RenderedFeed;
import com.sixpack.marketing.application.FeedSettingsService;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product feed Google Merchant Center and the Meta catalogue fetch. Public: the secret token in the path and
 * the store the host resolves to must both match. Every miss is the same empty 404, so a wrong guess, a feed that
 * is off and a store without the feature look alike, and nothing is echoed back.
 */
@RestController
class FeedController {

    private static final MediaType RSS = MediaType.parseMediaType("application/rss+xml;charset=UTF-8");
    private static final CacheControl FIFTEEN_MINUTES = CacheControl.maxAge(Duration.ofMinutes(15)).cachePublic();

    private final FeedSettingsService feeds;
    private final CatalogFeedService catalog;
    private final TenantFeatures features;

    FeedController(FeedSettingsService feeds, CatalogFeedService catalog, TenantFeatures features) {
        this.feeds = feeds;
        this.catalog = catalog;
        this.features = features;
    }

    @GetMapping("/api/v1/feeds/{token}/products.xml")
    ResponseEntity<byte[]> products(@PathVariable String token) {
        if (!features.enabled(Feature.MARKETING) || !feeds.opens(token)) {
            return ResponseEntity.notFound().build();
        }
        RenderedFeed feed = catalog.current();
        // Spring answers 304 itself when If-None-Match carries this ETag.
        return ResponseEntity.ok().contentType(RSS).eTag(feed.etag()).cacheControl(FIFTEEN_MINUTES)
                .body(feed.body());
    }
}
