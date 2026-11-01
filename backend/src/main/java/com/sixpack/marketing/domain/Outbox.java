package com.sixpack.marketing.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The current store's queued conversions (marketing_outbox). Every call is scoped to the tenant in context. */
public interface Outbox {

    /** Queues a PENDING row unless one exists for {@code (provider, eventId)}. @return whether a row was added */
    boolean addIfAbsent(Provider provider, String eventId, String orderId, String orderNumber, Instant now);

    /**
     * Claims the oldest due row for this process: PENDING with {@code nextAttemptAt <= now}, or SENDING whose lease ran
     * out. The claim sets a fresh claim token, a lease and counts the attempt.
     */
    Optional<OutboxRow> claim(Provider provider, Instant now, Duration lease);

    /** Ends a claim. @return false when the claim was lost (its lease ran out and another process took the row) */
    boolean finish(OutboxRow claimed, OutboxRow.Status status, DeliveryCode error, Instant nextAttemptAt, Instant now);

    Set<String> existingEventIds(Provider provider, Collection<String> eventIds);

    /** Newest first; {@code page} starts at 1. */
    Page recent(Provider provider, int page, int size);

    record Page(List<OutboxRow> rows, int totalPage) {
    }
}
