package com.sixpack.marketing.domain;

/** A provider's server-side API (spec §4.2). Adapters call fixed hosts only. */
public interface MarketingDestination {

    Provider provider();

    /** @throws DeliveryException with a retryable code (5xx, 429, timeout) or a final one (4xx) */
    void send(Credentials credentials, Conversion conversion);

    /** Sends one synthetic purchase to the provider's test channel and reports its answer; never throws for provider errors. */
    TestVerdict test(Credentials credentials, String testEventCode, String currency);

    /** {@code id} is the pixel or measurement ID. {@link #toString()} hides the secret. */
    record Credentials(String id, String secret) {
        @Override
        public String toString() {
            return "Credentials[id=" + id + ", secret=***]";
        }
    }

    record TestVerdict(boolean ok, String code, String message) {
    }
}
