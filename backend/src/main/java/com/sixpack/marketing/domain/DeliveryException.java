package com.sixpack.marketing.domain;

import java.time.Duration;

/**
 * A provider call that did not succeed. The exception message is only the code: provider text (which might echo a
 * request) travels in {@code detail}, already cleaned, and is shown to the owner but never logged.
 */
public class DeliveryException extends RuntimeException {

    private final DeliveryCode code;
    private final String detail;
    private final Duration retryAfter;

    public DeliveryException(DeliveryCode code, String detail, Duration retryAfter) {
        super(code.name(), null, false, false);
        this.code = code;
        this.detail = detail;
        this.retryAfter = retryAfter;
    }

    public DeliveryCode code() {
        return code;
    }

    /** The provider's Retry-After, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public String ownerMessage(Provider provider) {
        return detail == null ? code.message(provider) : code.message(provider) + ": " + detail;
    }
}
