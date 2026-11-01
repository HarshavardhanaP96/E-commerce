package com.sixpack.marketing.domain;

import java.time.Instant;

/** A purchase queued for one provider. Holds ids and delivery state only: no personal data. */
public record OutboxRow(String id, Provider provider, String eventId, String eventName, String orderId,
                        String orderNumber, Status status, int attempts, Instant nextAttemptAt, String claimToken,
                        DeliveryCode error, Instant createdAt, Instant updatedAt) {

    public enum Status { PENDING, SENDING, SENT, FAILED, SKIPPED }
}
