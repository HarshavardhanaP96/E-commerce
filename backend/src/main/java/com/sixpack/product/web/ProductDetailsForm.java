package com.sixpack.product.web;

import com.sixpack.product.application.ProductService.DetailsCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Optional multipart fields shared by product create and update. */
record ProductDetailsForm(
        @Size(max = 80) String slug,
        @Size(max = 60) String sku,
        @Size(max = 5000) String description,
        @Size(max = 60) String brand,
        @PositiveOrZero @DecimalMax("10000000") Double mrp,
        @DecimalMin("0") @DecimalMax("100") Double taxRate,
        @Pattern(regexp = "\\d{4,8}|") String hsn,
        @Pattern(regexp = "(?i)draft|active|archived|") String status,
        @Size(max = 20) List<@Size(max = 30) String> tags,
        @Size(max = 64) String categoryId) {

    DetailsCommand toCommand() {
        return new DetailsCommand(slug, sku, description, brand, mrp, taxRate, hsn, status, tags, categoryId);
    }
}
