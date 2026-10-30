package com.sixpack.shared.tenant;

/**
 * The public tag IDs the storefront loads for the store in {@link TenantContext}. Lives here so the {@code tenant}
 * module can ship them in {@code /tenant/config} without depending on {@code marketing}, which implements it.
 */
public interface MarketingTags {

    /** Never null; all fields null when the store saved nothing. Callers check {@link Feature#MARKETING} first. */
    Tags current();

    /** Field names are the JSON keys of {@code tenant.marketing.tags}. */
    record Tags(String ga4, String adsId, String adsPurchaseLabel, String gtm, String metaPixel) {

        public static final Tags NONE = new Tags(null, null, null, null, null);

        /** Not {@code isEmpty()}: Jackson would serialise that getter as an extra "empty" key. */
        public boolean hasAny() {
            return !equals(NONE);
        }
    }
}
