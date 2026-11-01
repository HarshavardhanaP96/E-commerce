package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the collection so offboarding exports and purges it with the rest of the store. */
@Configuration
class MarketingBackfill {

    @Bean
    BackfillTarget marketingIntegrationsBackfill() {
        return () -> "marketing_integrations";
    }
}
