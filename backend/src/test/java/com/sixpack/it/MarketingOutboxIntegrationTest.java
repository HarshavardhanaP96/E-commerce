package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.Outbox;
import com.sixpack.marketing.domain.OutboxRow;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** marketing_outbox: one row per store, provider and event; one claimant per row; fenced finishes; 30-day TTL. */
class MarketingOutboxIntegrationTest extends MarketingServerIntegrationTest {

    private static final Duration LEASE = Duration.ofMinutes(2);

    @Autowired
    Outbox outbox;

    Tenant acme;
    Tenant globex;

    @BeforeEach
    void stores() {
        acme = createTenant("acme", "Acme", "shop.acme.test");
        globex = createTenant("globex", "Globex", "shop.globex.test");
    }

    private boolean add(Tenant t, String eventId) {
        return TenantContext.callAs(ref(t), () ->
                outbox.addIfAbsent(Provider.META, eventId, eventId, "ORD-" + eventId, Instant.now()));
    }

    private Optional<OutboxRow> claim(Tenant t) {
        return TenantContext.callAs(ref(t), () -> outbox.claim(Provider.META, Instant.now(), LEASE));
    }

    @Test
    void addIfAbsentIsIdempotentUnderConcurrency() throws Exception {
        assertThat(add(acme, "o1")).isTrue();
        assertThat(add(acme, "o1")).isFalse();
        List<Boolean> results = concurrently(10, () -> add(acme, "o2"));
        assertThat(results).containsOnlyOnce(true);
        assertThat(outboxRows(acme)).hasSize(2);

        Document row = outboxRows(acme).get(0);
        assertThat(row.getString("status")).isEqualTo("PENDING");
        assertThat(row.getInteger("attempts")).isZero();
        assertThat(row.getString("orderNumber")).isEqualTo("ORD-o1");
        // The same event for another provider or another store is a different row.
        TenantContext.runAs(ref(acme), () -> outbox.addIfAbsent(Provider.GOOGLE, "o1", "o1", null, Instant.now()));
        add(globex, "o1");
        assertThat(outboxRows(acme)).hasSize(3);
        assertThat(outboxRows(globex)).hasSize(1);
    }

    @Test
    void concurrentClaimantsTakeEachRowOnce() throws Exception {
        IntStream.range(0, 20).forEach(i -> add(acme, "o" + i));
        List<List<String>> taken = concurrently(8, () -> {
            List<String> mine = new ArrayList<>();
            for (Optional<OutboxRow> r = claim(acme); r.isPresent(); r = claim(acme)) {
                mine.add(r.get().eventId());
            }
            return mine;
        });
        List<String> all = taken.stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(20).doesNotHaveDuplicates();
    }

    @Test
    void aRowIsDueOnlyFromItsNextAttemptAndTheOldestComesFirst() {
        add(acme, "later");
        add(acme, "first");
        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", "later"),
                new Document("$set", new Document("nextAttemptAt", Date.from(Instant.now().plusSeconds(600)))));
        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", "first"),
                new Document("$set", new Document("nextAttemptAt", Date.from(Instant.now().minusSeconds(600)))));

        assertThat(claim(acme)).get().extracting(OutboxRow::eventId).isEqualTo("first");
        assertThat(claim(acme)).isEmpty();
    }

    @Test
    void anExpiredLeaseCanBeClaimedAgainAndTheStaleClaimCannotOverwriteTheNewOwner() {
        add(acme, "o1");
        OutboxRow first = claim(acme).orElseThrow();
        assertThat(first.status()).isEqualTo(OutboxRow.Status.SENDING);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(claim(acme)).isEmpty(); // lease still live

        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", "o1"),
                new Document("$set", new Document("leaseUntil", Date.from(Instant.now().minusSeconds(1)))));
        OutboxRow second = claim(acme).orElseThrow();
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());

        boolean staleWon = TenantContext.callAs(ref(acme), () -> outbox.finish(first, OutboxRow.Status.FAILED,
                DeliveryCode.REJECTED, null, Instant.now()));
        assertThat(staleWon).isFalse();
        assertThat(outboxRow(acme, "META", "o1").getString("status")).isEqualTo("SENDING");

        boolean ownerWon = TenantContext.callAs(ref(acme), () -> outbox.finish(second, OutboxRow.Status.SENT, null,
                null, Instant.now()));
        assertThat(ownerWon).isTrue();
        Document done = outboxRow(acme, "META", "o1");
        assertThat(done.getString("status")).isEqualTo("SENT");
        assertThat(done).doesNotContainKeys("leaseUntil", "claimToken", "error");
    }

    @Test
    void aRetryKeepsTheErrorAndTheNextAttempt() {
        add(acme, "o1");
        OutboxRow row = claim(acme).orElseThrow();
        Instant next = Instant.now().plusSeconds(60);
        TenantContext.runAs(ref(acme), () -> outbox.finish(row, OutboxRow.Status.PENDING, DeliveryCode.TIMEOUT, next,
                Instant.now()));
        Document stored = outboxRow(acme, "META", "o1");
        assertThat(stored.getString("status")).isEqualTo("PENDING");
        assertThat(stored.getString("error")).isEqualTo("TIMEOUT");
        assertThat(stored.getDate("nextAttemptAt").toInstant()).isEqualTo(next.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void oneStoreNeverClaimsAnothersRows() {
        add(acme, "o1");
        assertThat(claim(globex)).isEmpty();
        assertThat(claim(acme)).isPresent();
    }

    @Test
    void theRecentPageIsNewestFirstAndCountsPages() {
        IntStream.range(0, 25).forEach(i -> {
            add(acme, "o" + i);
            mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", "o" + i),
                    new Document("$set", new Document("createdAt", Date.from(Instant.parse("2026-10-31T10:00:00Z")
                            .plusSeconds(i)))));
        });
        Outbox.Page first = TenantContext.callAs(ref(acme), () -> outbox.recent(Provider.META, 1, 20));
        Outbox.Page second = TenantContext.callAs(ref(acme), () -> outbox.recent(Provider.META, 2, 20));
        assertThat(first.totalPage()).isEqualTo(2);
        assertThat(first.rows()).hasSize(20).first().extracting(OutboxRow::eventId).isEqualTo("o24");
        assertThat(second.rows()).hasSize(5).last().extracting(OutboxRow::eventId).isEqualTo("o0");
        assertThat(TenantContext.callAs(ref(acme), () -> outbox.existingEventIds(Provider.META, List.of("o1", "zz"))))
                .containsExactly("o1");
    }

    @Test
    void rowsExpireThirtyDaysAfterTheyWereQueued() {
        add(acme, "o1");
        Document ttl = mongo.getCollection("marketing_outbox").listIndexes().into(new ArrayList<>()).stream()
                .filter(i -> i.containsKey("expireAfterSeconds")).findFirst().orElseThrow();
        assertThat(ttl.get("key", Document.class)).containsEntry("createdAt", 1);
        assertThat(((Number) ttl.get("expireAfterSeconds")).longValue()).isEqualTo(30L * 24 * 60 * 60);
    }
}
