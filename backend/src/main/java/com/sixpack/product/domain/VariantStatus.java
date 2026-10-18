package com.sixpack.product.domain;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** Whether a variant can be bought. Disabled variants stay in the catalogue record but are not sellable. */
public enum VariantStatus {
    ACTIVE, DISABLED;

    public boolean isSellable() {
        return this == ACTIVE;
    }

    public static VariantStatus fromStored(String stored) {
        if (stored == null) {
            return ACTIVE;
        }
        try {
            return valueOf(stored);
        } catch (IllegalArgumentException e) {
            return ACTIVE;
        }
    }

    /** Strict parse of user input; blank means the default (ACTIVE). */
    public static VariantStatus parse(String input) {
        if (input == null || input.isBlank()) {
            return ACTIVE;
        }
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("status: must be one of ACTIVE, DISABLED");
        }
    }
}
