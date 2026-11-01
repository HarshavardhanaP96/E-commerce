package com.sixpack.marketing.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform settings for marketing (application.yml {@code app.marketing}). {@code keys} is MARKETING_KEYS:
 * {@code k1:<base64 of 32 bytes>,k0:<older>}, newest first; empty means server-side conversions are unavailable.
 */
@ConfigurationProperties("app.marketing")
public record MarketingProperties(String keys, String piiConsentVersion, String callingCode, Duration breakerOpenFor,
                                  Meta meta, Google google, Http http) {

    public record Meta(String baseUrl, String apiVersion) {
    }

    public record Google(String baseUrl) {
    }

    public record Http(Duration connectTimeout, Duration readTimeout) {
    }
}
