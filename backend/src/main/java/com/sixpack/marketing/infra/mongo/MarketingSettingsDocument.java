package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** The store's feed settings (spec §5.2). */
@Document("marketing_settings")
class MarketingSettingsDocument extends TenantDocument {

    /** The store's id, so a store can never have two rows (a unique index on tenantId would clash with @Indexed). */
    @Id
    String id;
    boolean feedEnabled;
    /** A credential: never logged or exported. */
    String feedToken;
    Instant updatedAt;
}
