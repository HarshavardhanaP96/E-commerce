package com.sixpack.marketing.domain;

import java.util.Arrays;
import java.util.Optional;

/** A company the store sends data to: one admin card each, in this order. {@link #key()} is the URL/JSON name. */
public enum Provider {
    GOOGLE("google"),
    META("meta");

    private final String key;

    Provider(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<Provider> fromKey(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
    }
}
