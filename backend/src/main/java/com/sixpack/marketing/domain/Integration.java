package com.sixpack.marketing.domain;

import java.time.Instant;

/**
 * One provider's saved settings for the store: the public browser tag IDs (phase 1) and the server-side settings
 * (phase 3). {@code lastError}/{@code lastSuccessAt} are written by the dispatcher only.
 */
public record Integration(Provider provider, BrowserTags browser, ServerSettings server, LastError lastError,
                          Instant lastSuccessAt, Instant updatedAt) {

    public enum Status { NOT_CONNECTED, ACTIVE, ERROR }

    public Integration {
        server = server == null ? ServerSettings.OFF : server;
    }

    /** Phase 1 shape, kept so existing callers compile unchanged. */
    public Integration(Provider provider, BrowserTags browser, Instant updatedAt) {
        this(provider, browser, ServerSettings.OFF, null, null, updatedAt);
    }

    public static Integration notConnected(Provider provider) {
        return new Integration(provider, BrowserTags.NONE, null);
    }

    /** A stored row always has an ID; ERROR while server sending is on and its last failure is newer than its last success. */
    public Status status() {
        if (browser.isEmpty()) {
            return Status.NOT_CONNECTED;
        }
        boolean failing = server.enabled() && lastError != null
                && (lastSuccessAt == null || lastError.at().isAfter(lastSuccessAt));
        return failing ? Status.ERROR : Status.ACTIVE;
    }

    /** The ID the server-side API addresses: the Meta Pixel ID or the GA4 measurement ID; null when not saved. */
    public String serverId() {
        return serverId(provider, browser);
    }

    public static String serverId(Provider provider, BrowserTags browser) {
        return provider == Provider.META ? browser.pixelId() : browser.ga4MeasurementId();
    }
}
