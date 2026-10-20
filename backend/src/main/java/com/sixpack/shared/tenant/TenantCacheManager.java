package com.sixpack.shared.tenant;

import java.util.Collection;
import java.util.concurrent.Callable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Decorates a cache manager so every entry is keyed by the current tenant. Fails closed: reading or writing a
 * cache with no tenant in context throws. Clearing a cache removes every tenant's entries, which is harmless
 * because the entries are rebuilt on demand.
 */
public class TenantCacheManager implements CacheManager {

    private final CacheManager delegate;

    public TenantCacheManager(CacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(name);
        return cache == null ? null : new TenantCache(cache);
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    record TenantKey(String tenantId, Object key) {
    }

    private static final class TenantCache implements Cache {

        private final Cache delegate;

        TenantCache(Cache delegate) {
            this.delegate = delegate;
        }

        private static TenantKey key(Object key) {
            return new TenantKey(TenantContext.require().id(), key);
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            return delegate.get(key(key));
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return delegate.get(key(key), type);
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            return delegate.get(key(key), valueLoader);
        }

        @Override
        public void put(Object key, Object value) {
            delegate.put(key(key), value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            return delegate.putIfAbsent(key(key), value);
        }

        @Override
        public void evict(Object key) {
            delegate.evict(key(key));
        }

        @Override
        public boolean evictIfPresent(Object key) {
            return delegate.evictIfPresent(key(key));
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public boolean invalidate() {
            return delegate.invalidate();
        }
    }
}
