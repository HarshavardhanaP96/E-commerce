package com.sixpack.product.domain;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** How an option axis is presented: plain text chips (size, material) or colour swatches. */
public enum OptionType {
    TEXT, COLOUR;

    public static OptionType fromStored(String stored) {
        return "COLOUR".equals(stored) ? COLOUR : TEXT;
    }

    /** Strict parse of user input; blank means TEXT, and "color" is accepted for COLOUR. */
    public static OptionType parse(String input) {
        if (input == null || input.isBlank()) {
            return TEXT;
        }
        return switch (input.trim().toUpperCase(Locale.ROOT)) {
            case "TEXT" -> TEXT;
            case "COLOUR", "COLOR" -> COLOUR;
            default -> throw ApiException.badRequest("type: must be one of TEXT, COLOUR");
        };
    }
}
