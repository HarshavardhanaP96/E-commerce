package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Admin → Marketing product feed: on/off, the URL, a new URL, roles, the feature switch and audit. */
class FeedAdminIntegrationTest extends FeedIntegrationTest {

    Tenant acme;
    String admin;

    @BeforeEach
    void store() throws Exception {
        acme = feedStore("acme", "Acme Shop", "INR", null);
        admin = adminTokenFor(acme);
    }

    @Test
    void withTheFeatureOffTheFeedEndpointsAreNotFound() throws Exception {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        String token = adminToken();

        setFeed(def, token, true).andExpect(status().isNotFound());
        rotate(def, token).andExpect(status().isNotFound());
        assertThat(mongo.getCollection("marketing_settings").countDocuments()).isZero();
    }

    @Test
    void theMarketingPageGetsTheFeedInItsOneCall() throws Exception {
        JsonNode body = body(list(acme, admin).andExpect(status().isOk()).andReturn());

        assertThat(fieldNames(body)).containsExactly("success", "integrations", "feed");
        assertThat(fieldNames(body.get("feed"))).containsExactly("enabled", "url");
        assertThat(body.get("feed").get("enabled").asBoolean()).isFalse();
        assertThat(body.get("feed").get("url").isNull()).isTrue();
    }

    @Test
    void turningTheFeedOnGivesAUrlOnTheStoresDomain() throws Exception {
        JsonNode feed = body(setFeed(acme, admin, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andReturn()).get("feed");

        assertThat(feed.get("enabled").asBoolean()).isTrue();
        Matcher url = FEED_URL.matcher(feed.get("url").asText());
        assertThat(url.matches()).isTrue();
        assertThat(url.group(1)).isEqualTo("shop.acme.test");
        list(acme, admin).andExpect(jsonPath("$.feed.enabled").value(true))
                .andExpect(jsonPath("$.feed.url").value(feed.get("url").asText()));

        Document row = mongo.getCollection("marketing_settings").find(new Document("tenantId", acme.id())).first();
        assertThat(String.valueOf(row.get("_id"))).isEqualTo(acme.id());
        assertThat(row.getBoolean("feedEnabled")).isTrue();
        assertThat(row.getString("feedToken")).isEqualTo(url.group(2));
    }

    @Test
    void turningItOffHidesTheUrlAndTurningItOnBringsTheSameUrlBack() throws Exception {
        String token = enableFeed(acme, admin);

        setFeed(acme, admin, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.enabled").value(false))
                .andExpect(jsonPath("$.feed.url").value(nullValue()));
        list(acme, admin).andExpect(jsonPath("$.feed.url").value(nullValue()));

        assertThat(enableFeed(acme, admin)).isEqualTo(token);
    }

    @Test
    void aNewUrlReplacesTheOldOne() throws Exception {
        String old = enableFeed(acme, admin);

        String fresh = tokenOf(body(rotate(acme, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.feed.enabled").value(true)).andReturn()).get("feed").get("url").asText());

        assertThat(fresh).isNotEqualTo(old);
        list(acme, admin).andExpect(jsonPath("$.feed.url").value(containsString(fresh)));
        assertThat(mongo.getCollection("marketing_settings").find().first().getString("feedToken")).isEqualTo(fresh);
    }

    @Test
    void aNewUrlNeedsTheFeedOn() throws Exception {
        rotate(acme, admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("feed: enable it first"));

        enableFeed(acme, admin);
        setFeed(acme, admin, false).andExpect(status().isOk());
        rotate(acme, admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("feed: enable it first"));
        assertThat(auditEntries("FEED_TOKEN_ROTATED")).isEmpty();
    }

    @Test
    void theBodyMustSayOnOrOff() throws Exception {
        mvc.perform(put(FEED).with(host(hostOf(acme))).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("enabled: is required"));
    }

    @Test
    void onlyTheStoresAdminMayChangeTheFeed() throws Exception {
        String customer = registerOn(acme, "buyer@acme.test");

        setFeed(acme, customer, true).andExpect(status().isForbidden());
        rotate(acme, customer).andExpect(status().isForbidden());
        mvc.perform(put(FEED).with(host(hostOf(acme))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isUnauthorized());
        assertThat(mongo.getCollection("marketing_settings").countDocuments()).isZero();
    }

    @Test
    void changesAreAuditedWithoutTheToken() throws Exception {
        String first = enableFeed(acme, admin);
        setFeed(acme, admin, true).andExpect(status().isOk()); // no change, no entry
        String second = tokenOf(body(rotate(acme, admin).andExpect(status().isOk()).andReturn())
                .get("feed").get("url").asText());
        setFeed(acme, admin, false).andExpect(status().isOk());

        List<Document> updated = auditEntries("FEED_UPDATED");
        assertThat(updated).hasSize(2);
        Document on = updated.get(1);
        assertThat(on.getString("tenantId")).isEqualTo(acme.id());
        assertThat(on.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(on.getString("entityType")).isEqualTo("marketing_settings");
        assertThat(on.getString("entityId")).isEqualTo("feed");
        assertThat(changeOf(on, "enabled").get("before")).isEqualTo(false);
        assertThat(changeOf(on, "enabled").get("after")).isEqualTo(true);
        assertThat(changeOf(on, "feedToken").getBoolean("redacted")).isTrue();
        Document off = updated.get(0);
        assertThat(off.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("enabled");

        List<Document> rotated = auditEntries("FEED_TOKEN_ROTATED");
        assertThat(rotated).hasSize(1);
        assertThat(rotated.get(0).getString("entityId")).isEqualTo("feed");
        assertThat(rotated.get(0).getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("feedToken");
        assertThat(changeOf(rotated.get(0), "feedToken").getBoolean("redacted")).isTrue();

        String everything = mongo.getCollection("audit_log").find(new Document("tenantId", acme.id()))
                .map(Document::toJson).into(new ArrayList<>()).toString();
        assertThat(everything).doesNotContain(first).doesNotContain(second);
    }
}
