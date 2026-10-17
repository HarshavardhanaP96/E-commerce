package com.sixpack.product.domain;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** Lifecycle of a catalogue entry. Only ACTIVE products are listed publicly or purchasable. */
public enum ProductStatus {
    DRAFT, ACTIVE, ARCHIVED;

    public boolean isPublic() {
        return this == ACTIVE;
    }

    /** Lenient read of a stored value: legacy documents without a status are ACTIVE. */
    public static ProductStatus fromStored(String stored) {
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
    public static ProductStatus parse(String input) {
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("status: must be one of DRAFT, ACTIVE, ARCHIVED");
        }
    }
}
