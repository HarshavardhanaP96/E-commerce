package com.sixpack.product.application;

import static com.sixpack.shared.config.CacheConfig.PRODUCTS_ADMIN;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_BY_ID;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_CATEGORIES;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_LATEST;

import com.sixpack.product.ProductChangedEvent;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.ProductSearch;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.PageResult;
import com.sixpack.storage.FileStorage;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    public record ProductCommand(String name, Double price, Integer stock, String category, byte[] photo) {
    }

    private final ProductRepository products;
    private final FileStorage storage;
    private final ApplicationEventPublisher events;
    private final ProductProperties props;

    ProductService(ProductRepository products, FileStorage storage, ApplicationEventPublisher events,
                   ProductProperties props) {
        this.products = products;
        this.storage = storage;
        this.events = events;
        this.props = props;
    }

    @Cacheable(PRODUCTS_LATEST)
    public List<Product> latest() {
        return products.findLatest(5);
    }

    @Cacheable(PRODUCTS_CATEGORIES)
    public List<String> categories() {
        return products.distinctCategories();
    }

    @Cacheable(PRODUCTS_ADMIN)
    public List<Product> all() {
        return products.findAll();
    }

    @Cacheable(PRODUCTS_BY_ID)
    public Product get(String id) {
        return products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
    }

    public PageResult<Product> search(String search, Double maxPrice, String category, String sort, int page) {
        Boolean sortAsc = sort == null || sort.isBlank() ? null : "asc".equalsIgnoreCase(sort);
        return products.search(new ProductSearch(blankToNull(search), maxPrice,
                Product.normalizeCategory(blankToNull(category)), sortAsc, Math.max(page, 1), props.pageSize()));
    }

    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public Product create(ProductCommand cmd) {
        if (cmd.photo() == null) {
            throw ApiException.badRequest("photo: is required");
        }
        String photo = storage.storeImage(cmd.photo());
        try {
            Product saved = products.save(new Product(null, cmd.name().trim(), photo, cmd.price(), cmd.stock(),
                    Product.normalizeCategory(cmd.category()), null));
            events.publishEvent(new ProductChangedEvent());
            return saved;
        } catch (RuntimeException e) {
            storage.delete(photo);
            throw e;
        }
    }

    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public Product update(String id, ProductCommand cmd) {
        Product current = products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
        String photo = current.photo();
        if (cmd.photo() != null) {
            photo = storage.storeImage(cmd.photo());
        }
        Product saved = products.save(new Product(id,
                cmd.name() != null && !cmd.name().isBlank() ? cmd.name().trim() : current.name(),
                photo,
                cmd.price() != null ? cmd.price() : current.price(),
                cmd.stock() != null ? cmd.stock() : current.stock(),
                cmd.category() != null && !cmd.category().isBlank()
                        ? Product.normalizeCategory(cmd.category()) : current.category(),
                current.createdAt()));
        if (!photo.equals(current.photo())) {
            storage.delete(current.photo());
        }
        events.publishEvent(new ProductChangedEvent());
        return saved;
    }

    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public void delete(String id) {
        Product current = products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
        products.deleteById(id);
        storage.delete(current.photo());
        events.publishEvent(new ProductChangedEvent());
    }

    public int pageSize() {
        return props.pageSize();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
