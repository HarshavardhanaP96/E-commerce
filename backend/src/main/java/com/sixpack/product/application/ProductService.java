package com.sixpack.product.application;

import static com.sixpack.shared.config.CacheConfig.PRODUCTS_ADMIN;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_BY_ID;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_CATEGORIES;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_LATEST;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_OPTIONS;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.product.ProductChangedEvent;
import com.sixpack.product.domain.OptionFacet;
import com.sixpack.product.domain.OptionFilter;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.ProductSearch;
import com.sixpack.product.domain.CategoryRepository;
import com.sixpack.product.domain.ProductDetails;
import com.sixpack.product.domain.ProductStatus;
import com.sixpack.product.domain.ProductVariants;
import com.sixpack.product.domain.SizeChart;
import com.sixpack.product.domain.Slugs;
import com.sixpack.product.domain.StockAlert;
import com.sixpack.product.domain.Variant;
import com.sixpack.product.domain.VariantRules;
import com.sixpack.product.domain.VariantRules.AxisInput;
import com.sixpack.product.domain.VariantRules.VariantInput;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.PageResult;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.TenantFeatures;
import com.sixpack.shared.tenant.TenantQuotas;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.storage.FileStorage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    public record DetailsCommand(String slug, String sku, String description, String brand, Double mrp,
                                 Double taxRate, String hsn, String status, List<String> tags, String categoryId,
                                 String countryOfOrigin, Integer lowStockThreshold, List<byte[]> images) {
        public static final DetailsCommand NONE =
                new DetailsCommand(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public record VariantsCommand(List<AxisInput> axes, List<VariantInput> variants) {
    }

    public record SizeChartCommand(List<String> columns, List<List<String>> rows, String note) {
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
    private final StoreConfig store;
    private final CategoryRepository categories;
    private final AuditTrail audit;
    private final TenantFeatures features;
    private final TenantQuotas quotas;

    ProductService(ProductRepository products, FileStorage storage, ApplicationEventPublisher events,
                   StoreConfig store, CategoryRepository categories, AuditTrail audit,
                   TenantFeatures features, TenantQuotas quotas) {
        this.quotas = quotas;
        this.products = products;
        this.storage = storage;
        this.events = events;
        this.store = store;
        this.categories = categories;
        this.audit = audit;
        this.features = features;
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

    @Cacheable(PRODUCTS_OPTIONS)
    public List<OptionFacet> optionFacets() {
        return products.optionFacets();
    }

    /** @param options each {@code Name:Value}, split at the first colon */
    public PageResult<Product> search(String search, Double maxPrice, String category, String sort, int page,
                                      List<String> options) {
        Boolean sortAsc = sort == null || sort.isBlank() ? null : "asc".equalsIgnoreCase(sort);
        return products.search(new ProductSearch(blankToNull(search), maxPrice,
                Product.normalizeCategory(blankToNull(category)), sortAsc, Math.max(page, 1), store.effective().pageSize(),
                optionFilters(options)));
    }

    private static List<OptionFilter> optionFilters(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > 3) {
            throw ApiException.badRequest("option: at most 3 filters");
        }
        return raw.stream().map(o -> {
            int colon = o.indexOf(':');
            String name = colon < 0 ? "" : o.substring(0, colon).trim();
            String value = colon < 0 ? "" : o.substring(colon + 1).trim();
            if (name.isEmpty() || value.isEmpty()) {
                throw ApiException.badRequest("option: use Name:Value, e.g. Size:M");
            }
            return new OptionFilter(name, value);
        }).toList();
    }

    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_OPTIONS, PRODUCTS_ADMIN,
            PRODUCTS_BY_ID}, allEntries = true)
    public Product create(ProductCommand cmd) {
        if (cmd.photo() == null) {
            throw ApiException.badRequest("photo: is required");
        }
        int max = quotas.maxProducts();
        if (products.count() >= max) {
            throw ApiException.forbidden("This store has reached its limit of " + max + " products");
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

    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_OPTIONS, PRODUCTS_ADMIN,
            PRODUCTS_BY_ID}, allEntries = true)
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
            if (saved.hasVariants()) {
                // Colour images that pointed into the replaced gallery would dangle.
                products.updateVariants(id, p -> p.withVariants(
                        p.details().variants().retainingImages(galleryOf(p))));
            }
        }
        events.publishEvent(new ProductChangedEvent());
        audit.record(AuditEvent.of(AuditAction.PRODUCT_UPDATED).on("product", id)
                .changing(Changes.diff(CatalogAudit.view(current), CatalogAudit.view(saved), CatalogAudit.PRODUCT)));
        return saved;
    }

    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_OPTIONS, PRODUCTS_ADMIN,
            PRODUCTS_BY_ID}, allEntries = true)
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
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_OPTIONS, PRODUCTS_ADMIN,
            PRODUCTS_BY_ID}, allEntries = true)
    public Product replaceVariants(String id, VariantsCommand cmd) {
        if (!features.enabled(Feature.VARIANTS)) {
            throw ApiException.forbidden("Product variants are not enabled yet");
        }
        AtomicReference<Product> before = new AtomicReference<>();
        Product saved = products.updateVariants(id, current -> {
            before.set(current);
            ProductDetails d = current.details();
            ProductVariants built = VariantRules.build(d.variants(), cmd.axes(), cmd.variants(),
                    d.sku() != null ? d.sku() : d.slug(), d.sku(), () -> UUID.randomUUID().toString()
                            .replace("-", "").substring(0, 12));
            Set<String> gallery = galleryOf(current);
            for (String image : built.imagePaths()) {
                if (!gallery.contains(image)) {
                    throw ApiException.badRequest("axes.images: pick images from the product's own gallery");
                }
            }
            for (Variant v : built.items()) {
                if (takenBySomeoneElse(products.findBySku(v.sku()), id)) {
                    throw ApiException.conflict("sku: '" + v.sku() + "' is already in use");
                }
            }
            return current.withVariants(built);
        }).orElseThrow(() -> ApiException.notFound("Product not found"));
        events.publishEvent(new ProductChangedEvent());
        audit.record(AuditEvent.of(AuditAction.PRODUCT_UPDATED).on("product", id)
                .changing(Changes.diff(CatalogAudit.view(before.get()), CatalogAudit.view(saved), CatalogAudit.PRODUCT)));
        return saved;
    }

    /** Replaces the product's size guide; an empty chart removes it. */
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_OPTIONS, PRODUCTS_ADMIN,
            PRODUCTS_BY_ID}, allEntries = true)
    public Product replaceSizeChart(String id, SizeChartCommand cmd) {
        Product current = products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
        SizeChart chart = SizeChart.of(cmd.columns(), cmd.rows(), cmd.note());
        Product saved = products.save(current.withDetails(current.details().withSizeChart(chart)));
        events.publishEvent(new ProductChangedEvent());
        return saved;
    }

    /** Public lines at or below their low-stock threshold, emptiest first. */
    public List<StockAlert> lowStock() {
        int threshold = store.effective().lowStockThreshold();
        return products.findAll().stream().flatMap(p -> p.stockAlerts(threshold).stream())
                .sorted(Comparator.comparingInt(StockAlert::stock).thenComparing(StockAlert::name)
                        .thenComparing(a -> Objects.toString(a.label(), "")))
                .toList();
    }

    public int lowStockDefault() {
        return store.effective().lowStockThreshold();
    }

    /** Changes one variant's stock atomically, without touching the rest of the product. */
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_OPTIONS, PRODUCTS_ADMIN,
            PRODUCTS_BY_ID}, allEntries = true)
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
        Product saved = products.findById(id).orElseThrow();
        audit.record(AuditEvent.of(AuditAction.PRODUCT_UPDATED).on("product", id)
                .changing(Changes.diff(CatalogAudit.view(current), CatalogAudit.view(saved), CatalogAudit.PRODUCT)));
        return saved;
    }

    /** Images a colour may use: the product's gallery and its main photo. */
    private static Set<String> galleryOf(Product p) {
        Set<String> paths = new HashSet<>(p.details().images());
        paths.add(p.photo());
        return paths;
    }

    private Product get(String id, String variantId) {
        Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Product not found"));
        if (p.details().variants().find(variantId).isEmpty()) {
            throw ApiException.notFound("Variant not found");
        }
        return p;
    }

    public int pageSize() {
        return store.effective().pageSize();
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
                cur.images(), categoryId, pick(c.countryOfOrigin(), cur.countryOfOrigin()), cur.variants(),
                lowStockThreshold(c.lowStockThreshold(), cur.lowStockThreshold()), cur.sizeChart());
    }

    private static Integer lowStockThreshold(Integer proposed, Integer current) {
        if (proposed == null) {
            return current;
        }
        if (proposed < 0 || proposed > VariantRules.MAX_STOCK) {
            throw ApiException.badRequest("lowStockThreshold: must be between 0 and " + VariantRules.MAX_STOCK);
        }
        return proposed;
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
