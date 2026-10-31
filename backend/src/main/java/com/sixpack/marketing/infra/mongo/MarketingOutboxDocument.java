package com.sixpack.marketing.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A purchase queued for one provider (spec §5.4, without the payload: it is built from the order when sent). */
@Document("marketing_outbox")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_provider_event", def = "{'tenantId': 1, 'provider': 1, 'eventId': 1}",
                unique = true),
        @CompoundIndex(name = "tenant_provider_due", def = "{'tenantId': 1, 'provider': 1, 'status': 1, 'nextAttemptAt': 1}"),
        @CompoundIndex(name = "tenant_provider_created", def = "{'tenantId': 1, 'provider': 1, 'createdAt': -1}")
})
class MarketingOutboxDocument extends TenantDocument {

    @Id
    String id;
    /** {@code Provider} enum name. */
    String provider;
    String eventId;
    String eventName;
    String orderId;
    String orderNumber;
    String status;
    Integer attempts;
    Instant nextAttemptAt;
    Instant leaseUntil;
    String claimToken;
    /** {@code DeliveryCode} name of the last problem, if any. */
    String error;
    @Indexed(name = "created_ttl", expireAfter = "30d")
    Instant createdAt;
    Instant updatedAt;
}
