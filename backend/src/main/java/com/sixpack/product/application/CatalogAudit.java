package com.sixpack.product.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.product.domain.Category;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the audit log may say about products and categories. A field not named in a policy is never logged, so
 * adding a field to {@link Product} does not change the log until it is added here on purpose.
 */
final class CatalogAudit {

    static final FieldPolicy PRODUCT = Changes.policy(List.of("name", "price", "stock", "category", "photo", "slug",
            "sku", "brand", "mrp", "taxRate", "hsn", "status", "tags", "categoryId"), List.of());

    static final FieldPolicy CATEGORY = Changes.policy(List.of("name", "slug", "parentId", "image"), List.of());

    private CatalogAudit() {
    }

    static Map<String, Object> view(Product p) {
        ProductDetails d = p.details();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("price", p.price());
        m.put("stock", p.stock());
        m.put("category", p.category());
        m.put("photo", p.photo());
        m.put("slug", d.slug());
        m.put("sku", d.sku());
        m.put("brand", d.brand());
        m.put("mrp", d.mrp());
        m.put("taxRate", d.taxRate());
        m.put("hsn", d.hsn());
        m.put("status", d.status() == null ? null : d.status().name());
        m.put("tags", d.tags());
        m.put("categoryId", d.categoryId());
        return m;
    }

    static Map<String, Object> view(Category c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("slug", c.slug());
        m.put("parentId", c.parentId());
        m.put("image", c.image());
        return m;
    }
}
