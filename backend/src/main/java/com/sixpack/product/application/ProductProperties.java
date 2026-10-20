package com.sixpack.product.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param variantsEnabled platform default for the {@code variants} feature (see {@code app.features.defaults}).
 *                        A store can override it; the gate itself reads {@link com.sixpack.shared.tenant.TenantFeatures}.
 */
@ConfigurationProperties("app.products")
public record ProductProperties(int pageSize, boolean variantsEnabled) {

    public ProductProperties {
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }
    }
}
