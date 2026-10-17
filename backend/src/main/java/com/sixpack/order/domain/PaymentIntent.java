package com.sixpack.order.domain;

import java.util.List;

/** Server-side record of a priced cart awaiting payment (ADR-009). */
public record PaymentIntent(String id, String userId, String provider, String providerOrderId, List<OrderItem> items,
                            String coupon, Quote quote, String currency) {
}
