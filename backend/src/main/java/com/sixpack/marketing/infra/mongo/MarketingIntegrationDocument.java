package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One provider's settings for one store (spec §5.1). Phase 1 stores only the public browser tag IDs. */
@Document("marketing_integrations")
@CompoundIndex(name = "tenant_provider", def = "{'tenantId': 1, 'provider': 1}", unique = true)
class MarketingIntegrationDocument extends TenantDocument {

    static class Browser {
        String ga4MeasurementId;
        String adsConversionId;
        String adsPurchaseLabel;
        String gtmContainerId;
        String pixelId;
    }

    @Id
    String id;
    /** {@code Provider} enum name. */
    String provider;
    Browser browser;
    Instant updatedAt;
}
