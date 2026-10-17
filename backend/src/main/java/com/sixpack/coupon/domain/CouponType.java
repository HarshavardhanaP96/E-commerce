package com.sixpack.coupon.domain;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** FLAT: {@code amount} rupees off. PERCENT: {@code amount} percent off, optionally capped. */
public enum CouponType {
    FLAT, PERCENT;

    /** Blank or null means FLAT (the only kind that existed before). */
    public static CouponType parse(String input) {
        if (input == null || input.isBlank()) {
            return FLAT;
        }
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("type: must be FLAT or PERCENT");
        }
    }
}
