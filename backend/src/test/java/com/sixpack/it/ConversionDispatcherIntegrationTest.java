package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.marketing.application.ConversionDispatcher;
import com.sixpack.marketing.domain.Provider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Real adapters against the provider stub: delivery, retries, give-up, fencing, leases, skips, tenant binding. */
class ConversionDispatcherIntegrationTest extends MarketingServerIntegrationTest {

    static final String META_TOKEN = "fake-meta-token-aaaaaaaa";
    static final String GA_SECRET = "fake-ga4-secret-aaaaaaa";

    @Autowired
    ConversionDispatcher dispatcher;
    @Autowired
    MeterRegistry meters;

    Store acme;

    @BeforeEach
    void store() throws Exception {
        acme = configuredStore("acme", "123456789012345", META_TOKEN, "G-AB12CD3", GA_SECRET);
        STUB.reset();
    }

    private double acmeMetaSent() {
        var counter = meters.find("marketing.events").tags("provider", "meta", "outcome", "sent", "tenant", "acme")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private JsonNode card(Store s, int index) throws Exception {
        return body(list(s.tenant(), s.admin()).andReturn()).get("integrations").get(index);
    }

    @Test
    void sendsEachStoresOwnCredentialsWithTheOrderIdAsEventId() throws Exception {
        Store globex = configuredStore("globex", "999999999999999", "fake-meta-token-gggggggg", "G-GLOBEX1",
                "fake-ga4-secret-ggggggg");
        String acmeOrder = order(acme, consented(true, true));
        String globexOrder = order(globex, consented(true, true));
        STUB.reset();
        double sentBefore = acmeMetaSent(); // the registry is shared by every test in this context

        dispatcher.dispatch(Provider.META);
        dispatcher.dispatch(Provider.GOOGLE);

        ProviderStub.Call acmeMeta = STUB.callsTo(META_EVENTS + "123456789012345/").get(0);
        assertThat(json.readTree(acmeMeta.body()).get("access_token").asText()).isEqualTo(META_TOKEN);
        assertThat(json.readTree(acmeMeta.body()).at("/data/0/event_id").asText()).isEqualTo(acmeOrder);
        ProviderStub.Call globexMeta = STUB.callsTo(META_EVENTS + "999999999999999/").get(0);
        assertThat(globexMeta.body()).contains(globexOrder, "fake-meta-token-gggggggg").doesNotContain(META_TOKEN);

        List<ProviderStub.Call> collects = STUB.callsTo("/mp/collect");
        assertThat(collects).hasSize(2);
        assertThat(collects).anySatisfy(c -> {
            assertThat(c.query()).contains("measurement_id=G-AB12CD3", "api_secret=" + GA_SECRET);
            assertThat(json.readTree(c.body()).at("/events/0/params/transaction_id").asText()).isEqualTo(acmeOrder);
        });
        assertThat(collects).anySatisfy(c -> assertThat(c.query()).contains("G-GLOBEX1", "fake-ga4-secret-ggggggg"));

        assertThat(outboxRow(acme.tenant(), "META", acmeOrder).getString("status")).isEqualTo("SENT");
        assertThat(card(acme, 1).get("lastSuccessAt").isNull()).isFalse();
        assertThat(acmeMetaSent() - sentBefore).isEqualTo(1);
        assertThat(meters.find("marketing.outbox.due.age").tag("provider", "meta").gauge()).isNotNull();
    }

    @Test
    void hashedEmailGoesOnlyToMeta() throws Exception {
        order(acme, consented(true, true));
        dispatcher.dispatch(Provider.META);
        dispatcher.dispatch(Provider.GOOGLE);
        JsonNode metaBody = json.readTree(STUB.callsTo(META_EVENTS).get(0).body());
        assertThat(metaBody.at("/data/0/user_data/em/0").asText()).isEqualTo(sha256("buyer@acme.test"));
        assertThat(metaBody.at("/data/0/user_data/client_user_agent").asText()).isEqualTo("Mozilla/5.0 test");
        assertThat(metaBody.at("/data/0/event_source_url").asText()).isEqualTo("https://shop.acme.test/shipping");
        String googleBody = STUB.callsTo("/mp/collect").get(0).body();
        assertThat(googleBody).doesNotContain("buyer@acme.test", sha256("buyer@acme.test"), "\"em\"");
        assertThat(json.readTree(googleBody).get("client_id").asText()).isEqualTo("1234567890.1730000000");
    }

    @Test
    void retryableFailuresBackOffThenGiveUpAndFlagTheCard() throws Exception {
        String orderId = order(acme, consented(false, true));
        STUB.reply(META_EVENTS, ProviderStub.Reply.of(500, "{}"));

        Instant before = Instant.now();
        dispatcher.dispatch(Provider.META);
        Document first = outboxRow(acme.tenant(), "META", orderId);
        assertThat(first.getString("status")).isEqualTo("PENDING");
        assertThat(first.getInteger("attempts")).isEqualTo(1);
        assertThat(first.getString("error")).isEqualTo("PROVIDER_DOWN");
        assertThat(first.getDate("nextAttemptAt").toInstant())
                .isBetween(before.plusSeconds(47), Instant.now().plusSeconds(73));
        assertThat(card(acme, 1).get("status").asText()).isEqualTo("ACTIVE"); // retrying is not an error yet

        for (int i = 0; i < 5; i++) {
            makeDue(acme.tenant());
            dispatcher.dispatch(Provider.META);
        }
        Document last = outboxRow(acme.tenant(), "META", orderId);
        assertThat(last.getString("status")).isEqualTo("FAILED");
        assertThat(last.getInteger("attempts")).isEqualTo(6);
        assertThat(last.getString("error")).isEqualTo("GAVE_UP");
        assertThat(STUB.callsTo(META_EVENTS)).hasSize(6);
        JsonNode meta = card(acme, 1);
        assertThat(meta.get("status").asText()).isEqualTo("ERROR");
        assertThat(meta.get("lastError").get("code").asText()).isEqualTo("GAVE_UP");
        assertThat(meta.get("deliveries").get(0).get("status").asText()).isEqualTo("FAILED");

        makeDue(acme.tenant());
        dispatcher.dispatch(Provider.META);
        assertThat(STUB.callsTo(META_EVENTS)).hasSize(6); // a dead letter is never retried on its own
    }

    @Test
    void throttlingHonoursRetryAfter() throws Exception {
        String orderId = order(acme, consented(false, true));
        STUB.reply(META_EVENTS, new ProviderStub.Reply(429, "{}", Map.of("Retry-After", "600"), 0));
        dispatcher.dispatch(Provider.META);
        Document row = outboxRow(acme.tenant(), "META", orderId);
        assertThat(row.getString("error")).isEqualTo("RATE_LIMITED");
        assertThat(row.getDate("nextAttemptAt").toInstant()).isAfter(Instant.now().plusSeconds(590));
    }

    @Test
    void aTimeoutIsRetried() throws Exception {
        String orderId = order(acme, consented(false, true));
        STUB.reply(META_EVENTS, new ProviderStub.Reply(200, "{\"events_received\":1}", Map.of(), 1500));
        dispatcher.dispatch(Provider.META);
        Document row = outboxRow(acme.tenant(), "META", orderId);
        assertThat(row.getString("status")).isEqualTo("PENDING");
        assertThat(row.getString("error")).isEqualTo("TIMEOUT");
    }

    @Test
    void aRejectedEventFailsAtOnceAndShowsTheReason() throws Exception {
        String orderId = order(acme, consented(false, true));
        STUB.reply(META_EVENTS, ProviderStub.Reply.of(400,
                "{\"error\":{\"message\":\"Invalid parameter\",\"type\":\"OAuthException\",\"code\":100}}"));
        dispatcher.dispatch(Provider.META);

        Document row = outboxRow(acme.tenant(), "META", orderId);
        assertThat(row.getString("status")).isEqualTo("FAILED");
        assertThat(row.getInteger("attempts")).isEqualTo(1);
        assertThat(row.getString("error")).isEqualTo("REJECTED");
        JsonNode meta = card(acme, 1);
        assertThat(meta.get("status").asText()).isEqualTo("ERROR");
        assertThat(meta.get("lastError").get("message").asText()).isEqualTo("Meta rejected the event: Invalid parameter");
        assertThat(list(acme.tenant(), acme.admin()).andReturn().getResponse().getContentAsString())
                .doesNotContain(META_TOKEN);
    }

    @Test
    void aRejectedTokenAsksTheOwnerForANewOne() throws Exception {
        order(acme, consented(false, true));
        STUB.reply(META_EVENTS, ProviderStub.Reply.of(400,
                "{\"error\":{\"message\":\"Invalid OAuth access token\",\"type\":\"OAuthException\",\"code\":190}}"));
        dispatcher.dispatch(Provider.META);
        JsonNode meta = card(acme, 1);
        assertThat(meta.get("lastError").get("code").asText()).isEqualTo("AUTH_FAILED");
        assertThat(meta.get("lastError").get("message").asText())
                .isEqualTo("Meta rejected the saved secret. Create a new one and save it here.");
    }

    @Test
    void concurrentDispatchersSendEachRowOnce() throws Exception {
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            orders.add(order(acme, consented(false, true)));
        }
        STUB.reset();
        concurrently(6, () -> {
            dispatcher.dispatch(Provider.META);
            return null;
        });
        List<String> sent = new ArrayList<>();
        for (ProviderStub.Call c : STUB.callsTo(META_EVENTS)) {
            sent.add(json.readTree(c.body()).at("/data/0/event_id").asText());
        }
        assertThat(sent).hasSize(12).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(orders);
        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("status")).containsOnly("SENT");
    }

    @Test
    void anExpiredLeaseIsSentAgainAndALiveOneIsLeftAlone() throws Exception {
        String crashed = order(acme, consented(false, true));
        String busy = order(acme, consented(false, true));
        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", crashed), new Document("$set",
                new Document("status", "SENDING").append("attempts", 1).append("claimToken", "dead")
                        .append("leaseUntil", Date.from(Instant.now().minusSeconds(1)))));
        mongo.getCollection("marketing_outbox").updateOne(new Document("eventId", busy), new Document("$set",
                new Document("status", "SENDING").append("attempts", 1).append("claimToken", "alive")
                        .append("leaseUntil", Date.from(Instant.now().plus(Duration.ofMinutes(2))))));

        dispatcher.dispatch(Provider.META);

        assertThat(STUB.callsTo(META_EVENTS)).singleElement()
                .satisfies(c -> assertThat(c.body()).contains(crashed).doesNotContain(busy));
        assertThat(outboxRow(acme.tenant(), "META", crashed).getInteger("attempts")).isEqualTo(2);
        assertThat(outboxRow(acme.tenant(), "META", busy).getString("claimToken")).isEqualTo("alive");
    }

    @Test
    void rowsAreSkippedWhenConsentOrSettingsAreGone() throws Exception {
        String erased = order(acme, consented(true, true));
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(erased)),
                new Document("$unset", new Document("attribution", ""))); // what account erasure does
        saveServer(acme.tenant(), acme.admin(), "google", server(false, null, null)).andExpect(status().isOk());

        dispatcher.dispatch(Provider.META);
        dispatcher.dispatch(Provider.GOOGLE);

        assertThat(STUB.calls()).isEmpty();
        assertThat(outboxRow(acme.tenant(), "META", erased).getString("status")).isEqualTo("SKIPPED");
        assertThat(outboxRow(acme.tenant(), "META", erased).getString("error")).isEqualTo("NO_CONSENT");
        assertThat(outboxRow(acme.tenant(), "GOOGLE", erased).getString("error")).isEqualTo("NO_CONSENT");
    }

    @Test
    void serverEventsTurnedOffAfterQueueingSkipTheRow() throws Exception {
        String orderId = order(acme, consented(true, true));
        saveServer(acme.tenant(), acme.admin(), "google", server(false, null, null)).andExpect(status().isOk());
        dispatcher.dispatch(Provider.GOOGLE);
        assertThat(outboxRow(acme.tenant(), "GOOGLE", orderId).getString("error")).isEqualTo("SERVER_OFF");
        assertThat(STUB.callsTo("/mp/")).isEmpty();
    }

    @Test
    void purchasesOlderThanAProviderAcceptsAreNotSent() throws Exception {
        String orderId = order(acme, consented(true, true));
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(orderId)),
                new Document("$set", new Document("createdAt", Date.from(Instant.now().minus(Duration.ofDays(4))))));
        dispatcher.dispatch(Provider.GOOGLE);
        dispatcher.dispatch(Provider.META);
        assertThat(outboxRow(acme.tenant(), "GOOGLE", orderId).getString("error")).isEqualTo("TOO_OLD");
        assertThat(outboxRow(acme.tenant(), "META", orderId).getString("status")).isEqualTo("SENT"); // 7 days for Meta
    }

    @Test
    void aStoreWithTheFeatureOffIsLeftAlone() throws Exception {
        String orderId = order(acme, consented(true, true));
        marketing(acme.tenant(), false);
        dispatcher.dispatch(Provider.META);
        assertThat(STUB.calls()).isEmpty();
        assertThat(outboxRow(acme.tenant(), "META", orderId).getString("status")).isEqualTo("PENDING");
    }
}
