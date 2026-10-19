package com.sixpack.order.domain;

import java.time.Instant;

/**
 * Money returned to the customer for one order.
 *
 * @param providerRefundId the provider's id for it; kept server-side
 * @param status           the provider's state when it was issued, e.g. {@code processed} or {@code pending}
 * @param reason           shown to the customer
 * @param actor            the admin who issued it
 */
public record Refund(String providerRefundId, double amount, String reason, String status, String actor,
                     Instant at) {
}
