package com.sixpack.tenant.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs: the offboarding purge ({@link TenantPurge}) and trial expiry ({@link TenantTrials}). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
