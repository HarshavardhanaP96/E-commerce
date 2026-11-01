package com.sixpack.marketing.domain;

import java.time.Instant;

/** Why the provider last refused a purchase, in words the owner can act on. {@code code} is a {@link DeliveryCode} name. */
public record LastError(Instant at, String code, String message) {
}
