package com.sixpack.product.domain;

import java.text.Normalizer;
import java.util.Locale;

/** URL-safe slugs: lowercase ASCII letters and digits separated by single dashes, at most 80 characters. */
public final class Slugs {
    private static final int MAX_LENGTH = 80;

    private Slugs() {
    }

    public static String of(String text) {
        if (text == null) {
            return "item";
        }
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "item" : slug;
    }
}
