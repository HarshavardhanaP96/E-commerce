package com.sixpack.dashboard.application;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timezone the zone shop dashboards cut days in (default Asia/Kolkata). One platform value until each
 *                 store gets its own time zone setting.
 */
@ConfigurationProperties("app.analytics")
public record AnalyticsProperties(ZoneId timezone) {

    public AnalyticsProperties {
        timezone = timezone == null ? ZoneId.of("Asia/Kolkata") : timezone;
    }
}
