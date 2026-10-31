package com.sixpack.marketing.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The store's marketing settings that belong to no single provider: the product feed. {@code feedToken} is the
 * secret part of the feed's address; treat it as a credential (never log, audit or export it).
 */
public record MarketingSettings(boolean feedEnabled, String feedToken) {

    public static final MarketingSettings NONE = new MarketingSettings(false, null);

    /** Whether {@code token} opens this store's feed. Compares in constant time. */
    public boolean opensFeed(String token) {
        return feedEnabled && feedToken != null && token != null && MessageDigest.isEqual(
                feedToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "MarketingSettings[feedEnabled=" + feedEnabled + ", feedToken=" + (feedToken == null ? null : "***")
                + "]";
    }
}
