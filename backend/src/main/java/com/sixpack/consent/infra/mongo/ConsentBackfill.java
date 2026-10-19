package com.sixpack.consent.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ConsentBackfill {

    @Bean
    BackfillTarget consentsBackfill() {
        return () -> "consents";
    }
}
