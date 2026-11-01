package com.sixpack.marketing.application;

import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.order.OrderAttribution;
import com.sixpack.order.PaidOrders.PaidOrder;

/** What a provider is sent for one paid order, built from the order as it is now (so erasure and consent apply). */
final class Conversions {

    private Conversions() {
    }

    /** Callers check consent first ({@link ConversionRecorder#allowed}); {@code order.attribution()} is not null. */
    static Conversion of(PaidOrder order, Provider provider, String storeCurrency, String piiConsentVersion,
                         String callingCode) {
        OrderAttribution a = order.attribution();
        boolean marketing = a.consent().marketing();
        // Hashed contact details go to Meta only, and only to shoppers whose cookie notice said so.
        boolean pii = provider == Provider.META && marketing && a.consent().version() != null
                && a.consent().version().compareTo(piiConsentVersion) >= 0;
        Conversion.Identity identity = new Conversion.Identity(
                pii ? PiiHasher.email(order.email()) : null,
                pii ? PiiHasher.phone(order.phone(), callingCode) : null,
                pii && order.userId() != null ? PiiHasher.sha256(order.userId()) : null,
                a.fbp(), a.fbc(), a.gaClientId(), a.userAgent(), a.sourceUrl(), marketing);
        return new Conversion(order.id(), order.placedAt(),
                order.currency() != null ? order.currency() : storeCurrency, order.total(), order.tax(),
                order.shipping(), order.coupon(), order.lines().stream().map(l -> new Conversion.Item(l.productId(),
                        l.name(), l.variant(), l.price(), l.quantity())).toList(), identity);
    }
}
