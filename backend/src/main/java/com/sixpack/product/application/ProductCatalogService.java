package com.sixpack.product.application;

import static com.sixpack.shared.config.CacheConfig.PRODUCTS_ADMIN;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_BY_ID;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_LATEST;

import com.sixpack.product.CatalogReads;
import com.sixpack.product.LowStockLine;
import com.sixpack.product.ProductCatalog;
import com.sixpack.product.ProductChangedEvent;
import com.sixpack.product.ProductSnapshot;
import com.sixpack.product.StockLine;
import com.sixpack.product.domain.OptionAxis;
import com.sixpack.product.domain.OptionChoice;
import com.sixpack.product.domain.OptionValue;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.Variant;
import com.sixpack.shared.ApiException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
class ProductCatalogService implements ProductCatalog, CatalogReads {

    private final ProductRepository products;
    private final ApplicationEventPublisher events;
    private final ProductService service;

    ProductCatalogService(ProductRepository products, ApplicationEventPublisher events, ProductService service) {
        this.products = products;
        this.events = events;
        this.service = service;
    }

    @Override
    public List<LowStockLine> lowStock() {
        return service.lowStock().stream().map(a -> new LowStockLine(a.productId(), a.name(), a.variantId(),
                a.label(), a.sku(), a.stock(), a.threshold())).toList();
    }

    @Override
    public void forEachListing(int maxProducts, Consumer<Listing> sink) {
        products.forEachPublic(maxProducts, p -> {
            String description = p.details().description();
            String brand = p.details().brand();
            if (!p.hasVariants()) {
                sink.accept(new Listing(p.id(), null, p.name(), null, description, brand, p.photo(), p.price(),
                        p.stock()));
                return;
            }
            for (Variant v : p.details().variants().items()) {
                if (v.status().isSellable()) {
                    sink.accept(new Listing(p.id(), v.id(), p.name(), v.label(), description, brand,
                            variantPhoto(p, v), v.price(), v.stock()));
                }
            }
        });
    }

    @Override
    public List<ProductSnapshot> findSnapshots(Collection<String> productIds) {
        return products.findAllById(productIds).stream()
                .filter(p -> p.details().status().isPublic())
                .map(p -> new ProductSnapshot(p.id(), p.name(), p.photo(), p.price(), p.stock(),
                        p.details().sku(), p.details().taxRate(), null, null, null, p.category()))
                .toList();
    }

    @Override
    public ProductSnapshot resolveLine(String productId, String variantId) {
        Product p = products.findById(productId).filter(x -> x.details().status().isPublic())
                .orElseThrow(() -> ApiException.badRequest("A product in your cart no longer exists"));
        boolean wantsVariant = variantId != null && !variantId.isBlank();
        if (!p.hasVariants()) {
            if (wantsVariant) {
                throw ApiException.badRequest(p.name() + " has no options to choose from");
            }
            return new ProductSnapshot(p.id(), p.name(), p.photo(), p.price(), p.stock(), p.details().sku(),
                    p.details().taxRate(), null, null, null, p.category());
        }
        if (!wantsVariant) {
            throw ApiException.badRequest("Please choose options for " + p.name());
        }
        Variant v = p.details().variants().find(variantId).filter(x -> x.status().isSellable())
                .orElseThrow(() -> ApiException.badRequest(
                        "The selected option of " + p.name() + " is no longer available"));
        return new ProductSnapshot(p.id(), p.name(), variantPhoto(p, v), v.price(), v.stock(), v.sku(),
                p.details().taxRate(), v.id(), v.label(),
                v.options().stream().map(o -> new ProductSnapshot.Option(o.name(), o.value())).toList(),
                p.category());
    }

    /** The first image attached to any of the variant's option values (e.g. its colour), else the product photo. */
    private static String variantPhoto(Product p, Variant v) {
        for (OptionChoice choice : v.options()) {
            for (OptionAxis axis : p.details().variants().axes()) {
                if (!axis.name().equals(choice.name())) {
                    continue;
                }
                for (OptionValue value : axis.values()) {
                    if (value.label().equals(choice.value()) && !value.images().isEmpty()) {
                        return value.images().get(0);
                    }
                }
            }
        }
        return p.photo();
    }

    @Override
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public void reserveStock(Collection<StockLine> lines) {
        List<StockLine> reserved = new ArrayList<>();
        // Sorted order keeps concurrent reservations from interleaving inconsistently.
        for (StockLine line : sorted(lines)) {
            boolean ok = line.variantId() == null
                    ? products.decrementStockIfAvailable(line.productId(), line.quantity())
                    : products.decrementVariantStockIfAvailable(line.productId(), line.variantId(),
                            line.quantity());
            if (!ok) {
                releaseStock(reserved);
                throw ApiException.conflict("Insufficient stock for one or more items");
            }
            reserved.add(line);
        }
        events.publishEvent(new ProductChangedEvent());
    }

    @Override
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public void releaseStock(Collection<StockLine> lines) {
        for (StockLine line : lines) {
            if (line.variantId() == null) {
                products.incrementStock(line.productId(), line.quantity());
            } else {
                products.adjustVariantStock(line.productId(), line.variantId(), line.quantity());
            }
        }
    }

    private static List<StockLine> sorted(Collection<StockLine> lines) {
        return lines.stream().sorted(Comparator.comparing(StockLine::productId)
                .thenComparing(l -> Objects.toString(l.variantId(), ""))).toList();
    }
}
