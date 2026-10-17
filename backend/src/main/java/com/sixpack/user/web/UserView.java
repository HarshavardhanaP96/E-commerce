package com.sixpack.user.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.user.domain.User;
import java.time.Instant;
import java.time.LocalDate;

/** Public user representation. Never includes the password hash. */
record UserView(
        @JsonProperty("_id") String id,
        String name,
        String email,
        String photo,
        String role,
        String gender,
        LocalDate dob,
        int age,
        Instant createdAt,
        String phone,
        String locale,
        boolean emailVerified,
        String status,
        Instant lastLoginAt) {

    static UserView from(User u) {
        return new UserView(u.id(), u.name(), u.email(), u.photo(), u.role().wireValue(),
                u.gender() == null ? null : u.gender().wireValue(), u.dob(), u.age(LocalDate.now()), u.createdAt(),
                u.details().phone(), u.details().locale(), u.details().emailVerified(),
                u.details().status().name(), u.details().lastLoginAt());
    }
}
