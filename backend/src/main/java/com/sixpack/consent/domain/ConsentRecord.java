package com.sixpack.consent.domain;

import com.sixpack.consent.Purpose;
import java.time.Instant;

/** One consent decision. Records are append-only: a withdrawal is a new record with {@code granted = false}. */
public record ConsentRecord(
        String id,
        String userId,
        Purpose purpose,
        boolean granted,
        String policyVersion,
        Source source,
        String ipHash,
        String userAgent,
        Instant createdAt) {

    public enum Source {
        REGISTER, REPROMPT, SETTINGS;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static Source fromWire(String value) {
            return valueOf(value.trim().toUpperCase());
        }
    }
}
