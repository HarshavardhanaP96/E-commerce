package com.sixpack.shared.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-process caches with short TTLs (ADR-007). With several replicas a pod may serve data up to one TTL
 * stale; switch to Redis via {@code spring.cache.type=redis} when that matters.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRODUCTS_LATEST = "products.latest";
    public static final String PRODUCTS_CATEGORIES = "products.categories";
    public static final String PRODUCTS_BY_ID = "products.byId";
    public static final String PRODUCTS_ADMIN = "products.admin";
    public static final String DASHBOARD = "dashboard";

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        Caffeine<Object, Object> catalog = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(5_000).recordStats();
        for (String name : new String[] {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_BY_ID, PRODUCTS_ADMIN}) {
            manager.registerCustomCache(name, catalog.build());
        }
        manager.registerCustomCache(DASHBOARD, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(120)).maximumSize(500).recordStats().build());
        return new com.sixpack.shared.tenant.TenantCacheManager(manager);
    }
}
