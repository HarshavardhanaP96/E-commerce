package com.sixpack.product.application;

import com.sixpack.product.domain.Category;
import com.sixpack.product.domain.CategoryRepository;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.Slugs;
import com.sixpack.shared.ApiException;
import com.sixpack.storage.FileStorage;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CategoryService {

    public record CategoryCommand(String name, String parentId, byte[] image) {
    }

    private static final int MAX_DEPTH = 20;

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final FileStorage storage;

    CategoryService(CategoryRepository categories, ProductRepository products, FileStorage storage) {
        this.categories = categories;
        this.products = products;
        this.storage = storage;
    }

    public List<Category> all() {
        return categories.findAll();
    }

    public Category get(String id) {
        return categories.findById(id).orElseThrow(() -> ApiException.notFound("Category not found"));
    }

    public Category create(CategoryCommand cmd) {
        String parentId = blankToNull(cmd.parentId());
        if (parentId != null) {
            requireExists(parentId);
        }
        String name = cmd.name().trim();
        String image = cmd.image() == null ? null : storage.storeImage(cmd.image());
        try {
            return categories.save(new Category(null, name, freeSlug(Slugs.of(name)), parentId, image, null));
        } catch (RuntimeException e) {
            if (image != null) {
                storage.delete(image);
            }
            throw e;
        }
    }

    public Category update(String id, CategoryCommand cmd) {
        Category current = get(id);
        String parentId = blankToNull(cmd.parentId());
        if (parentId != null) {
            requireExists(parentId);
            assertNoCycle(id, parentId);
        } else {
            parentId = current.parentId();
        }
        String name = blankToNull(cmd.name()) != null ? cmd.name().trim() : current.name();
        String image = cmd.image() != null ? storage.storeImage(cmd.image()) : current.image();
        Category saved = categories.save(new Category(id, name, current.slug(), parentId, image, current.createdAt()));
        if (current.image() != null && !current.image().equals(image)) {
            storage.delete(current.image());
        }
        return saved;
    }

    public void delete(String id) {
        Category current = get(id);
        if (categories.existsByParentId(id)) {
            throw ApiException.conflict("Category has sub-categories");
        }
        if (products.existsByCategoryId(id)) {
            throw ApiException.conflict("Category is used by products");
        }
        categories.deleteById(id);
        if (current.image() != null) {
            storage.delete(current.image());
        }
    }

    private void requireExists(String parentId) {
        if (categories.findById(parentId).isEmpty()) {
            throw ApiException.badRequest("parentId: category not found");
        }
    }

    /** Walks up from the proposed parent; reaching {@code id} (or an absurdly deep chain) is a cycle. */
    private void assertNoCycle(String id, String newParentId) {
        String cursor = newParentId;
        int hops = 0;
        while (cursor != null) {
            if (cursor.equals(id) || ++hops > MAX_DEPTH) {
                throw ApiException.badRequest("parentId: would create a cycle");
            }
            cursor = categories.findById(cursor).map(Category::parentId).orElse(null);
        }
    }

    private String freeSlug(String base) {
        String candidate = base;
        for (int n = 2; categories.findBySlug(candidate).isPresent(); n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
