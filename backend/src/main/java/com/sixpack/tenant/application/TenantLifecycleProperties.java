package com.sixpack.tenant.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param deletionGrace how long an offboarded store can be restored before its data is purged (default 30 days)
 * @param purgeInterval how often the purge job looks for stores past their grace period (default hourly)
 * @param trialLength   how long a store created as a trial runs before it is suspended (default 14 days)
 */
@ConfigurationProperties("app.tenant")
public record TenantLifecycleProperties(Duration deletionGrace, Duration purgeInterval, Duration trialLength) {

    public TenantLifecycleProperties {
        deletionGrace = deletionGrace == null || deletionGrace.isNegative() ? Duration.ofDays(30) : deletionGrace;
        purgeInterval = purgeInterval == null || !purgeInterval.isPositive() ? Duration.ofHours(1) : purgeInterval;
        trialLength = trialLength == null || !trialLength.isPositive() ? Duration.ofDays(14) : trialLength;
    }
}
