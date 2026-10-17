package com.sixpack.order.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

/** Human-friendly order references like {@code ORD-20261017-K7M3QX}. Characters that read alike (I, L, O, 0, 1) are left out. */
public final class OrderNumbers {
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final int SUFFIX_LENGTH = 6;

    private OrderNumbers() {
    }

    public static String generate(Instant now, RandomGenerator random) {
        StringBuilder number = new StringBuilder("ORD-").append(DAY.format(now)).append('-');
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            number.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return number.toString();
    }
}
