package com.sixpack.shared.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, derived from the verified JWT. */
public record AuthUser(String id, boolean admin) {

    static final String ROLE_CLAIM = "role";

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(jwt.getSubject(), "ADMIN".equals(jwt.getClaimAsString(ROLE_CLAIM)));
    }
}
