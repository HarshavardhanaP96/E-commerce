package com.sixpack.dashboard.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One row of the daily summary. {@code day} is the store's local date as YYYY-MM-DD, so ranges sort as text. */
@Document("daily_sales")
@CompoundIndex(name = "tenant_currency_day", def = "{'tenantId': 1, 'currency': 1, 'day': 1}", unique = true)
class DailySalesDocument extends TenantDocument {
    @Id
    String id;
    String day;
    String currency;
    String timeZone;
    long orders;
    double grossRevenue;
    double refunds;
    double netRevenue;
    double discount;
    double tax;
    double units;
    long newCustomers;
    Instant computedAt;
}
