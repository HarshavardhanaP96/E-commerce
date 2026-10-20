package com.sixpack.product.infra.mongo;

import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.product.domain.OptionAxis;
import com.sixpack.product.domain.OptionFacet;
import com.sixpack.product.domain.OptionFilter;
import com.sixpack.product.domain.OptionChoice;
import com.sixpack.product.domain.OptionType;
import com.sixpack.product.domain.OptionValue;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.ProductSearch;
import com.sixpack.product.domain.ProductStatus;
import com.sixpack.product.domain.ProductVariants;
import com.sixpack.product.domain.SizeChart;
import com.sixpack.product.domain.Variant;
import com.sixpack.product.domain.VariantStatus;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.PageResult;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;

import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoProductRepository implements ProductRepository {

    private static final int MAX_ATTEMPTS = 5;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final TenantMongo mongo;

    MongoProductRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Product> findById(String id) {
        return mongo.findById(id, ProductDocument.class).map(MongoProductRepository::toDomain);
    }

    @Override
    public List<Product> findAllById(Collection<String> ids) {
        return find(Query.query(Criteria.where("id").in(ids)));
    }

    @Override
    public List<Product> findLatest(int limit) {
        return find(new Query(publicOnly()).with(NEWEST_FIRST).limit(limit));
    }

    @Override
    public List<Product> findAll() {
        return find(new Query().with(NEWEST_FIRST));
    }

    @Override
    public List<String> distinctCategories() {
        return mongo.findDistinct(new Query(publicOnly()), "category", ProductDocument.class, String.class).stream()
                .filter(c -> c != null && !c.isBlank()).sorted().toList();
    }

    @Override
    public PageResult<Product> search(ProductSearch s) {
        Query query = new Query(publicOnly());
        if (s.search() != null) {
            String literal = s.search().length() > 100 ? s.search().substring(0, 100) : s.search();
            query.addCriteria(Criteria.where("name")
                    .regex(Pattern.compile(escapeRegex(literal), Pattern.CASE_INSENSITIVE)));
        }
        if (s.maxPrice() != null) {
            query.addCriteria(Criteria.where("price").lte(s.maxPrice()));
        }
        if (s.category() != null) {
            query.addCriteria(Criteria.where("category").is(s.category()));
        }
        if (!s.options().isEmpty()) {
            Criteria variant = Criteria.where("status").ne(VariantStatus.DISABLED.name()).and("stock").gt(0)
                    .andOperator(s.options().stream().map(MongoProductRepository::optionMatch)
                            .toArray(Criteria[]::new));
            query.addCriteria(Criteria.where("variants.items").elemMatch(variant));
        }
        long total = mongo.count(query, ProductDocument.class);
        Sort sort = s.sortAsc() == null ? NEWEST_FIRST
                : Sort.by(s.sortAsc() ? Sort.Direction.ASC : Sort.Direction.DESC, "price");
        query.with(sort).skip((long) (s.page() - 1) * s.size()).limit(s.size());
        return new PageResult<>(find(query), total);
    }

    @Override
    public List<OptionFacet> optionFacets() {
        Query query = new Query(publicOnly()).addCriteria(Criteria.where("variants").exists(true));
        query.fields().include("variants.axes");
        // name -> type and value label -> hex, in first-seen order
        Map<String, OptionType> types = new TreeMap<>();
        Map<String, Map<String, String>> values = new HashMap<>();
        for (ProductDocument d : mongo.find(query, ProductDocument.class)) {
            for (ProductDocument.AxisDoc axis : orEmpty(d.variants == null ? null : d.variants.axes)) {
                types.putIfAbsent(axis.name, OptionType.fromStored(axis.type));
                Map<String, String> known = values.computeIfAbsent(axis.name, k -> new LinkedHashMap<>());
                for (ProductDocument.ValueDoc v : orEmpty(axis.values)) {
                    known.merge(v.label, v.hex == null ? "" : v.hex, (a, b) -> a.isEmpty() ? b : a);
                }
            }
        }
        return types.entrySet().stream().map(e -> new OptionFacet(e.getKey(), e.getValue(),
                values.get(e.getKey()).entrySet().stream()
                        .map(v -> new OptionFacet.Value(v.getKey(), v.getValue().isEmpty() ? null : v.getValue()))
                        .toList())).toList();
    }

    private static Criteria optionMatch(OptionFilter f) {
        return Criteria.where("options").elemMatch(Criteria.where("name").is(f.name()).and("value").is(f.value()));
    }

    @Override
    public Optional<Product> findBySlug(String slug) {
        return mongo.findOne(Query.query(Criteria.where("slug").is(slug)), ProductDocument.class)
                .map(MongoProductRepository::toDomain);
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        Criteria matches = new Criteria().orOperator(Criteria.where("sku").is(sku),
                Criteria.where("variants.items.sku").is(sku));
        return mongo.findOne(Query.query(matches), ProductDocument.class)
                .map(MongoProductRepository::toDomain);
    }

    @Override
    public boolean existsByCategoryId(String categoryId) {
        return mongo.exists(Query.query(Criteria.where("categoryId").is(categoryId)), ProductDocument.class);
    }

    @Override
    public Product save(Product p) {
        ProductDocument doc = p.id() == null ? null : mongo.findById(p.id(), ProductDocument.class).orElse(null);
        if (doc == null) {
            doc = new ProductDocument();
        }
        doc.id = p.id();
        doc.name = p.name();
        doc.photo = p.photo();
        // Price, stock and variants of a product with variants are owned by the variant operations; a plain save
        // (admin edit of name, tags, ...) must not write back a stale copy over live stock.
        if (doc.variants == null) {
            doc.price = p.price();
            doc.stock = p.stock();
        }
        doc.category = p.category();
        ProductDetails d = p.details();
        doc.slug = d.slug();
        doc.sku = d.sku();
        doc.description = d.description();
        doc.brand = d.brand();
        doc.mrp = d.mrp();
        doc.taxRate = d.taxRate();
        doc.hsn = d.hsn();
        doc.status = d.status().name();
        doc.tags = d.tags();
        doc.images = d.images();
        doc.categoryId = d.categoryId();
        doc.countryOfOrigin = d.countryOfOrigin();
        doc.lowStockThreshold = d.lowStockThreshold();
        doc.sizeChart = toDoc(d.sizeChart());
        return toDomain(mongo.save(doc));
    }

    @Override
    public void deleteById(String id) {
        mongo.remove(Query.query(Criteria.where("id").is(id)), ProductDocument.class);
    }

    @Override
    public boolean decrementStockIfAvailable(String id, int quantity) {
        var result = mongo.updateFirst(
                Query.query(Criteria.where("id").is(id).and("stock").gte(quantity).and("variants").exists(false)),
                new Update().inc("stock", -quantity).inc("rev", 1).currentDate("updatedAt"),
                ProductDocument.class);
        return result.getModifiedCount() == 1;
    }

    @Override
    public void incrementStock(String id, int quantity) {
        mongo.updateFirst(Query.query(Criteria.where("id").is(id)),
                new Update().inc("stock", quantity).inc("rev", 1).currentDate("updatedAt"), ProductDocument.class);
    }

    @Override
    public Optional<Product> updateVariants(String id, UnaryOperator<Product> change) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ProductDocument doc = mongo.findById(id, ProductDocument.class).orElse(null);
            if (doc == null) {
                return Optional.empty();
            }
            Product next = change.apply(toDomain(doc));
            long rev = doc.rev == null ? 0 : doc.rev;
            Update update = new Update().set("price", next.price()).set("stock", next.stock())
                    .set("rev", rev + 1).currentDate("updatedAt");
            if (next.hasVariants()) {
                update.set("variants", toDoc(next.details().variants()));
            } else {
                update.unset("variants");
            }
            // A document that never had a rev counts as revision 0.
            Criteria revision = rev == 0 ? Criteria.where("rev").in(null, 0L) : Criteria.where("rev").is(rev);
            if (mongo.updateFirst(Query.query(Criteria.where("id").is(id).andOperator(revision)), update,
                    ProductDocument.class).getModifiedCount() == 1) {
                return findById(id);
            }
        }
        throw ApiException.conflict("This product is being changed right now, please try again");
    }

    @Override
    public boolean decrementVariantStockIfAvailable(String productId, String variantId, int quantity) {
        var result = mongo.updateFirst(
                Query.query(Criteria.where("id").is(productId).and("variants.items").elemMatch(
                        Criteria.where("variantId").is(variantId).and("stock").gte(quantity)
                                .and("status").ne(VariantStatus.DISABLED.name()))),
                new Update().inc("variants.items.$.stock", -quantity).inc("stock", -quantity).inc("rev", 1)
                        .currentDate("updatedAt"),
                ProductDocument.class);
        return result.getModifiedCount() == 1;
    }

    @Override
    public boolean adjustVariantStock(String productId, String variantId, int delta) {
        Criteria variant = Criteria.where("variantId").is(variantId);
        if (delta < 0) {
            variant = variant.and("stock").gte(-delta);
        }
        var result = mongo.updateFirst(
                Query.query(Criteria.where("id").is(productId).and("variants.items").elemMatch(variant)),
                new Update().inc("variants.items.$.stock", delta).inc("stock", delta).inc("rev", 1)
                        .currentDate("updatedAt"),
                ProductDocument.class);
        return result.getModifiedCount() == 1;
    }

    @Override
    public boolean compareAndSetVariantStock(String productId, String variantId, int expected, int updated) {
        var result = mongo.updateFirst(
                Query.query(Criteria.where("id").is(productId).and("variants.items")
                        .elemMatch(Criteria.where("variantId").is(variantId).and("stock").is(expected))),
                new Update().set("variants.items.$.stock", updated).inc("stock", updated - expected)
                        .inc("rev", 1).currentDate("updatedAt"),
                ProductDocument.class);
        return result.getModifiedCount() == 1;
    }

    /** Matches ACTIVE products and legacy documents with no status at all. */
    private static Criteria publicOnly() {
        return Criteria.where("status").nin(ProductStatus.DRAFT.name(), ProductStatus.ARCHIVED.name());
    }

    private List<Product> find(Query query) {
        return mongo.find(query, ProductDocument.class).stream().map(MongoProductRepository::toDomain).toList();
    }

    static String escapeRegex(String literal) {
        return literal.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
    }

    private static Product toDomain(ProductDocument d) {
        ProductDetails details = new ProductDetails(d.slug, d.sku, d.description, d.brand, d.mrp, d.taxRate, d.hsn,
                ProductStatus.fromStored(d.status), d.tags, d.images, d.categoryId, d.countryOfOrigin,
                toDomain(d.variants), d.lowStockThreshold, toDomain(d.sizeChart));
        return new Product(d.id, d.name, d.photo, d.price == null ? 0 : d.price, d.stock == null ? 0 : d.stock,
                d.category, d.createdAt, details);
    }

    private static ProductVariants toDomain(ProductDocument.VariantsDoc v) {
        if (v == null) {
            return ProductVariants.EMPTY;
        }
        List<OptionAxis> axes = orEmpty(v.axes).stream().map(a -> new OptionAxis(a.name, OptionType.fromStored(a.type),
                orEmpty(a.values).stream().map(x -> new OptionValue(x.label, x.hex, x.images)).toList())).toList();
        List<Variant> items = orEmpty(v.items).stream().map(i -> new Variant(i.variantId, i.sku,
                orEmpty(i.options).stream().map(o -> new OptionChoice(o.name, o.value)).toList(),
                i.price == null ? 0 : i.price, i.mrp, i.stock == null ? 0 : i.stock,
                VariantStatus.fromStored(i.status))).toList();
        return new ProductVariants(axes, items);
    }

    private static ProductDocument.VariantsDoc toDoc(ProductVariants v) {
        var doc = new ProductDocument.VariantsDoc();
        doc.axes = v.axes().stream().map(a -> {
            var axis = new ProductDocument.AxisDoc();
            axis.name = a.name();
            axis.type = a.type().name();
            axis.values = a.values().stream().map(x -> {
                var value = new ProductDocument.ValueDoc();
                value.label = x.label();
                value.hex = x.hex();
                value.images = x.images();
                return value;
            }).toList();
            return axis;
        }).toList();
        doc.items = v.items().stream().map(i -> {
            var item = new ProductDocument.VariantDoc();
            item.variantId = i.id();
            item.sku = i.sku();
            item.options = i.options().stream().map(o -> {
                var choice = new ProductDocument.ChoiceDoc();
                choice.name = o.name();
                choice.value = o.value();
                return choice;
            }).toList();
            item.price = i.price();
            item.mrp = i.mrp();
            item.stock = i.stock();
            item.status = i.status().name();
            return item;
        }).toList();
        return doc;
    }

    private static SizeChart toDomain(ProductDocument.SizeChartDoc c) {
        return c == null ? SizeChart.EMPTY : new SizeChart(c.columns, orEmpty(c.rows), c.note);
    }

    private static ProductDocument.SizeChartDoc toDoc(SizeChart c) {
        if (c.isEmpty()) {
            return null;
        }
        var doc = new ProductDocument.SizeChartDoc();
        doc.columns = c.columns();
        doc.rows = c.rows();
        doc.note = c.note();
        return doc;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
