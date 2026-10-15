package com.sixpack.product.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.products")
public record ProductProperties(int pageSize) {

    public ProductProperties {
        if (pageSize <= 0 || pageSize > 100) {
            pageSize = 10;
        }
    }
}
