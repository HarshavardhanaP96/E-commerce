package com.sixpack.product.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param variantsEnabled platform default for the {@code variants} feature (see {@code app.features.defaults}); on by
 *                        default now that checkout, orders, the storefront and the admin editor all support variants.
 *                        A store can override it; the gate itself reads {@link com.sixpack.shared.tenant.TenantFeatures}.
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
