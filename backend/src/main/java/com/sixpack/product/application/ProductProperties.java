package com.sixpack.product.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param variantsEnabled platform default for the {@code variants} feature (see {@code app.features.defaults}); on by
 *                        default now that checkout, orders, the storefront and the admin editor all support variants.
 *                        A store can override it; the gate itself reads {@link com.sixpack.shared.tenant.TenantFeatures}.
 */
@ConfigurationProperties("app.products")
public record ProductProperties(boolean variantsEnabled) {
}
