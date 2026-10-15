package com.sixpack.user.domain;

public enum Gender {
    MALE, FEMALE;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static Gender fromWire(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
