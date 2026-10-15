package com.sixpack.dashboard.application;

import com.sixpack.order.OrderChangedEvent;
import com.sixpack.product.ProductChangedEvent;
import com.sixpack.shared.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps dashboard numbers fresh without the order/product modules knowing the dashboard exists. */
@Component
class DashboardCacheEvictor {

    @EventListener
    @CacheEvict(cacheNames = CacheConfig.DASHBOARD, allEntries = true)
    public void onOrderChanged(OrderChangedEvent event) {
        // eviction handled by annotation
    }

    @EventListener
    @CacheEvict(cacheNames = CacheConfig.DASHBOARD, allEntries = true)
    public void onProductChanged(ProductChangedEvent event) {
        // eviction handled by annotation
    }
}
