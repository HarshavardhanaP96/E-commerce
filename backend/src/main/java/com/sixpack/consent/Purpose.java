package com.sixpack.consent;

/** What a user can agree to. Terms and Privacy are required to hold an account; the rest are optional. */
public enum Purpose {
    TERMS(true), PRIVACY(true), MARKETING_EMAIL(false);

    private final boolean required;

    Purpose(boolean required) {
        this.required = required;
    }

    public boolean required() {
        return required;
    }

    public String wireValue() {
        return name().toLowerCase();
    }

    public static Purpose fromWire(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
