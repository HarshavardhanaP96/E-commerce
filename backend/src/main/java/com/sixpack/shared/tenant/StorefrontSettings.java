package com.sixpack.shared.tenant;

/**
 * What a store shows about itself on its storefront and legal pages. Public by design: it appears on the
 * Terms, Privacy and Contact pages. A null field means "not set", and the storefront keeps its placeholder.
 */
public record StorefrontSettings(String logoUrl, String legalName, String address, String email, String phone,
                                 String grievanceOfficer, String grievanceEmail) {

    public static final StorefrontSettings NONE = new StorefrontSettings(null, null, null, null, null, null, null);

    public StorefrontSettings {
        logoUrl = LegalSettings.blankToNull(logoUrl);
        legalName = LegalSettings.blankToNull(legalName);
        address = LegalSettings.blankToNull(address);
        email = LegalSettings.blankToNull(email);
        phone = LegalSettings.blankToNull(phone);
        grievanceOfficer = LegalSettings.blankToNull(grievanceOfficer);
        grievanceEmail = LegalSettings.blankToNull(grievanceEmail);
    }
}
