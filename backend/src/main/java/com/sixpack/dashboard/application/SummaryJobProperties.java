package com.sixpack.dashboard.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The nightly daily-summary run ({@code app.analytics.summary.*}).
 *
 * @param cron         when it runs, in the server's zone (default 02:30 every day)
 * @param recentDays   days before today recomputed on every run, because orders change after the fact
 * @param startupDelay one extra run this long after startup, so a new or restarted server fills its table
 */
@ConfigurationProperties("app.analytics.summary")
public record SummaryJobProperties(String cron, Integer recentDays, Duration startupDelay) {

    public SummaryJobProperties {
        cron = cron == null || cron.isBlank() ? "0 30 2 * * *" : cron;
        recentDays = recentDays == null || recentDays < 1 ? 30 : recentDays;
        startupDelay = startupDelay == null ? Duration.ofMinutes(1) : startupDelay;
    }
}
