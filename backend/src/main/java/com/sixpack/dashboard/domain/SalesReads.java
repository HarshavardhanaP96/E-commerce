package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read side of the Sales tab. All periods are [from, to). */
public interface SalesReads {

    record ProductRevenue(String productId, double revenue, long units) {
    }

    /** Whole orders only: these cannot be split by item. */
    record Breakdown(long orders, double total, double discount, double tax, double shipping, long couponOrders) {
    }

    /** @param method how the customer paid, "unknown" when the provider did not say */
    record MethodTotals(String method, long orders, double revenue) {
    }

    /** Item revenue (price × quantity) per product; narrowed to items when the scope is. */
    List<ProductRevenue> byProduct(Scope scope, Instant from, Instant to);

    /** Category of each product that still exists; deleted products are missing from the map. */
    Map<String, String> categories(Collection<String> productIds);

    Breakdown breakdown(Scope scope, Instant from, Instant to);

    List<MethodTotals> byPaymentMethod(Scope scope, Instant from, Instant to);
}
