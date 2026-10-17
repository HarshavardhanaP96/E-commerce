package com.sixpack.user.domain;

import java.time.Instant;
import java.util.List;

/** Optional profile fields. Legacy users carry {@link #EMPTY}. */
public record UserDetails(
        String phone,
        String locale,
        boolean emailVerified,
        UserStatus status,
        Instant lastLoginAt,
        List<Address> addresses) {

    public static final UserDetails EMPTY = new UserDetails(null, null, false, null, null, null);

    public UserDetails {
        status = status == null ? UserStatus.ACTIVE : status;
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
    }
}
