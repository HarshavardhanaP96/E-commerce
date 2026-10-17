package com.sixpack.product.infra.mongo;

import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.ProductSearch;
import com.sixpack.product.domain.ProductStatus;
import com.sixpack.shared.PageResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoProductRepository implements ProductRepository {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final MongoTemplate mongo;

    MongoProductRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Product> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, ProductDocument.class)).map(MongoProductRepository::toDomain);
    }

    @Override
    public List<Product> findAllById(Collection<String> ids) {
        return find(Query.query(Criteria.where("id").in(ids)));
    }

    @Override
    public List<Product> findLatest(int limit) {
        return find(new Query().with(NEWEST_FIRST).limit(limit));
    }

    @Override
    public List<Product> findAll() {
        return find(new Query().with(NEWEST_FIRST));
    }

    @Override
    public List<String> distinctCategories() {
        return mongo.findDistinct(new Query(), "category", ProductDocument.class, String.class).stream()
                .filter(c -> c != null && !c.isBlank()).sorted().toList();
    }

    @Override
    public PageResult<Product> search(ProductSearch s) {
        Query query = new Query();
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
        long total = mongo.count(query, ProductDocument.class);
        Sort sort = s.sortAsc() == null ? NEWEST_FIRST
                : Sort.by(s.sortAsc() ? Sort.Direction.ASC : Sort.Direction.DESC, "price");
        query.with(sort).skip((long) (s.page() - 1) * s.size()).limit(s.size());
        return new PageResult<>(find(query), total);
    }

    @Override
    public Optional<Product> findBySlug(String slug) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("slug").is(slug)), ProductDocument.class))
                .map(MongoProductRepository::toDomain);
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("sku").is(sku)), ProductDocument.class))
                .map(MongoProductRepository::toDomain);
    }

    @Override
    public Product save(Product p) {
        ProductDocument doc = p.id() == null ? null : mongo.findById(p.id(), ProductDocument.class);
        if (doc == null) {
            doc = new ProductDocument();
        }
        doc.id = p.id();
        doc.name = p.name();
        doc.photo = p.photo();
        doc.price = p.price();
        doc.stock = p.stock();
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
        return toDomain(mongo.save(doc));
    }

    @Override
    public void deleteById(String id) {
        mongo.remove(Query.query(Criteria.where("id").is(id)), ProductDocument.class);
    }

    @Override
    public boolean decrementStockIfAvailable(String id, int quantity) {
        var result = mongo.updateFirst(
                Query.query(Criteria.where("id").is(id).and("stock").gte(quantity)),
                new Update().inc("stock", -quantity).currentDate("updatedAt"),
                ProductDocument.class);
        return result.getModifiedCount() == 1;
    }

    @Override
    public void incrementStock(String id, int quantity) {
        mongo.updateFirst(Query.query(Criteria.where("id").is(id)),
                new Update().inc("stock", quantity).currentDate("updatedAt"), ProductDocument.class);
    }

    private List<Product> find(Query query) {
        return mongo.find(query, ProductDocument.class).stream().map(MongoProductRepository::toDomain).toList();
    }

    static String escapeRegex(String literal) {
        return literal.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
    }

    private static Product toDomain(ProductDocument d) {
        ProductDetails details = new ProductDetails(d.slug, d.sku, d.description, d.brand, d.mrp, d.taxRate, d.hsn,
                ProductStatus.fromStored(d.status), d.tags, d.images, d.categoryId);
        return new Product(d.id, d.name, d.photo, d.price == null ? 0 : d.price, d.stock == null ? 0 : d.stock,
                d.category, d.createdAt, details);
    }
}
