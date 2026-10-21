package com.sixpack.dashboard.domain;

import java.time.Instant;

/** Read side of the Sales tab. All periods are [from, to). */
public interface SalesReads {

    /** Whole orders only: these cannot be split by item. */
    record Breakdown(long orders, double total, double discount, double tax, double shipping, long couponOrders) {
    }

    Breakdown breakdown(Scope scope, Instant from, Instant to);
}
