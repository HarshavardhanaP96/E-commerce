package com.sixpack.marketing.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** SHA-256 (lower-case hex) of normalised email and phone, as Meta's customer-information rules ask. */
final class PiiHasher {

    private PiiHasher() {
    }

    static String email(String raw) {
        return raw == null || raw.isBlank() ? null : sha256(raw.strip().toLowerCase(Locale.ROOT));
    }

    /** Digits only, no leading zeros, with the country code; a 10-digit number without one gets {@code callingCode}. */
    static String phone(String raw, String callingCode) {
        if (raw == null) {
            return null;
        }
        boolean international = raw.strip().startsWith("+");
        String digits = raw.replaceAll("\\D", "").replaceFirst("^0+", "");
        if (!international && digits.length() == 10) {
            digits = callingCode + digits;
        }
        return digits.length() < 8 ? null : sha256(digits);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM has SHA-256
        }
    }
}
