package com.sixpack.audit.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AuditBackfill {

    @Bean
    BackfillTarget auditLogBackfill() {
        return () -> "audit_log";
    }
}
