package com.sixpack.tenant.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (today only the offboarding purge, {@link TenantPurge}). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
