package com.sixpack.product.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param variantsEnabled gate for creating product variants; an off switch for operators now that checkout, orders,
 *                        the storefront and the admin editor all support them
 *                        (docs/superpowers/plans/2026-10-18-product-variants.md).
 * @param lowStockThreshold store-wide "running low" level for products that do not set their own
 */
@ConfigurationProperties("app.products")
public record ProductProperties(int pageSize, boolean variantsEnabled, int lowStockThreshold) {

    public ProductProperties {
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }
        if (lowStockThreshold < 0) {
            lowStockThreshold = 5;
        }
    }
}
