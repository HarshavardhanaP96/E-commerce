package com.sixpack.consent;

/**
 * What a user can agree to. Terms and Privacy are required to hold an account; the admin agreement is required
 * only for admins; marketing is optional.
 */
public enum Purpose {
    TERMS(true, false), PRIVACY(true, false), MARKETING_EMAIL(false, false), ADMIN_AUP(true, true);

    private final boolean required;
    private final boolean adminOnly;

    Purpose(boolean required, boolean adminOnly) {
        this.required = required;
        this.adminOnly = adminOnly;
    }

    /** Required to hold the account (for whoever it applies to), so it cannot be withdrawn. */
    public boolean required() {
        return required;
    }

    /** Only an admin can grant it. */
    public boolean adminOnly() {
        return adminOnly;
    }

    /** Whether this account must hold a current grant: admin-only purposes bind admins and nobody else. */
    public boolean requiredFor(boolean admin) {
        return required && (admin || !adminOnly);
    }

    public String wireValue() {
        return name().toLowerCase();
    }

    public static Purpose fromWire(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
