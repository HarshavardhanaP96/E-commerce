package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.shared.tenant.TenantLimits;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantStatus;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Helpers for the product feed tests: stores, raw products, the two admin calls and the public fetch. */
abstract class FeedIntegrationTest extends MarketingIntegrationTest {

    protected static final String FEED = BASE + "/feed";
    /** A feed address: the store's domain (group 1), then a 43-character URL-safe token (group 2). */
    protected static final Pattern FEED_URL =
            Pattern.compile("^https://([^/]+)/api/v1/feeds/([A-Za-z0-9_-]{43})/products\\.xml$");

    // Cleaned here, not in the shared IntegrationTest base, which other workstreams edit in parallel.
    @BeforeEach
    void cleanFeedSettings() {
        mongo.getCollection("marketing_settings").deleteMany(new Document());
    }

    /** A store on {@code shop.<slug>.test} with the marketing switch on, its own currency and optional limits. */
    protected Tenant feedStore(String slug, String name, String currency, TenantLimits limits) {
        Tenant t = tenantRepository.save(new Tenant(null, slug, name, TenantStatus.ACTIVE,
                List.of("shop." + slug + ".test"), Map.of(), "en-IN", currency, null, null, null, null, null,
                Map.of("marketing", true), null, limits));
        tenantRegistry.refresh();
        return t;
    }

    protected ResultActions setFeed(Tenant t, String token, boolean enabled) throws Exception {
        return mvc.perform(put(FEED).with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enabled", enabled))));
    }

    protected ResultActions rotate(Tenant t, String token) throws Exception {
        return mvc.perform(post(FEED + "/rotate").with(host(hostOf(t))).header("Authorization", bearer(token)));
    }

    /** Turns the feed on through the admin API and returns its secret token. */
    protected String enableFeed(Tenant t, String adminToken) throws Exception {
        return tokenOf(body(setFeed(t, adminToken, true).andExpect(status().isOk()).andReturn())
                .get("feed").get("url").asText());
    }

    protected static String tokenOf(String url) {
        Matcher m = FEED_URL.matcher(url);
        assertThat(m.matches()).as(url).isTrue();
        return m.group(2);
    }

    protected static String feedPath(String feedToken) {
        return "/api/v1/feeds/" + feedToken + "/products.xml";
    }

    /** An anonymous fetch, as Google or Meta make it, on the store's host. */
    protected ResultActions fetch(Tenant t, String feedToken) throws Exception {
        return mvc.perform(get(feedPath(feedToken)).with(host(hostOf(t))));
    }

    protected byte[] feedBody(Tenant t, String feedToken) throws Exception {
        return fetch(t, feedToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /** Makes the request come from {@code ip}. */
    protected static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** Inserts a product straight into the store's collection: no product event, so cached feeds stay. */
    protected String insertProduct(Tenant t, Document product) {
        ObjectId id = new ObjectId();
        mongo.getCollection("products").insertOne(product.append("_id", id).append("tenantId", t.id()));
        return id.toHexString();
    }

    /** An active product without variants, photo {@code uploads/p/<name>.png}. */
    protected static Document simple(String name, double price, int stock) {
        return new Document("name", name).append("photo", "uploads/p/" + name.replace(' ', '-') + ".png")
                .append("price", price).append("stock", stock).append("status", "ACTIVE");
    }

    /** "Tee" with axes Size (S, M) and Colour (Black, which has an image) and the given variants. */
    protected static Document tee(Document... variants) {
        return simple("Tee", 499, 0).append("variants", new Document("axes", List.of(
                        new Document("name", "Size").append("type", "TEXT")
                                .append("values", List.of(new Document("label", "S"), new Document("label", "M"))),
                        new Document("name", "Colour").append("type", "COLOUR").append("values", List.of(
                                new Document("label", "Black").append("images", List.of("uploads/p/tee-black.png"))))))
                .append("items", List.of(variants)));
    }

    /** A Tee variant in Black; {@code status} is ACTIVE or DISABLED. */
    protected static Document variant(String id, String size, double price, int stock, String status) {
        return new Document("variantId", id).append("sku", id.toUpperCase())
                .append("options", List.of(new Document("name", "Size").append("value", size),
                        new Document("name", "Colour").append("value", "Black")))
                .append("price", price).append("stock", stock).append("status", status);
    }
}
