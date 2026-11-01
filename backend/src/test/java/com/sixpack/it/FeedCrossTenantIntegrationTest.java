package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.marketing.FeedXml;
import com.sixpack.tenant.domain.Tenant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One store's feed token, products and cache never reach another store. */
class FeedCrossTenantIntegrationTest extends FeedIntegrationTest {

    Tenant acme;
    Tenant globex;
    String acmeAdmin;
    String acmeFeed;
    String globexFeed;

    @BeforeEach
    void twoStoresWithFeeds() throws Exception {
        acme = feedStore("acme", "Acme Shop", "INR", null);
        globex = feedStore("globex", "Globex", "EUR", null);
        acmeAdmin = adminTokenFor(acme);
        acmeFeed = enableFeed(acme, acmeAdmin);
        globexFeed = enableFeed(globex, adminTokenFor(globex));
        insertProduct(acme, simple("Acme Anvil", 100, 3));
        insertProduct(globex, simple("Globex Gizmo", 50, 1));
    }

    private List<Map<String, String>> items(Tenant t, String feedToken) throws Exception {
        return FeedXml.items(feedBody(t, feedToken));
    }

    @Test
    void aStoresTokenOpensNothingOnAnotherStoresHost() throws Exception {
        fetch(globex, acmeFeed).andExpect(status().isNotFound());
        fetch(acme, globexFeed).andExpect(status().isNotFound());
    }

    @Test
    void eachFeedListsOnlyItsOwnStoresProductsAlsoFromCache() throws Exception {
        assertThat(items(acme, acmeFeed)).extracting(i -> i.get("title")).containsExactly("Acme Anvil");
        assertThat(items(globex, globexFeed)).extracting(i -> i.get("title")).containsExactly("Globex Gizmo");

        List<Map<String, String>> cached = items(acme, acmeFeed);
        assertThat(cached).extracting(i -> i.get("title")).containsExactly("Acme Anvil");
        assertThat(cached.get(0).get("link")).startsWith("https://shop.acme.test/");
        assertThat(cached.get(0).get("g:brand")).isEqualTo("Acme Shop");
        assertThat(cached.get(0).get("g:price")).isEqualTo("100.00 INR");
        assertThat(items(globex, globexFeed).get(0).get("g:price")).isEqualTo("50.00 EUR");
    }

    @Test
    void aStoreThatNeverTurnedItsFeedOnServesNothing() throws Exception {
        Tenant initech = feedStore("initech", "Initech", "INR", null);
        insertProduct(initech, simple("Stapler", 5, 1));

        fetch(initech, acmeFeed).andExpect(status().isNotFound());
    }

    @Test
    void oneStoresAdminCannotChangeAnotherStoresFeed() throws Exception {
        setFeed(globex, acmeAdmin, false).andExpect(status().isUnauthorized());
        rotate(globex, acmeAdmin).andExpect(status().isUnauthorized());

        fetch(globex, globexFeed).andExpect(status().isOk());
    }
}
