package com.sixpack.order.domain;

import com.sixpack.order.OrderAttribution;
import java.util.List;

/** Server-side record of a priced cart awaiting payment (ADR-009). {@code attribution} may be null. */
public record PaymentIntent(String id, String userId, String provider, String providerOrderId, List<OrderItem> items,
                            String coupon, Quote quote, String currency, OrderAttribution attribution) {

    /** Pre-attribution shape, kept so existing callers compile unchanged. */
    public PaymentIntent(String id, String userId, String provider, String providerOrderId, List<OrderItem> items,
                         String coupon, Quote quote, String currency) {
        this(id, userId, provider, providerOrderId, items, coupon, quote, currency, null);
    }
}
