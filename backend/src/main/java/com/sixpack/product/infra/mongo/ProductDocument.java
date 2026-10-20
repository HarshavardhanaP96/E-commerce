package com.sixpack.product.infra.mongo;

import java.time.Instant;
import java.util.List;
import com.sixpack.shared.tenant.TenantDocument;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("products")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_slug", def = "{'tenantId': 1, 'slug': 1}", unique = true,
                partialFilter = "{'slug': {$type: 'string'}}"),
        @CompoundIndex(name = "tenant_sku", def = "{'tenantId': 1, 'sku': 1}", unique = true,
                partialFilter = "{'sku': {$type: 'string'}}"),
        @CompoundIndex(name = "tenant_variant_sku", def = "{'tenantId': 1, 'variants.items.sku': 1}", unique = true,
                partialFilter = "{'variants.items.sku': {$type: 'string'}}")
})
class ProductDocument extends TenantDocument {

    @Id
    String id;
    String name;
    String photo;
    Double price;
    Integer stock;
    @Indexed
    String category;
    String slug;
    String sku;
    String description;
    String brand;
    Double mrp;
    Double taxRate;
    String hsn;
    @Indexed
    String status;
    List<String> tags;
    List<String> images;
    @Indexed
    String categoryId;
    String countryOfOrigin;
    Integer lowStockThreshold;
    SizeChartDoc sizeChart;
    /** Absent for simple and legacy products. Only written through the variant operations. */
    VariantsDoc variants;
    /** Bumped by every stock change so a variant-structure write can detect a concurrent sale. */
    Long rev;
    @CreatedDate
    @Indexed
    Instant createdAt;
    @LastModifiedDate
    Instant updatedAt;

    static class VariantsDoc {
        List<AxisDoc> axes;
        List<VariantDoc> items;
    }

    static class AxisDoc {
        String name;
        String type;
        List<ValueDoc> values;
    }

    static class ValueDoc {
        String label;
        String hex;
        List<String> images;
    }

    /** The id is stored as {@code variantId}: a field called {@code id} would be mapped to {@code _id}. */
    static class VariantDoc {
        String variantId;
        String sku;
        List<ChoiceDoc> options;
        Double price;
        Double mrp;
        Integer stock;
        String status;
    }

    static class ChoiceDoc {
        String name;
        String value;
    }

    static class SizeChartDoc {
        List<String> columns;
        List<List<String>> rows;
        String note;
    }
}
