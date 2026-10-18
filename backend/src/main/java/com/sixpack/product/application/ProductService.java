package com.sixpack.product.application;

import static com.sixpack.shared.config.CacheConfig.PRODUCTS_ADMIN;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_BY_ID;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_CATEGORIES;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_LATEST;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.product.ProductChangedEvent;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.ProductSearch;
import com.sixpack.product.domain.CategoryRepository;
import com.sixpack.product.domain.ProductDetails;
import com.sixpack.product.domain.ProductStatus;
import com.sixpack.product.domain.ProductVariants;
import com.sixpack.product.domain.Slugs;
import com.sixpack.product.domain.Variant;
import com.sixpack.product.domain.VariantRules;
import com.sixpack.product.domain.VariantRules.AxisInput;
import com.sixpack.product.domain.VariantRules.VariantInput;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.PageResult;
import com.sixpack.storage.FileStorage;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    public record DetailsCommand(String slug, String sku, String description, String brand, Double mrp,
                                 Double taxRate, String hsn, String status, List<String> tags, String categoryId,
                                 String countryOfOrigin, List<byte[]> images) {
        public static final DetailsCommand NONE =
                new DetailsCommand(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public record VariantsCommand(List<AxisInput> axes, List<VariantInput> variants) {
    }

    /** Exactly one of {@code delta} (relative change) and {@code set} (absolute value). */
    public record StockCommand(Integer delta, Integer set) {
    }

    public record ProductCommand(String name, Double price, Integer stock, String category, byte[] photo,
                                 DetailsCommand details) {
        public ProductCommand {
            details = details == null ? DetailsCommand.NONE : details;
        }

        public ProductCommand(String name, Double price, Integer stock, String category, byte[] photo) {
            this(name, price, stock, category, photo, DetailsCommand.NONE);
        }
    }

    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_LENGTH = 30;

    private final ProductRepository products;
    private final FileStorage storage;
    private final ApplicationEventPublisher events;
    private final ProductProperties props;
    private final CategoryRepository categories;
    private final AuditTrail audit;

    ProductService(ProductRepository products, FileStorage storage, ApplicationEventPublisher events,
                   ProductProperties props, CategoryRepository categories, AuditTrail audit) {
        this.products = products;
        this.storage = storage;
        this.events = events;
        this.props = props;
        this.categories = categories;
        this.audit = audit;
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
        List<String> gallery = storeAll(cmd.details().images());
        try {
            String name = cmd.name().trim();
            Product saved = products.save(new Product(null, name, photo, cmd.price(), cmd.stock(),
                    Product.normalizeCategory(cmd.category()), null,
                    merge(null, name, ProductDetails.EMPTY, cmd.details(), cmd.price()).withImages(gallery)));
            events.publishEvent(new ProductChangedEvent());
            audit.record(AuditEvent.of(AuditAction.PRODUCT_CREATED).on("product", saved.id())
                    .changing(Changes.diff(Map.of(), CatalogAudit.view(saved), CatalogAudit.PRODUCT)));
            return saved;
        } catch (RuntimeException e) {
            storage.delete(photo);
            gallery.forEach(storage::delete);
            throw e;
        }
    }

    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public Product update(String id, ProductCommand cmd) {
        Product current = products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
        String name = cmd.name() != null && !cmd.name().isBlank() ? cmd.name().trim() : current.name();
        // With variants, price and stock are derived from them and cannot be edited here.
        double price = cmd.price() != null && !current.hasVariants() ? cmd.price() : current.price();
        ProductDetails details = merge(id, name, current.details(), cmd.details(), price);
        String photo = current.photo();
        if (cmd.photo() != null) {
            photo = storage.storeImage(cmd.photo());
        }
        List<String> oldGallery = current.details().images();
        List<String> gallery = cmd.details().images() == null || cmd.details().images().isEmpty()
                ? oldGallery : storeAll(cmd.details().images());
        Product saved = products.save(new Product(id, name, photo, price,
                cmd.stock() != null && !current.hasVariants() ? cmd.stock() : current.stock(),
                cmd.category() != null && !cmd.category().isBlank()
                        ? Product.normalizeCategory(cmd.category()) : current.category(),
                current.createdAt(), details.withImages(gallery)));
        if (!photo.equals(current.photo())) {
            storage.delete(current.photo());
        }
        if (gallery != oldGallery) {
            oldGallery.forEach(storage::delete);
        }
        events.publishEvent(new ProductChangedEvent());
        audit.record(AuditEvent.of(AuditAction.PRODUCT_UPDATED).on("product", id)
                .changing(Changes.diff(CatalogAudit.view(current), CatalogAudit.view(saved), CatalogAudit.PRODUCT)));
        return saved;
    }

    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public void delete(String id) {
        Product current = products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
        products.deleteById(id);
        storage.delete(current.photo());
        current.details().images().forEach(storage::delete);
        events.publishEvent(new ProductChangedEvent());
        audit.record(AuditEvent.of(AuditAction.PRODUCT_DELETED).on("product", id)
                .changing(Changes.diff(CatalogAudit.view(current), Map.of(), CatalogAudit.PRODUCT)));
    }

    /**
     * Replaces the option axes and variants of a product (an empty command removes them). Existing variants are
     * matched by id and keep their live stock; the product's price and stock are re-derived.
     */
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public Product replaceVariants(String id, VariantsCommand cmd) {
        if (!props.variantsEnabled()) {
            throw ApiException.forbidden("Product variants are not enabled yet");
        }
        Product saved = products.updateVariants(id, current -> {
            ProductDetails d = current.details();
            ProductVariants built = VariantRules.build(d.variants(), cmd.axes(), cmd.variants(),
                    d.sku() != null ? d.sku() : d.slug(), d.sku(), () -> UUID.randomUUID().toString()
                            .replace("-", "").substring(0, 12));
            for (Variant v : built.items()) {
                if (takenBySomeoneElse(products.findBySku(v.sku()), id)) {
                    throw ApiException.conflict("sku: '" + v.sku() + "' is already in use");
                }
            }
            return current.withVariants(built);
        }).orElseThrow(() -> ApiException.notFound("Product not found"));
        events.publishEvent(new ProductChangedEvent());
        return saved;
    }

    /** Changes one variant's stock atomically, without touching the rest of the product. */
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_ADMIN, PRODUCTS_BY_ID}, allEntries = true)
    public Product adjustVariantStock(String id, String variantId, StockCommand cmd) {
        if ((cmd.delta() == null) == (cmd.set() == null)) {
            throw ApiException.badRequest("stock: send exactly one of delta or set");
        }
        if (cmd.set() != null && (cmd.set() < 0 || cmd.set() > VariantRules.MAX_STOCK)) {
            throw ApiException.badRequest("stock: set must be between 0 and " + VariantRules.MAX_STOCK);
        }
        if (cmd.delta() != null && Math.abs((long) cmd.delta()) > VariantRules.MAX_STOCK) {
            throw ApiException.badRequest("stock: delta must be between -" + VariantRules.MAX_STOCK + " and "
                    + VariantRules.MAX_STOCK);
        }
        Product current = get(id, variantId);
        if (cmd.delta() != null) {
            if (!products.adjustVariantStock(id, variantId, cmd.delta())) {
                throw ApiException.conflict("stock: cannot go below zero");
            }
        } else {
            boolean done = false;
            Variant variant = current.details().variants().find(variantId).orElseThrow();
            for (int attempt = 0; attempt < 3 && !done; attempt++) {
                done = products.compareAndSetVariantStock(id, variantId, variant.stock(), cmd.set());
                if (!done) {
                    variant = get(id, variantId).details().variants().find(variantId).orElseThrow();
                }
            }
            if (!done) {
                throw ApiException.conflict("stock: changed while saving, please try again");
            }
        }
        events.publishEvent(new ProductChangedEvent());
        return products.findById(id).orElseThrow();
    }

    private Product get(String id, String variantId) {
        Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
        if (p.details().variants().find(variantId).isEmpty()) {
            throw ApiException.notFound("Variant not found");
        }
        return p;
    }

    public int pageSize() {
        return props.pageSize();
    }

    private List<String> storeAll(List<byte[]> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<String> stored = new ArrayList<>();
        try {
            for (byte[] f : files) {
                stored.add(storage.storeImage(f));
            }
        } catch (RuntimeException e) {
            stored.forEach(storage::delete);
            throw e;
        }
        return stored;
    }

    private ProductDetails merge(String selfId, String name, ProductDetails cur, DetailsCommand c, double price) {
        String sku = pick(c.sku(), cur.sku());
        if (sku != null && !sku.equals(cur.sku()) && takenBySomeoneElse(products.findBySku(sku), selfId)) {
            throw ApiException.conflict("sku: already in use");
        }

        String slug;
        if (hasText(c.slug())) {
            slug = Slugs.of(c.slug());
            if (!slug.equals(cur.slug()) && takenBySomeoneElse(products.findBySlug(slug), selfId)) {
                throw ApiException.conflict("slug: already in use");
            }
        } else if (cur.slug() != null) {
            slug = cur.slug();
        } else {
            slug = freeSlug(Slugs.of(name), selfId);
        }

        Double mrp = c.mrp() != null ? c.mrp() : cur.mrp();
        if (mrp != null && mrp < price) {
            throw ApiException.badRequest("mrp: must not be less than price");
        }

        String categoryId = pick(c.categoryId(), cur.categoryId());
        if (categoryId != null && !categoryId.equals(cur.categoryId()) && categories.findById(categoryId).isEmpty()) {
            throw ApiException.badRequest("categoryId: category not found");
        }

        return new ProductDetails(slug, sku, pick(c.description(), cur.description()), pick(c.brand(), cur.brand()),
                mrp, c.taxRate() != null ? c.taxRate() : cur.taxRate(), pick(c.hsn(), cur.hsn()),
                hasText(c.status()) ? ProductStatus.parse(c.status()) : cur.status(),
                c.tags() != null && !c.tags().isEmpty() ? normalizeTags(c.tags()) : cur.tags(),
                cur.images(), categoryId, pick(c.countryOfOrigin(), cur.countryOfOrigin()), cur.variants());
    }

    private String freeSlug(String base, String selfId) {
        String candidate = base;
        for (int n = 2; takenBySomeoneElse(products.findBySlug(candidate), selfId); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    private static boolean takenBySomeoneElse(Optional<Product> found, String selfId) {
        return found.filter(p -> !Objects.equals(p.id(), selfId)).isPresent();
    }

    private static List<String> normalizeTags(List<String> raw) {
        var tags = new LinkedHashSet<String>();
        for (String t : raw) {
            if (t != null && !t.isBlank()) {
                tags.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (tags.size() > MAX_TAGS || tags.stream().anyMatch(t -> t.length() > MAX_TAG_LENGTH)) {
            throw ApiException.badRequest("tags: at most " + MAX_TAGS + " tags of " + MAX_TAG_LENGTH + " characters");
        }
        return List.copyOf(tags);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** New value when provided (trimmed), otherwise the current one. Blank counts as not provided. */
    private static String pick(String proposed, String current) {
        return hasText(proposed) ? proposed.trim() : current;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
