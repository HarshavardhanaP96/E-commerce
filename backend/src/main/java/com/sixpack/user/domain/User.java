package com.sixpack.user.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;

public record User(
        String id,
        String name,
        String email,
        String passwordHash,
        String photo,
        Role role,
        Gender gender,
        LocalDate dob,
        Instant createdAt,
        UserDetails details) {

    public User {
        details = details == null ? UserDetails.EMPTY : details;
    }

    /** Pre-details shape, kept so existing callers compile unchanged. */
    public User(String id, String name, String email, String passwordHash, String photo, Role role, Gender gender,
                LocalDate dob, Instant createdAt) {
        this(id, name, email, passwordHash, photo, role, gender, dob, createdAt, UserDetails.EMPTY);
    }

    public static User newCustomer(String name, String email, String passwordHash, String photo, Gender gender,
                                   LocalDate dob) {
        return new User(null, name, email, passwordHash, photo, Role.USER, gender, dob, null);
    }

    public User withRole(Role newRole) {
        return new User(id, name, email, passwordHash, photo, newRole, gender, dob, createdAt, details);
    }

    public User withPasswordHash(String newHash) {
        return new User(id, name, email, newHash, photo, role, gender, dob, createdAt, details);
    }

    public User withDetails(UserDetails newDetails) {
        return new User(id, name, email, passwordHash, photo, role, gender, dob, createdAt, newDetails);
    }

    public int age(LocalDate today) {
        return dob == null ? 0 : Period.between(dob, today).getYears();
    }
}
