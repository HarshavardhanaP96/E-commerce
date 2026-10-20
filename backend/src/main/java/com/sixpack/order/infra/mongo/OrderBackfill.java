package com.sixpack.order.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OrderBackfill {

    @Bean
    BackfillTarget ordersBackfill() {
        return () -> "orders";
    }

    @Bean
    BackfillTarget paymentIntentsBackfill() {
        return () -> "payment_intents";
    }
}
