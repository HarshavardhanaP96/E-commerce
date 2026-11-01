package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers marketing_settings so offboarding exports (without the token) and purges it with the rest of the store. */
@Configuration
class FeedBackfill {

    @Bean
    BackfillTarget marketingSettingsBackfill() {
        return () -> "marketing_settings";
    }
}
