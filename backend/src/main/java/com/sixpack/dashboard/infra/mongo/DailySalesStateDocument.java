package com.sixpack.dashboard.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One per store: the summary is complete through {@code through} (YYYY-MM-DD) in {@code timeZone}. */
@Document("daily_sales_state")
class DailySalesStateDocument extends TenantDocument {
    @Id
    String id;
    @Indexed(unique = true, sparse = true)
    String key;
    String through;
    String timeZone;
    Instant computedAt;
}
