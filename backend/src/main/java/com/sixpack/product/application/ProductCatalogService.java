package com.sixpack.product.application;

import static com.sixpack.shared.config.CacheConfig.PRODUCTS_ADMIN;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_BY_ID;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_LATEST;

import com.sixpack.product.ProductCatalog;
import com.sixpack.product.ProductChangedEvent;
import com.sixpack.product.ProductSnapshot;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.shared.ApiException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
class ProductCatalogService implements ProductCatalog {

    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    ProductCatalogService(ProductRepository products, ApplicationEventPublisher events) {
        this.products = products;
        this.events = events;
    }

    @Override
    public List<ProductSnapshot> findSnapshots(Collection<String> productIds) {
        return products.findAllById(productIds).stream()
                .filter(p -> p.details().status().isPublic())
                .map(p -> new ProductSnapshot(p.id(), p.name(), p.photo(), p.price(), p.stock()))
                .toList();
    }

    @Override
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public void reserveStock(Map<String, Integer> quantities) {
        Map<String, Integer> reserved = new LinkedHashMap<>();
        // Sorted order keeps concurrent reservations from interleaving inconsistently.
        for (var line : new TreeMap<>(quantities).entrySet()) {
            if (!products.decrementStockIfAvailable(line.getKey(), line.getValue())) {
                releaseStock(reserved);
                throw ApiException.conflict("Insufficient stock for one or more items");
            }
            reserved.put(line.getKey(), line.getValue());
        }
        events.publishEvent(new ProductChangedEvent());
    }

    @Override
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public void releaseStock(Map<String, Integer> quantities) {
        quantities.forEach(products::incrementStock);
    }
}
