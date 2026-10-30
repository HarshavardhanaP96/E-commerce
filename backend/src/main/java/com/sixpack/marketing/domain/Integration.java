package com.sixpack.marketing.domain;

import java.time.Instant;

/** One provider's saved settings for the store. Phase 1 holds only the browser tag IDs. */
public record Integration(Provider provider, BrowserTags browser, Instant updatedAt) {

    public enum Status { NOT_CONNECTED, ACTIVE }

    public static Integration notConnected(Provider provider) {
        return new Integration(provider, BrowserTags.NONE, null);
    }

    /** A stored row always has at least one ID (saving none removes it), so having IDs means ACTIVE. */
    public Status status() {
        return browser.isEmpty() ? Status.NOT_CONNECTED : Status.ACTIVE;
    }
}
