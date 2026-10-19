package com.sixpack.product.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documents that predate multi-tenancy are given to the default tenant at startup. */
@Configuration
class CatalogueBackfill {

    @Bean
    BackfillTarget productsBackfill() {
        return () -> "products";
    }

    @Bean
    BackfillTarget categoriesBackfill() {
        return () -> "categories";
    }
}
