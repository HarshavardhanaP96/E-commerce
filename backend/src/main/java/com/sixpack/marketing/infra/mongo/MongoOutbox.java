package com.sixpack.marketing.infra.mongo;

import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoOutbox implements Outbox {

    private static final String PENDING = OutboxRow.Status.PENDING.name();
    private static final String SENDING = OutboxRow.Status.SENDING.name();

    private final TenantMongo mongo;

    MongoOutbox(TenantMongo mongo) {
        this.mongo = mongo;
    }

    /** One upsert on the unique key: concurrent callers (listener, sweep, replicas) end with exactly one row. */
    @Override
    public boolean addIfAbsent(Provider provider, String eventId, String orderId, String orderNumber, Instant now) {
        Update insert = new Update().setOnInsert("eventName", "purchase").setOnInsert("orderId", orderId)
                .setOnInsert("orderNumber", orderNumber).setOnInsert("status", PENDING).setOnInsert("attempts", 0)
                .setOnInsert("nextAttemptAt", now).setOnInsert("createdAt", now).setOnInsert("updatedAt", now);
        try {
            return mongo.upsert(byEvent(provider, eventId), insert, MarketingOutboxDocument.class)
                    .getUpsertedId() != null;
        } catch (DuplicateKeyException e) {
            return false; // a concurrent insert of the same event won
        }
    }

    @Override
    public Optional<OutboxRow> claim(Provider provider, Instant now, Duration lease) {
        Query due = Query.query(Criteria.where("provider").is(provider.name()).orOperator(
                        Criteria.where("status").is(PENDING).and("nextAttemptAt").lte(now),
                        Criteria.where("status").is(SENDING).and("leaseUntil").lt(now)))
                .with(Sort.by("nextAttemptAt"));
        Update take = new Update().set("status", SENDING).set("leaseUntil", now.plus(lease))
                .set("claimToken", UUID.randomUUID().toString()).set("updatedAt", now).inc("attempts", 1);
        return mongo.findAndModify(due, take, FindAndModifyOptions.options().returnNew(true),
                MarketingOutboxDocument.class).map(MongoOutbox::toDomain);
    }

    /** Fenced: only the holder of the current claim token may end the claim. */
    @Override
    public boolean finish(OutboxRow claimed, OutboxRow.Status status, DeliveryCode error, Instant nextAttemptAt,
                          Instant now) {
        Update update = new Update().set("status", status.name()).set("updatedAt", now)
                .unset("leaseUntil").unset("claimToken");
        if (error == null) {
            update.unset("error");
        } else {
            update.set("error", error.name());
        }
        if (nextAttemptAt != null) {
            update.set("nextAttemptAt", nextAttemptAt);
        }
        Query mine = Query.query(Criteria.where("id").is(claimed.id()).and("status").is(SENDING)
                .and("claimToken").is(claimed.claimToken()));
        return mongo.updateFirst(mine, update, MarketingOutboxDocument.class).getModifiedCount() == 1;
    }

    @Override
    public Set<String> existingEventIds(Provider provider, Collection<String> eventIds) {
        if (eventIds.isEmpty()) {
            return Set.of();
        }
        Query query = Query.query(Criteria.where("provider").is(provider.name()).and("eventId").in(eventIds));
        query.fields().include("eventId");
        return mongo.find(query, MarketingOutboxDocument.class).stream().map(d -> d.eventId)
                .collect(Collectors.toSet());
    }

    @Override
    public Page recent(Provider provider, int page, int size) {
        Query byProvider = Query.query(Criteria.where("provider").is(provider.name()));
        long total = mongo.count(byProvider, MarketingOutboxDocument.class);
        List<OutboxRow> rows = mongo.find(Query.of(byProvider)
                        .with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")))
                        .skip((long) (page - 1) * size).limit(size), MarketingOutboxDocument.class)
                .stream().map(MongoOutbox::toDomain).toList();
        return new Page(rows, (int) ((total + size - 1) / size));
    }

    private static Query byEvent(Provider provider, String eventId) {
        return Query.query(Criteria.where("provider").is(provider.name()).and("eventId").is(eventId));
    }

    private static OutboxRow toDomain(MarketingOutboxDocument d) {
        DeliveryCode error = d.error == null ? null : Arrays.stream(DeliveryCode.values())
                .filter(c -> c.name().equals(d.error)).findFirst().orElse(null);
        return new OutboxRow(d.id, Provider.valueOf(d.provider), d.eventId, d.eventName, d.orderId, d.orderNumber,
                OutboxRow.Status.valueOf(d.status), d.attempts == null ? 0 : d.attempts, d.nextAttemptAt, d.claimToken,
                error, d.createdAt, d.updatedAt);
    }
}
