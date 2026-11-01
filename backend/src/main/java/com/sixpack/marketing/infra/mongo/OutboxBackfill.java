package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers marketing_outbox so offboarding purges it with the rest of the store (the export skips it). */
@Configuration
class OutboxBackfill {

    @Bean
    BackfillTarget marketingOutboxBackfill() {
        return () -> "marketing_outbox";
    }
}
