package com.sixpack.user.infra.mongo;

import com.sixpack.shared.tenant.BackfillTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IdentityBackfill {

    @Bean
    BackfillTarget usersBackfill() {
        return () -> "users";
    }

    @Bean
    BackfillTarget passwordResetTokensBackfill() {
        return () -> "password_reset_tokens";
    }

    @Bean
    BackfillTarget emailVerificationTokensBackfill() {
        return () -> "email_verification_tokens";
    }
}
