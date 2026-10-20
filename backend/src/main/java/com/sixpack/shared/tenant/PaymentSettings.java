package com.sixpack.shared.tenant;

/**
 * A tenant's own payment account. The secret itself is never stored with the tenant: {@code secretRef} names an
 * environment entry ({@code TENANT_SECRET_<REF>}) that holds it, so secrets stay in the sealed-secret store.
 *
 * @param provider {@code razorpay}, or {@code fake} outside production
 */
public record PaymentSettings(String provider, String keyId, String secretRef) {

    public static final PaymentSettings NONE = new PaymentSettings(null, null, null);

    public PaymentSettings {
        provider = LegalSettings.blankToNull(provider);
        keyId = LegalSettings.blankToNull(keyId);
        secretRef = LegalSettings.blankToNull(secretRef);
    }

    public boolean configured() {
        return provider != null && ("fake".equals(provider) || keyId != null && secretRef != null);
    }
}
