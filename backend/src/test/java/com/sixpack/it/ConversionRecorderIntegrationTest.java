package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.marketing.application.ConversionRecorder;
import com.sixpack.marketing.application.ConversionSweep;
import com.sixpack.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A paid order is queued once per provider the shopper allowed and the store set up; never a network call. */
class ConversionRecorderIntegrationTest extends MarketingServerIntegrationTest {

    @Autowired
    ConversionRecorder recorder;
    @Autowired
    ConversionSweep sweep;

    Store acme;

    @BeforeEach
    void store() throws Exception {
        acme = configuredStore("acme", "123456789012345", "fake-meta-token-aaaaaaaa", "G-AB12CD3",
                "fake-ga4-secret-aaaaaaa");
        STUB.reset();
    }

    @Test
    void aPaidOrderIsQueuedOncePerProviderTheShopperAllowed() throws Exception {
        String orderId = order(acme, consented(true, true));
        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("provider"))
                .containsExactlyInAnyOrder("META", "GOOGLE");
        Document meta = outboxRow(acme.tenant(), "META", orderId);
        assertThat(meta.getString("status")).isEqualTo("PENDING");
        assertThat(meta.getString("eventName")).isEqualTo("purchase");
        assertThat(meta.getString("orderNumber")).startsWith("ORD-");
        assertThat(meta.getInteger("attempts")).isZero();
        assertThat(meta.keySet()).doesNotContain("email", "phone", "payload", "userAgent");
        assertThat(STUB.calls()).isEmpty(); // queueing never calls a provider
    }

    @Test
    void eachProviderNeedsItsOwnConsent() throws Exception {
        String marketingOnly = order(acme, consented(false, true));
        String analyticsOnly = order(acme, consented(true, false));
        String declined = order(acme, consented(false, false));
        String none = order(acme, null);
        assertThat(outboxRow(acme.tenant(), "META", marketingOnly)).isNotNull();
        assertThat(outboxRow(acme.tenant(), "GOOGLE", marketingOnly)).isNull();
        assertThat(outboxRow(acme.tenant(), "GOOGLE", analyticsOnly)).isNotNull();
        assertThat(outboxRow(acme.tenant(), "META", analyticsOnly)).isNull();
        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("eventId"))
                .doesNotContain(declined, none);
    }

    @Test
    void theSameOrderIsQueuedOnceHoweverOftenItIsSeen() throws Exception {
        String orderId = order(acme, consented(true, true));
        for (int i = 0; i < 2; i++) { // each status change publishes OrderChangedEvent again
            mvc.perform(put("/api/v1/order/" + orderId).with(host(hostOf(acme.tenant())))
                    .header("Authorization", bearer(acme.admin()))).andExpect(status().isOk());
        }
        concurrently(10, () -> TenantContext.callAs(ref(acme.tenant()), () -> recorder.record(orderId)));
        assertThat(outboxRows(acme.tenant())).hasSize(2);
    }

    @Test
    void nothingIsQueuedWhenServerEventsOrTheFeatureAreOff() throws Exception {
        saveServer(acme.tenant(), acme.admin(), "meta", server(false, null, null)).andExpect(status().isOk());
        String metaOff = order(acme, consented(true, true));
        assertThat(outboxRow(acme.tenant(), "META", metaOff)).isNull();
        assertThat(outboxRow(acme.tenant(), "GOOGLE", metaOff)).isNotNull();

        marketing(acme.tenant(), false);
        String featureOff = order(acme, consented(true, true));
        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("eventId")).doesNotContain(featureOff);
    }

    @Test
    void theSweepQueuesAPaidOrderTheListenerMissed() throws Exception {
        saveServer(acme.tenant(), acme.admin(), "meta", server(false, null, null)).andExpect(status().isOk());
        saveServer(acme.tenant(), acme.admin(), "google", server(false, null, null)).andExpect(status().isOk());
        String missed = order(acme, consented(true, true)); // as if the process died before queueing
        String old = order(acme, consented(true, true));
        mongo.getCollection("orders").updateOne(new Document("_id", new ObjectId(old)),
                new Document("$set", new Document("createdAt", Date.from(Instant.now().minusSeconds(4 * 24 * 3600)))));
        assertThat(outboxRows(acme.tenant())).isEmpty();

        saveServer(acme.tenant(), acme.admin(), "meta", server(true, null, null)).andExpect(status().isOk());
        saveServer(acme.tenant(), acme.admin(), "google", server(true, null, null)).andExpect(status().isOk());
        sweep.sweep(Instant.now().plusSeconds(120));
        sweep.sweep(Instant.now().plusSeconds(120)); // running again adds nothing

        assertThat(outboxRows(acme.tenant())).extracting(d -> d.getString("eventId")).containsOnly(missed).hasSize(2);
        assertThat(STUB.calls()).isEmpty();
    }
}
