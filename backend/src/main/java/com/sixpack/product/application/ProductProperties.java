package com.sixpack.product.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param variantsEnabled gate for creating product variants; an off switch for operators now that checkout, orders,
 *                        the storefront and the admin editor all support them
 *                        (docs/superpowers/plans/2026-10-18-product-variants.md).
 */
@ConfigurationProperties("app.products")
public record ProductProperties(int pageSize, boolean variantsEnabled) {

    public ProductProperties {
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }
    }
}
