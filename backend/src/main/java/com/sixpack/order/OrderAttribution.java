package com.sixpack.order;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Where a paid order came from and what the shopper allowed (marketing spec §5.5). Public API of {@code order}: the
 * {@code marketing} module reads it through {@link PaidOrders}. Built only by {@link #from}, which enforces consent
 * server-side: campaign data, click IDs and the browser only with marketing consent; the GA client id only with
 * analytics consent.
 */
public record OrderAttribution(String utmSource, String utmMedium, String utmCampaign, String utmContent,
                               String utmTerm, String gclid, String fbclid, String fbp, String fbc, String gaClientId,
                               String landingPath, String userAgent, String sourceUrl, Consent consent) {

    /** The shopper's cookie choice at checkout; {@code at} is server time. */
    public record Consent(boolean analytics, boolean marketing, String version, Instant at) {
    }

    /** What the browser sent, unchecked. */
    public record Raw(String utmSource, String utmMedium, String utmCampaign, String utmContent, String utmTerm,
                      String gclid, String fbclid, String fbp, String fbc, String gaClientId, String landingPath,
                      Boolean analytics, Boolean marketing, String version) {
    }

    private static final Pattern CLICK_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,512}$");
    private static final Pattern FBP = Pattern.compile("^fb\\.\\d\\.\\d{1,15}\\.\\d{1,25}$");
    private static final Pattern FBC = Pattern.compile("^fb\\.\\d\\.\\d{1,15}\\.[A-Za-z0-9_.-]{1,512}$");
    private static final Pattern GA_CLIENT = Pattern.compile("^\\d{1,20}\\.\\d{1,20}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9.-]{1,20}$");

    /**
     * Never throws: a value that fails its check is dropped, so marketing data can never fail a checkout.
     *
     * @return null without a usable consent snapshot
     */
    public static OrderAttribution from(Raw raw, String userAgent, String sourceUrl, Instant now) {
        if (raw == null || raw.analytics() == null || raw.marketing() == null) {
            return null;
        }
        String version = match(raw.version(), VERSION);
        if (version == null) {
            return null;
        }
        boolean m = raw.marketing();
        boolean a = raw.analytics();
        return new OrderAttribution(
                m ? text(raw.utmSource(), 150) : null, m ? text(raw.utmMedium(), 150) : null,
                m ? text(raw.utmCampaign(), 150) : null, m ? text(raw.utmContent(), 150) : null,
                m ? text(raw.utmTerm(), 150) : null,
                m ? match(raw.gclid(), CLICK_ID) : null, m ? match(raw.fbclid(), CLICK_ID) : null,
                m ? match(raw.fbp(), FBP) : null, m ? match(raw.fbc(), FBC) : null,
                a ? match(raw.gaClientId(), GA_CLIENT) : null,
                a || m ? path(raw.landingPath()) : null,
                m ? text(userAgent, 512) : null, m ? sourceUrl : null,
                new Consent(a, m, version, now));
    }

    private static String match(String value, Pattern pattern) {
        String v = value == null ? null : value.strip();
        return v != null && pattern.matcher(v).matches() ? v : null;
    }

    /** Control characters become spaces, then strip; blank is absent; cut to {@code max}. */
    private static String text(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").strip();
        return v.isEmpty() ? null : v.length() > max ? v.substring(0, max) : v;
    }

    /** A path on this store only: one leading slash, no scheme or host. */
    private static String path(String value) {
        String v = text(value, 512);
        return v != null && v.startsWith("/") && !v.startsWith("//") ? v : null;
    }
}
