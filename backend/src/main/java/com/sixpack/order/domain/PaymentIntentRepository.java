package com.sixpack.order.domain;

import java.util.Optional;

public interface PaymentIntentRepository {

    void save(PaymentIntent intent);

    Optional<PaymentIntent> findByProviderOrderId(String providerOrderId);

    /** Atomically flips CREATED → CONSUMED. @return false if it was already consumed */
    boolean markConsumed(String providerOrderId);

    /** Reverts a consumption whose order could not be created, so the customer can retry. */
    void markCreated(String providerOrderId);
}
