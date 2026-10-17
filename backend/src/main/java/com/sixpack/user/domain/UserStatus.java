package com.sixpack.user.domain;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** Account state. SUSPENDED users cannot log in. */
public enum UserStatus {
    ACTIVE, SUSPENDED;

    /** Lenient read of a stored value: legacy documents without a status are ACTIVE. */
    public static UserStatus fromStored(String stored) {
        if (stored == null) {
            return ACTIVE;
        }
        try {
            return valueOf(stored);
        } catch (IllegalArgumentException e) {
            return ACTIVE;
        }
    }

    /** Strict parse of user input. */
    public static UserStatus parse(String input) {
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("status: must be one of ACTIVE, SUSPENDED");
        }
    }
}
