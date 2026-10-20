package com.sixpack.shared.tenant;

/** How a tenant's emails link back to its storefront. A null base URL means "derive it from the tenant's domain". */
public record MailSettings(String baseUrl) {

    public static final MailSettings NONE = new MailSettings(null);

    public MailSettings {
        baseUrl = LegalSettings.blankToNull(baseUrl);
    }
}
