package com.sixpack.marketing.domain;

/** Why a queued purchase was retried, failed or skipped. The message is shown to the store owner. */
public enum DeliveryCode {
    TIMEOUT("%s did not answer in time. We will try again."),
    RATE_LIMITED("%s asked us to slow down. We will try again."),
    PROVIDER_DOWN("%s had a temporary problem. We will try again."),
    AUTH_FAILED("%s rejected the saved secret. Create a new one and save it here."),
    REJECTED("%s rejected the event"),
    SECRET_UNREADABLE("The saved secret can no longer be read. Save it again."),
    GAVE_UP("%s could not be reached after 6 attempts, so this purchase was not sent."),
    NO_CONSENT("The shopper did not allow this, so nothing was sent."),
    SERVER_OFF("Server events were off or not set up, so nothing was sent."),
    TOO_OLD("The purchase is older than %s accepts, so it was not sent."),
    ORDER_GONE("The order no longer exists, so nothing was sent.");

    private final String template;

    DeliveryCode(String template) {
        this.template = template;
    }

    /** Transient: the row is tried again later. Everything else is final. */
    public boolean retryable() {
        return this == TIMEOUT || this == RATE_LIMITED || this == PROVIDER_DOWN;
    }

    public String message(Provider provider) {
        return String.format(template, provider == Provider.META ? "Meta" : "Google");
    }
}
