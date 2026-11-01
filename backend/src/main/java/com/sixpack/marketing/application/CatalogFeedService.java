package com.sixpack.marketing.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sixpack.product.CatalogReads;
import com.sixpack.product.ProductChangedEvent;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantQuotas;
import com.sixpack.shared.tenant.TenantSettings;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Renders the current store's product feed and keeps it for 15 minutes or until its catalogue changes. */
@Service
public class CatalogFeedService {

    private static final Logger log = LoggerFactory.getLogger(CatalogFeedService.class);

    /** One store's rendered feed; {@code etag} is a hash of the body. */
    public record RenderedFeed(byte[] body, String etag) {
    }

    private final CatalogReads catalog;
    private final TenantSettings tenant;
    private final StoreConfig store;
    private final TenantQuotas quotas;
    // Not a CacheConfig cache: the size cap weighs this module's feed bytes. Keyed by the bound store, fail-closed;
    // get() renders once per store even when Google and Meta fetch at the same moment.
    private final Cache<String, RenderedFeed> feeds = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))
            .maximumWeight(64L * 1024 * 1024)
            .weigher((String tenantId, RenderedFeed feed) -> feed.body().length)
            .build();

    CatalogFeedService(CatalogReads catalog, TenantSettings tenant, StoreConfig store, TenantQuotas quotas) {
        this.catalog = catalog;
        this.tenant = tenant;
        this.store = store;
        this.quotas = quotas;
    }

    public RenderedFeed current() {
        return feeds.get(TenantContext.require().id(), id -> render());
    }

    @EventListener
    public void onProductChanged(ProductChangedEvent event) {
        evict();
    }

    /** Drops the current store's feed; with no store bound, every store's (each is rebuilt on its next fetch). */
    public void evict() {
        TenantContext.current().ifPresentOrElse(t -> feeds.invalidate(t.id()), feeds::invalidateAll);
    }

    private RenderedFeed render() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        FeedWriter feed = new FeedWriter(out, tenant.name(), "https://" + tenant.primaryDomain(), store.currency());
        catalog.forEachListing(quotas.maxProducts(), feed::add);
        feed.finish();
        log.info("Product feed rendered: {} item(s), {} left out without an image", feed.items(), feed.skipped());
        byte[] body = out.toByteArray();
        return new RenderedFeed(body, FeedWriter.sha256(body).substring(0, 32));
    }
}
