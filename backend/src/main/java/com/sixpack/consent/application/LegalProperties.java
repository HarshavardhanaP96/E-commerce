package com.sixpack.consent.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param termsVersion   version (effective date) of the Terms users must have accepted. Bump only for a
 *                       material change: every user is asked to accept again on their next visit.
 * @param privacyVersion same for the Privacy Policy
 * @param ipHashSalt     salt for hashing the IP stored as consent evidence, so the raw IP is never kept
 */
@ConfigurationProperties("app.legal")
public record LegalProperties(String termsVersion, String privacyVersion, String ipHashSalt) {
}
