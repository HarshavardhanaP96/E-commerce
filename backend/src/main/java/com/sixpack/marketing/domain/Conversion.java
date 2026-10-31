package com.sixpack.marketing.domain;

import java.time.Instant;
import java.util.List;

/** One purchase as a provider receives it. {@code eventId} is the order id, the browser's event id too. */
public record Conversion(String eventId, Instant at, String currency, double value, double tax, double shipping,
                         String coupon, List<Item> items, Identity identity) {

    public record Item(String id, String name, String variant, double price, int quantity) {
    }

    /**
     * What may identify the shopper, already reduced to what consent allows: hashes are SHA-256 hex of normalised
     * values (null when not allowed); {@code adConsent} is the marketing choice.
     */
    public record Identity(String emailSha256, String phoneSha256, String externalIdSha256, String fbp, String fbc,
                           String gaClientId, String userAgent, String sourceUrl, boolean adConsent) {
    }
}
