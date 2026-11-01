package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One provider's settings for one store (spec §5.1): public browser tag IDs and sealed server-side settings. */
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

    static class Server {
        Boolean enabled;
        String testEventCode;
    }

    static class Sealed {
        String keyId;
        String iv;
        String ciphertext;
    }

    /** Top-level so the tenant export can drop the whole field (TenantExport.SKIPPED_FIELDS). */
    static class Secrets {
        Sealed server;
    }

    static class LastErrorDoc {
        Instant at;
        String code;
        String message;
    }

    Server server;
    Secrets secrets;
    LastErrorDoc lastError;
    Instant lastSuccessAt;

    @Id
    String id;
    /** {@code Provider} enum name. */
    String provider;
    Browser browser;
    Instant updatedAt;
}
