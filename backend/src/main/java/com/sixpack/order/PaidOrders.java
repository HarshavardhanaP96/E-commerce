package com.sixpack.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only view of the current store's paid orders, for modules that report sales elsewhere (marketing). */
public interface PaidOrders {

    /** The order as it is now (after any erasure), or empty when it is gone or was never paid. */
    Optional<PaidOrder> find(String orderId);

    /** Ids of paid orders placed in [from, to) whose shopper allowed analytics or marketing, oldest first. */
    List<String> consentedBetween(Instant from, Instant to, int limit);

    /** {@code email}/{@code phone} are the buyer snapshot (null once erased); {@code attribution} may be null. */
    record PaidOrder(String id, String orderNumber, String userId, Instant placedAt, double total, double tax,
                     double shipping, String currency, String coupon, List<Line> lines, String email, String phone,
                     OrderAttribution attribution) {
    }

    record Line(String productId, String name, String variant, double price, int quantity) {
    }
}
