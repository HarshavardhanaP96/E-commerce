package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.marketing.FeedXml;
import com.sixpack.shared.tenant.TenantLimits;
import com.sixpack.storage.TestImages;
import com.sixpack.tenant.domain.Tenant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/** The public product feed Google Merchant Center and the Meta catalogue fetch. */
class FeedEndpointIntegrationTest extends FeedIntegrationTest {

    Tenant acme;
    String admin;
    String token;

    @BeforeEach
    void storeWithAFeed() throws Exception {
        acme = feedStore("acme", "Acme Shop", "USD", null);
        admin = adminTokenFor(acme);
        token = enableFeed(acme, admin);
    }

    private List<Map<String, String>> items(Tenant t, String feedToken) throws Exception {
        return FeedXml.items(feedBody(t, feedToken));
    }

    @Test
    void servesOneItemPerActiveVariantAndPerSimpleProduct() throws Exception {
        String mug = insertProduct(acme, simple("Mug", 250, 4)
                .append("description", "<p>Holds <b>tea</b> &amp; coffee.</p>"));
        String tee = insertProduct(acme, tee(variant("tee-s", "S", 499, 5, "ACTIVE"),
                variant("tee-m", "M", 549, 0, "ACTIVE")).append("brand", "Teesmiths"));

        MvcResult res = fetch(acme, token).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/rss+xml"))
                .andExpect(content().encoding("UTF-8"))
                .andExpect(header().string("Cache-Control", "max-age=900, public"))
                .andExpect(header().string("ETag", matchesPattern("\"[0-9a-f]{32}\"")))
                .andReturn();
        List<Map<String, String>> items = FeedXml.items(res.getResponse().getContentAsByteArray());

        assertThat(items).hasSize(3);
        assertThat(items.get(0)).containsExactly(
                entry("g:id", mug), entry("title", "Mug"), entry("description", "Holds tea & coffee."),
                entry("link", "https://shop.acme.test/product/" + mug),
                entry("g:image_link", "https://shop.acme.test/uploads/p/Mug.png"),
                entry("g:availability", "in stock"), entry("g:price", "250.00 USD"),
                entry("g:brand", "Acme Shop"), entry("g:condition", "new"));
        assertThat(items.get(1)).contains(entry("g:id", tee + "-tee-s"), entry("g:item_group_id", tee),
                entry("title", "Tee - S / Black"), entry("description", "Tee"),
                entry("link", "https://shop.acme.test/product/" + tee),
                entry("g:image_link", "https://shop.acme.test/uploads/p/tee-black.png"),
                entry("g:availability", "in stock"), entry("g:price", "499.00 USD"),
                entry("g:brand", "Teesmiths"));
        assertThat(items.get(2)).contains(entry("g:id", tee + "-tee-m"), entry("g:item_group_id", tee),
                entry("g:availability", "out of stock"), entry("g:price", "549.00 USD"));
    }

    @Test
    void aClientWithTheCurrentETagGetsNotModified() throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        String etag = fetch(acme, token).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");

        mvc.perform(get(feedPath(token)).with(host(hostOf(acme))).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void leavesOutHiddenProductsDisabledVariantsAndProductsWithoutAnImage() throws Exception {
        insertProduct(acme, simple("Draft", 10, 1).append("status", "DRAFT"));
        insertProduct(acme, simple("Archived", 10, 1).append("status", "ARCHIVED"));
        insertProduct(acme, simple("No photo", 10, 1).append("photo", null));
        String legacy = insertProduct(acme, simple("Legacy", 10, 1).append("status", null));
        String tee = insertProduct(acme, tee(variant("tee-s", "S", 499, 5, "ACTIVE"),
                variant("tee-m", "M", 549, 3, "DISABLED")));

        assertThat(items(acme, token)).extracting(i -> i.get("g:id")).containsExactly(legacy, tee + "-tee-s");
    }

    @Test
    void escapesMarkupAndDropsCharactersXmlCannotCarry() throws Exception {
        insertProduct(acme, simple("x", 10, 1).append("name", "Tom & Jerry <\"Mugs\">\u0001"));

        byte[] body = feedBody(acme, token);
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("Tom &amp; Jerry &lt;").doesNotContain("\u0001");
        assertThat(FeedXml.items(body).get(0).get("title")).isEqualTo("Tom & Jerry <\"Mugs\">");
    }

    @Test
    void listsNoMoreProductsThanTheStoreMayHold() throws Exception {
        Tenant small = feedStore("small", "Small", "INR", new TenantLimits(2, null, null));
        String smallToken = enableFeed(small, adminTokenFor(small));
        String one = insertProduct(small, simple("One", 1, 1));
        String two = insertProduct(small, simple("Two", 1, 1));
        insertProduct(small, simple("Three", 1, 1));

        assertThat(items(small, smallToken)).extracting(i -> i.get("g:id")).containsExactly(one, two);
    }

    @Test
    void theFeedIsCachedUntilAProductChanges() throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        assertThat(items(acme, token)).hasSize(1);
        insertProduct(acme, simple("Bowl", 120, 2)); // straight into the database: no product event
        assertThat(items(acme, token)).hasSize(1);

        mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .with(host(hostOf(acme))).param("name", "Plate").param("price", "90").param("stock", "5")
                        .param("category", "home").header("Authorization", bearer(admin)))
                .andExpect(status().isCreated());

        assertThat(items(acme, token)).extracting(i -> i.get("title")).containsExactly("Mug", "Bowl", "Plate");
    }

    @Test
    void turningTheFeedOffAndOnRebuildsIt() throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        assertThat(items(acme, token)).hasSize(1);
        insertProduct(acme, simple("Bowl", 120, 2));

        setFeed(acme, admin, false);
        setFeed(acme, admin, true);

        assertThat(items(acme, token)).hasSize(2);
    }

    @Test
    void anythingButTheRightTokenOnAnEnabledFeedIsAnEmptyNotFound() throws Exception {
        insertProduct(acme, simple("Mug", 250, 4));
        String wrong = token.substring(0, 42) + (token.endsWith("A") ? "B" : "A");

        fetch(acme, wrong).andExpect(status().isNotFound()).andExpect(content().string(""));
        fetch(acme, "x").andExpect(status().isNotFound()).andExpect(content().string(""));

        setFeed(acme, admin, false);
        fetch(acme, token).andExpect(status().isNotFound());
        setFeed(acme, admin, true);
        fetch(acme, token).andExpect(status().isOk()); // the same address works again

        marketing(acme, false);
        fetch(acme, token).andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    @Test
    void sixtyFetchesAMinutePerClientThenTooManyRequests() throws Exception {
        // The limiter counts per clock minute: start well inside one so all 62 calls share a window.
        while (System.currentTimeMillis() % 60_000 > 50_000) {
            Thread.sleep(250);
        }
        for (int i = 0; i < 60; i++) {
            mvc.perform(get(feedPath(token)).with(host(hostOf(acme))).with(from("10.6.6.6")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(feedPath(token)).with(host(hostOf(acme))).with(from("10.6.6.6")))
                .andExpect(status().isTooManyRequests());
        // Guessing another token buys no fresh budget.
        mvc.perform(get(feedPath("guess")).with(host(hostOf(acme))).with(from("10.6.6.6")))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get(feedPath(token)).with(host(hostOf(acme))).with(from("10.7.7.7")))
                .andExpect(status().isOk());
    }
}
