package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.storage.TestImages;
import com.sixpack.tenant.domain.Tenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One Spring context for the phase-3 tests: an all-zero test key (so server events are available), both provider
 * hosts pointed at {@link ProviderStub}, the dispatcher and sweep parked (tests call them), the breaker never
 * staying open, and a short read timeout for the timeout tests.
 */
@TestPropertySource(properties = {
        "app.marketing.keys=k1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.marketing.dispatch-interval=PT1H",
        "app.marketing.sweep-interval=PT1H",
        "app.marketing.breaker-open-for=PT0S",
        "app.marketing.http.read-timeout=500ms"})
abstract class MarketingServerIntegrationTest extends MarketingIntegrationTest {

    protected static final ProviderStub STUB = ProviderStub.start();
    protected static final String META_EVENTS = "/v25.0/";

    @DynamicPropertySource
    static void providerHosts(DynamicPropertyRegistry registry) {
        registry.add("app.marketing.meta.base-url", STUB::url);
        registry.add("app.marketing.google.base-url", STUB::url);
    }

    /** marketing_outbox is cleaned here, not in the shared IntegrationTest base (edited by other workstreams). */
    @BeforeEach
    void cleanOutboxAndStub() {
        mongo.getCollection("marketing_outbox").deleteMany(new Document());
        STUB.reset();
    }

    protected record Store(Tenant tenant, String admin, String product, String shopper) {
    }

    /** A store with fake payments, the marketing switch on, Meta and Google set up for server events, a product and a shopper. */
    protected Store configuredStore(String slug, String pixel, String metaToken, String ga4, String apiSecret)
            throws Exception {
        Tenant t = marketing(withFakePayments(createTenant(slug, slug, "shop." + slug + ".test")), true);
        String admin = adminTokenFor(t);
        save(t, admin, "meta", browser("pixelId", pixel)).andExpect(status().isOk());
        saveServer(t, admin, "meta", server(true, metaToken, "TEST123")).andExpect(status().isOk());
        save(t, admin, "google", browser("ga4MeasurementId", ga4)).andExpect(status().isOk());
        saveServer(t, admin, "google", server(true, apiSecret, null)).andExpect(status().isOk());
        return new Store(t, admin, productOn(t, admin), registerOn(t, "buyer@" + slug + ".test"));
    }

    protected ResultActions saveServer(Tenant t, String token, String provider, Map<String, Object> server)
            throws Exception {
        return mvc.perform(put(BASE + "/" + provider).with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("server", server))));
    }

    protected static Map<String, Object> server(boolean enabled, String secret, String testEventCode) {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", enabled);
        if (secret != null) {
            m.put("secret", secret);
        }
        m.put("testEventCode", testEventCode);
        return m;
    }

    protected String productOn(Tenant t, String admin) throws Exception {
        return body(mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .with(host(hostOf(t))).param("name", "Lamp").param("price", "500").param("stock", "50")
                        .param("category", "home").header("Authorization", bearer(admin)))
                .andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
    }

    /** The attribution a consenting shopper's browser sends (cookie version 2026-10-31, so Meta may get hashes). */
    protected static Map<String, Object> consented(boolean analytics, boolean marketing) {
        Map<String, Object> a = new HashMap<>(Map.of("utmSource", "google", "utmCampaign", "diwali",
                "gclid", "Cj0KCQ-abc", "fbclid", "IwAR-xyz", "fbp", "fb.1.1730000000000.1098115397",
                "gaClientId", "1234567890.1730000000", "landingPath", "/product/lamp"));
        a.put("consent", Map.of("analytics", analytics, "marketing", marketing, "version", "2026-10-31"));
        return a;
    }

    /** Checks out one lamp on the store's host and confirms the fake payment. Returns the order id. */
    protected String order(Store s, Map<String, Object> attribution) throws Exception {
        Map<String, Object> checkout = new HashMap<>();
        checkout.put("items", List.of(Map.of("productId", s.product(), "quantity", 1)));
        if (attribution != null) {
            checkout.put("attribution", attribution);
        }
        String providerOrderId = body(mvc.perform(post("/api/v1/order/checkout").with(host(hostOf(s.tenant())))
                        .header("Authorization", bearer(s.shopper())).header("User-Agent", "Mozilla/5.0 test")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(checkout)))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        return body(mvc.perform(post("/api/v1/order/new").with(host(hostOf(s.tenant())))
                        .header("Authorization", bearer(s.shopper())).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("providerOrderId", providerOrderId,
                                "paymentId", "fake_pay_1", "signature", "fake",
                                "shippingInfo", Map.of("address", "12 MG Road", "city", "Bengaluru",
                                        "state", "Karnataka", "country", "India", "pinCode", "560001")))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    protected List<Document> outboxRows(Tenant t) {
        return mongo.getCollection("marketing_outbox").find(new Document("tenantId", t.id()))
                .sort(new Document("createdAt", 1)).into(new ArrayList<>());
    }

    protected Document outboxRow(Tenant t, String provider, String eventId) {
        return mongo.getCollection("marketing_outbox").find(new Document("tenantId", t.id())
                .append("provider", provider).append("eventId", eventId)).first();
    }

    /** Makes every queued row of the store due now, as if its backoff had passed. */
    protected void makeDue(Tenant t) {
        mongo.getCollection("marketing_outbox").updateMany(new Document("tenantId", t.id()),
                new Document("$set", new Document("nextAttemptAt", Date.from(Instant.now().minusSeconds(1)))));
    }

    protected static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Runs {@code task} on {@code n} threads released at the same moment and returns every result. */
    protected static <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
