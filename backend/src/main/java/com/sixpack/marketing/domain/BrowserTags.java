package com.sixpack.marketing.domain;

import java.util.regex.Pattern;

/**
 * The public tag IDs a store pastes into Admin → Marketing. Each one is later written into a {@code <script>} call
 * on the storefront, so it must match a strict pattern (spec §5.1) before it is kept. Values are stripped; blank
 * means "not set" (null). Google uses the first four fields, Meta only {@code pixelId}.
 */
public record BrowserTags(String ga4MeasurementId, String adsConversionId, String adsPurchaseLabel,
                          String gtmContainerId, String pixelId) {

    // Declared before NONE: static fields initialise in order and NONE runs the constructor.
    private static final Pattern GA4 = Pattern.compile("^G-[A-Z0-9]{4,12}$");
    private static final Pattern ADS = Pattern.compile("^AW-\\d{6,12}$");
    private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private static final Pattern GTM = Pattern.compile("^GTM-[A-Z0-9]{4,10}$");
    private static final Pattern PIXEL = Pattern.compile("^\\d{10,20}$");

    public static final BrowserTags NONE = new BrowserTags(null, null, null, null, null);

    public BrowserTags {
        ga4MeasurementId = check("ga4MeasurementId", ga4MeasurementId, GA4, "must look like G-XXXXXXX");
        adsConversionId = check("adsConversionId", adsConversionId, ADS, "must look like AW-123456789");
        adsPurchaseLabel = check("adsPurchaseLabel", adsPurchaseLabel, LABEL, "use 1-40 letters, digits, _ or -");
        gtmContainerId = check("gtmContainerId", gtmContainerId, GTM, "must look like GTM-XXXXXX");
        pixelId = check("pixelId", pixelId, PIXEL, "must be 10-20 digits");
        if (adsPurchaseLabel != null && adsConversionId == null) {
            throw new IllegalArgumentException("adsPurchaseLabel: needs adsConversionId");
        }
    }

    /** Only the fields that belong to {@code provider}; the other provider's are dropped without being checked. */
    public static BrowserTags of(Provider provider, String ga4MeasurementId, String adsConversionId,
                                 String adsPurchaseLabel, String gtmContainerId, String pixelId) {
        return switch (provider) {
            case GOOGLE -> new BrowserTags(ga4MeasurementId, adsConversionId, adsPurchaseLabel, gtmContainerId, null);
            case META -> new BrowserTags(null, null, null, null, pixelId);
        };
    }

    public boolean isEmpty() {
        return equals(NONE);
    }

    private static String check(String field, String value, Pattern pattern, String reason) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (!pattern.matcher(stripped).matches()) {
            throw new IllegalArgumentException(field + ": " + reason);
        }
        return stripped;
    }
}
