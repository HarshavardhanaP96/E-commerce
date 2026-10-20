package com.sixpack.product.infra.mongo;

import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.product.domain.Category;
import com.sixpack.product.domain.CategoryRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;

import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoCategoryRepository implements CategoryRepository {

    private final TenantMongo mongo;

    MongoCategoryRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Category> findById(String id) {
        return mongo.findById(id, CategoryDocument.class).map(MongoCategoryRepository::toDomain);
    }

    @Override
    public Optional<Category> findBySlug(String slug) {
        return mongo.findOne(Query.query(Criteria.where("slug").is(slug)), CategoryDocument.class)
                .map(MongoCategoryRepository::toDomain);
    }

    @Override
    public List<Category> findAll() {
        return mongo.find(new Query().with(Sort.by("createdAt", "name")), CategoryDocument.class).stream()
                .map(MongoCategoryRepository::toDomain).toList();
    }

    @Override
    public boolean existsByParentId(String parentId) {
        return mongo.exists(Query.query(Criteria.where("parentId").is(parentId)), CategoryDocument.class);
    }

    @Override
    public Category save(Category c) {
        CategoryDocument doc = c.id() == null ? null : mongo.findById(c.id(), CategoryDocument.class).orElse(null);
        if (doc == null) {
            doc = new CategoryDocument();
        }
        doc.id = c.id();
        doc.name = c.name();
        doc.slug = c.slug();
        doc.parentId = c.parentId();
        doc.image = c.image();
        return toDomain(mongo.save(doc));
    }

    @Override
    public void deleteById(String id) {
        mongo.remove(Query.query(Criteria.where("id").is(id)), CategoryDocument.class);
    }

    private static Category toDomain(CategoryDocument d) {
        return new Category(d.id, d.name, d.slug, d.parentId, d.image, d.createdAt);
    }
}
