package com.sixpack.user.domain;

public enum Role {
    ADMIN, USER;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static Role fromWire(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
