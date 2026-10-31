package com.sixpack.marketing.web;

import com.sixpack.marketing.domain.OutboxRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One delivery in the owner's log. Ids and state only: never an email, hash, IP, user agent or click ID. */
record DeliveryView(String eventId, String orderNumber, String eventName, String status, int attempts,
                    Instant createdAt, Instant updatedAt, Instant nextAttemptAt, Map<String, String> error) {

    static DeliveryView from(OutboxRow r) {
        Map<String, String> error = null;
        if (r.error() != null) {
            error = new LinkedHashMap<>();
            error.put("code", r.error().name());
            error.put("message", r.error().message(r.provider()));
        }
        return new DeliveryView(r.eventId(), r.orderNumber(), r.eventName(), r.status().name(), r.attempts(),
                r.createdAt(), r.updatedAt(), r.status() == OutboxRow.Status.PENDING ? r.nextAttemptAt() : null, error);
    }
}
