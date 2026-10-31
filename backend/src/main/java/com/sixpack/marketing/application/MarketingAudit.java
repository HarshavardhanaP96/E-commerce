package com.sixpack.marketing.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.marketing.domain.BrowserTags;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the audit log may say about an integration. Public tag IDs are logged with values; secrets (phase 3) never. */
final class MarketingAudit {

    static final String ENTITY = "marketing_integration";

    static final FieldPolicy POLICY = Changes.policy(List.of("ga4MeasurementId", "adsConversionId",
            "adsPurchaseLabel", "gtmContainerId", "pixelId"), List.of());

    private MarketingAudit() {
    }

    static Map<String, Object> view(BrowserTags t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ga4MeasurementId", t.ga4MeasurementId());
        m.put("adsConversionId", t.adsConversionId());
        m.put("adsPurchaseLabel", t.adsPurchaseLabel());
        m.put("gtmContainerId", t.gtmContainerId());
        m.put("pixelId", t.pixelId());
        return m;
    }
}
