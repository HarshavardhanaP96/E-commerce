package com.sixpack.shared.tenant;

/**
 * Policy versions a tenant requires its users to have accepted. A null or blank field means "use the platform
 * default from {@code app.legal.*}".
 */
public record LegalSettings(String termsVersion, String privacyVersion, String adminAupVersion) {

    public static final LegalSettings NONE = new LegalSettings(null, null, null);

    public LegalSettings {
        termsVersion = blankToNull(termsVersion);
        privacyVersion = blankToNull(privacyVersion);
        adminAupVersion = blankToNull(adminAupVersion);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
