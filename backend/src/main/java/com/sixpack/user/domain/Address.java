package com.sixpack.user.domain;

/** A saved delivery address. {@code id} is a server-generated UUID, unique within one user's book. */
public record Address(String id, String label, String name, String phone, String line1, String line2, String city,
                      String state, String country, String pinCode, boolean isDefault) {

    public Address withId(String newId) {
        return new Address(newId, label, name, phone, line1, line2, city, state, country, pinCode, isDefault);
    }

    public Address withDefault(boolean value) {
        return new Address(id, label, name, phone, line1, line2, city, state, country, pinCode, value);
    }
}
