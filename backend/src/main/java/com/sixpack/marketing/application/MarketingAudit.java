package com.sixpack.marketing.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.marketing.domain.BrowserTags;
import com.sixpack.marketing.domain.Integration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the audit log may say about an integration. Public tag IDs are logged with values; the server secret only as
 * "changed", never with a value.
 */
final class MarketingAudit {

    static final String ENTITY = "marketing_integration";

    static final FieldPolicy POLICY = Changes.policy(List.of("ga4MeasurementId", "adsConversionId",
            "adsPurchaseLabel", "gtmContainerId", "pixelId", "serverEnabled", "testEventCode"), List.of("secret"));

    private MarketingAudit() {
    }

    static Map<String, Object> view(Integration i) {
        BrowserTags t = i.browser();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ga4MeasurementId", t.ga4MeasurementId());
        m.put("adsConversionId", t.adsConversionId());
        m.put("adsPurchaseLabel", t.adsPurchaseLabel());
        m.put("gtmContainerId", t.gtmContainerId());
        m.put("pixelId", t.pixelId());
        m.put("serverEnabled", i.server().enabled());
        m.put("testEventCode", i.server().testEventCode());
        // Compared only: "secret" is a personal field, so the entry says it changed and never holds a value.
        m.put("secret", i.server().hasSecret() ? i.server().secret().ciphertext() : null);
        return m;
    }
}
