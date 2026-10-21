package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.util.List;

/** Read side of the Orders tab. */
public interface OrderReads {

    /**
     * One order, flattened for analysis.
     *
     * @param shippedAt   first time it was marked Shipped; null when never, or when it has no status history
     * @param deliveredAt first time it was marked Delivered; same rule
     * @param refunded    at least one refund that did not fail
     */
    record OrderFacts(String id, String orderNumber, Instant placedAt, String status, int items, double total,
                      double discount, String paymentMethod, Instant shippedAt,
                      Instant deliveredAt, boolean refunded) {
    }

    /** Orders placed in [from, to), newest first, at most {@code max}. */
    List<OrderFacts> orders(Scope scope, Instant from, Instant to, int max);

    /** Orders still Processing that were placed before {@code placedBefore}, oldest first. */
    List<OrderFacts> stuck(Scope scope, Instant placedBefore, int limit);

    long stuckCount(Scope scope, Instant placedBefore);
}
