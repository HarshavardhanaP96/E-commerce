package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Sales and Products narrowed to a gender and/or age band. A narrowed result is shown only when the same item of
 * that period's audience table (Customers tab) is shown.
 *
 * <p>October 2026, every buyer 25-34 on 31 Oct: 6 women buy a Tee (100), 6 men a Mug (200), 2 buyers of unknown
 * gender (no gender, "other") a Cap (50); a guest buys a Bag (1000). The unknown row is small, so the table also
 * hides the female total (else the grand total minus female and male would give the 2 away): female is hidden
 * although it has 6 buyers. Shown: male, 25-34, male/25-34.
 *
 * <p>September 2026: the same 6 men buy a Mug again (15 Sep); 6 women who turn 25 on 20 Oct buy a Tee (20 Sep),
 * so they are 18-24 at the end of September. Another shop's man (25-34) buys a Mug for 9999 on 11 Oct.
 */
class AudienceFilterIntegrationTest extends IntegrationTest {

    private static final String TEE = new ObjectId().toHexString();
    private static final String MUG = new ObjectId().toHexString();
    private static final String CAP = new ObjectId().toHexString();
    private static final String BAG = new ObjectId().toHexString();

    private String admin;
    private String tenant;

    @BeforeEach
    void seed() throws Exception {
        admin = adminToken();
        tenant = tenantRepository.findBySlug("default").orElseThrow().id();
        product(TEE, "Tee", "clothing");
        product(MUG, "Mug", "home");
        product(CAP, "Cap", "hats");
        product(BAG, "Bag", "bags");
        for (int i = 0; i < 6; i++) {
            order(tenant, user(tenant, "female", "1995-05-05"), "2026-10-10T06:00:00Z", TEE, 100);
            String man = user(tenant, "male", "1995-05-05");
            order(tenant, man, "2026-10-11T06:00:00Z", MUG, 200);
            order(tenant, man, "2026-09-15T06:00:00Z", MUG, 200);
            order(tenant, user(tenant, "female", "2001-10-20"), "2026-09-20T06:00:00Z", TEE, 100);
        }
        order(tenant, user(tenant, null, "1995-05-05"), "2026-10-12T06:00:00Z", CAP, 50);
        order(tenant, user(tenant, "other", "1995-05-05"), "2026-10-12T06:00:00Z", CAP, 50);
        order(tenant, null, "2026-10-13T06:00:00Z", BAG, 1000); // a guest: in no segment
        order("another-shop", user("another-shop", "male", "1995-05-05"), "2026-10-11T06:00:00Z", MUG, 9999);
    }

    private ResultActions october(String path, String... params) throws Exception {
        return call(path, "2026-10-01", "2026-10-31", params);
    }

    private ResultActions call(String path, String from, String to, String... params) throws Exception {
        var req = get("/api/v1/dashboard/" + path).header("Authorization", bearer(admin))
                .param("from", from).param("to", to);
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req);
    }

    private JsonNode octoberJson(String path, String... params) throws Exception {
        return body(october(path, params).andExpect(status().isOk()).andReturn());
    }

    private static double sum(JsonNode series) {
        double total = 0;
        for (JsonNode p : series) {
            total += p.get("revenue").asDouble();
        }
        return total;
    }

    // --- Sales ---

    @Test
    void salesNarrowedToAGenderLeaveOtherSegmentsAndGuestsOut() throws Exception {
        assertThat(sum(octoberJson("sales").get("section").get("series"))).isEqualTo(2900.0);

        JsonNode male = octoberJson("sales", "gender", "male").get("section");
        assertThat(male.get("small").asBoolean()).isFalse();
        assertThat(sum(male.get("series"))).isEqualTo(1200.0); // not the other shop's 9999
        assertThat(male.get("series").get(10).get("revenue").asDouble()).isEqualTo(1200.0);
        assertThat(male.get("breakdown").get("charged").asDouble()).isEqualTo(1200.0);

        JsonNode young = octoberJson("sales", "ageBand", "25-34").get("section");
        assertThat(sum(young.get("series"))).isEqualTo(1900.0); // everyone signed in, not the guest's bag

        JsonNode both = octoberJson("sales", "gender", "male", "ageBand", "25-34").get("section");
        assertThat(sum(both.get("series"))).isEqualTo(1200.0);
    }

    @Test
    void aSmallSegmentShowsNoFigures() throws Exception {
        october("sales", "gender", "unknown").andExpect(status().isOk())
                .andExpect(jsonPath("$.section.small").value(true))
                .andExpect(jsonPath("$.section.series.length()").value(0))
                .andExpect(jsonPath("$.section.previousSeries").doesNotExist())
                .andExpect(jsonPath("$.section.breakdown").doesNotExist());
        // nobody at all in the band: hidden the same way
        october("sales", "ageBand", "55+").andExpect(jsonPath("$.section.small").value(true))
                .andExpect(jsonPath("$.section.series.length()").value(0));
    }

    @Test
    void aSegmentTheAudienceTableHidesIsHiddenEvenWithFiveOrMoreBuyers() throws Exception {
        october("customers").andExpect(jsonPath("$.customers.audience.rows[0].total.small").value(true));
        october("sales", "gender", "female").andExpect(jsonPath("$.section.small").value(true))
                .andExpect(jsonPath("$.section.series.length()").value(0));
        october("sales", "gender", "female", "ageBand", "25-34")
                .andExpect(jsonPath("$.section.small").value(true));
        october("products", "gender", "female").andExpect(jsonPath("$.products.small").value(true))
                .andExpect(jsonPath("$.products.top.length()").value(0));
    }

    @Test
    void filteredFiguresMatchTheAudienceTableSoDifferencingRecoversNoHiddenGroup() throws Exception {
        JsonNode audience = octoberJson("customers").get("customers").get("audience");
        JsonNode rows = audience.get("rows");
        for (int g = 0; g < 4; g++) {
            String gender = g < 3 ? rows.get(g).get("gender").asText() : null;
            for (int b = 0; b <= 6; b++) {
                String band = b < 6 ? audience.get("bands").get(b).asText() : null;
                if (gender == null && band == null) {
                    continue;
                }
                JsonNode item = b < 6 ? rows.get(g).get("cells").get(b) : rows.get(g).get("total");
                java.util.List<String> params = new java.util.ArrayList<>();
                if (gender != null) {
                    params.addAll(List.of("gender", gender));
                }
                if (band != null) {
                    params.addAll(List.of("ageBand", band));
                }
                JsonNode sales = octoberJson("sales", params.toArray(String[]::new)).get("section");
                String what = gender + " / " + band;
                assertThat(sales.get("small").asBoolean()).as(what).isEqualTo(item.get("small").asBoolean());
                if (!item.get("small").asBoolean()) {
                    // the same buyers as the table's item: its revenue, no more, no less
                    assertThat(sum(sales.get("series"))).as(what).isEqualTo(item.get("revenue").asDouble());
                }
            }
        }
        // Of the genders only male is shown: everyone signed in (1900) minus male (1200) is women and unknown
        // together (8 buyers); neither small group can be told apart from it.
        october("sales", "gender", "male").andExpect(jsonPath("$.section.small").value(false));
        october("sales", "gender", "female").andExpect(jsonPath("$.section.small").value(true));
        october("sales", "gender", "unknown").andExpect(jsonPath("$.section.small").value(true));
    }

    @Test
    void theComparisonUsesTheSameSegmentWithAgesAtItsOwnEnd() throws Exception {
        JsonNode male = octoberJson("sales", "gender", "male").get("section");
        assertThat(male.get("previousSmall").asBoolean()).isFalse();
        assertThat(male.get("previousSeries").get(15).get("revenue").asDouble()).isEqualTo(1200.0); // 15 Sep
        assertThat(sum(male.get("previousSeries"))).isEqualTo(1200.0);

        // the six who turn 25 on 20 Oct are 25-34 now but were 18-24 at the end of September
        JsonNode young = octoberJson("sales", "ageBand", "25-34").get("section");
        assertThat(sum(young.get("previousSeries"))).isEqualTo(1200.0);
        assertThat(young.get("previousSeries").get(20).get("revenue").asDouble()).isEqualTo(0.0);
    }

    @Test
    void aComparisonTheTableHidesIsDroppedAndSaysWhy() throws Exception {
        // September's men are shown; the period before it (August) has no buyers, so its male total is hidden
        call("sales", "2026-09-01", "2026-09-30", "gender", "male").andExpect(status().isOk())
                .andExpect(jsonPath("$.section.small").value(false))
                .andExpect(jsonPath("$.section.series[14].revenue").value(1200.0)) // 15 Sep
                .andExpect(jsonPath("$.section.previousSmall").value(true))
                .andExpect(jsonPath("$.section.previousSeries").doesNotExist());
        // without a segment the comparison is the whole store's, as before
        call("sales", "2026-09-01", "2026-09-30").andExpect(jsonPath("$.section.previousSmall").value(false))
                .andExpect(jsonPath("$.section.previousSeries.length()").value(30));
    }

    @Test
    void salesCsvHoldsOnlyTheSegmentAndNothingWhenHidden() throws Exception {
        String male = october("sales", "gender", "male", "format", "csv").andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("sales-male-2026-10-01")))
                .andReturn().getResponse().getContentAsString();
        assertThat(male.lines().toList()).contains("2026-10-11,1200,6,0");
        String female = october("sales", "gender", "female", "format", "csv").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(female.lines().toList()).containsExactly("start,revenue,orders,previousRevenue");
    }

    @Test
    void badSegmentValuesAreRejected() throws Exception {
        for (String path : new String[] {"sales", "products", "export/top-products"}) {
            october(path, "gender", "other").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("gender: unknown value"));
            october(path, "ageBand", "55").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("ageBand: unknown value"));
        }
    }

    // --- Products ---

    @Test
    void productsTopSellersAndCategoriesFollowTheSegment() throws Exception {
        // cached per segment: the whole store before and after a narrowed read stays the whole store
        october("products").andExpect(jsonPath("$.products.top.length()").value(4));
        october("products", "gender", "male").andExpect(status().isOk())
                .andExpect(jsonPath("$.products.small").value(false))
                .andExpect(jsonPath("$.products.top.length()").value(1))
                .andExpect(jsonPath("$.products.top[0].name").value("Mug"))
                .andExpect(jsonPath("$.products.top[0].units").value(6.0))
                .andExpect(jsonPath("$.products.top[0].revenue").value(1200.0))
                .andExpect(jsonPath("$.products.top[0].orders").value(6))
                .andExpect(jsonPath("$.products.categories.length()").value(1))
                .andExpect(jsonPath("$.products.categories[0].category").value("home"))
                .andExpect(jsonPath("$.products.categories[0].share").value(100.0))
                // nothing else was sold to men: the other three are "not sold in this range" for them
                .andExpect(jsonPath("$.products.idle[*].name").value(containsInAnyOrder("Tee", "Cap", "Bag")))
                // stock cover is about the whole store's selling rate: not shown for a segment
                .andExpect(jsonPath("$.products.stock").doesNotExist());
        october("products").andExpect(jsonPath("$.products.top.length()").value(4))
                .andExpect(jsonPath("$.products.stock").exists());
        october("products", "ageBand", "25-34")
                .andExpect(jsonPath("$.products.top[*].name").value(containsInAnyOrder("Mug", "Tee", "Cap")));
    }

    @Test
    void productsTrendingComparesTheSegmentWithAgesAtThePreviousPeriodsEnd() throws Exception {
        // 25-34 in September was only the men's mugs; the women's tees then were bought at 24
        october("products", "ageBand", "25-34").andExpect(status().isOk())
                .andExpect(jsonPath("$.products.previousSmall").value(false))
                .andExpect(jsonPath("$.products.trending.rising[0].name").value("Tee"))
                .andExpect(jsonPath("$.products.trending.rising[0].previousUnits").value(0.0))
                .andExpect(jsonPath("$.products.trending.rising[0].isNew").value(true));
        // with no segment September's tees count: Tee sold 6 both months
        october("products").andExpect(jsonPath("$.products.trending.rising[*].name")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Tee"))));
    }

    @Test
    void productsTrendingIsDroppedWhenThePreviousPeriodHidesTheSegment() throws Exception {
        call("products", "2026-09-01", "2026-09-30", "gender", "male").andExpect(status().isOk())
                .andExpect(jsonPath("$.products.small").value(false))
                .andExpect(jsonPath("$.products.top[0].name").value("Mug"))
                .andExpect(jsonPath("$.products.previousSmall").value(true))
                .andExpect(jsonPath("$.products.trending").doesNotExist());
    }

    @Test
    void aHiddenProductsSegmentHasNoRowsAnywhere() throws Exception {
        october("products", "gender", "unknown").andExpect(status().isOk())
                .andExpect(jsonPath("$.products.small").value(true))
                .andExpect(jsonPath("$.products.top.length()").value(0))
                .andExpect(jsonPath("$.products.categories.length()").value(0))
                .andExpect(jsonPath("$.products.idle.length()").value(0))
                .andExpect(jsonPath("$.products.boughtTogether.length()").value(0))
                .andExpect(jsonPath("$.products.trending").doesNotExist())
                .andExpect(jsonPath("$.products.stock").doesNotExist());
    }

    @Test
    void productExportsFollowTheSegment() throws Exception {
        String male = october("export/top-products", "gender", "male").andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("top-products-male_")))
                .andReturn().getResponse().getContentAsString();
        assertThat(male.substring(1).lines().toList()).containsExactly("product_id,product,units,revenue,orders",
                MUG + ",Mug,6,1200,6");
        String hidden = october("export/categories", "gender", "unknown").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(hidden.substring(1).lines().toList()).containsExactly("category,revenue,units,share_percent");
        assertThat(auditEntries("ANALYTICS_EXPORTED")).extracting(a -> a.getString("entityId"))
                .containsExactlyInAnyOrder("top-products", "categories");
    }

    // --- unknown covers accounts that are gone ---

    @Test
    void unknownGenderAndAgeIncludeGoneAndLegacyAccounts() throws Exception {
        String at = "2026-07-10T06:00:00Z";
        for (int i = 0; i < 5; i++) {
            order(tenant, user(tenant, "female", "1995-05-05"), at, TEE, 100);
        }
        order(tenant, user(tenant, null, null), at, CAP, 10);
        order(tenant, user(tenant, "other", null), at, CAP, 20);
        order(tenant, new ObjectId().toHexString(), at, CAP, 30); // the account is gone
        order(tenant, "legacy-9", at, CAP, 40); // not an ObjectId
        order(tenant, user("another-shop", "male", "1995-05-05"), at, CAP, 50); // an account of another shop
        for (String[] segment : new String[][] {{"gender", "unknown"}, {"ageBand", "unknown"},
                {"gender", "unknown", "ageBand", "unknown"}}) {
            JsonNode s = body(call("sales", "2026-07-01", "2026-07-31", segment).andExpect(status().isOk())
                    .andReturn()).get("section");
            assertThat(s.get("small").asBoolean()).as(String.join(" ", segment)).isFalse();
            assertThat(sum(s.get("series"))).as(String.join(" ", segment)).isEqualTo(150.0);
        }
    }

    private void product(String id, String name, String category) {
        mongo.getCollection("products").insertOne(new Document("_id", new ObjectId(id)).append("tenantId", tenant)
                .append("name", name).append("price", 100).append("stock", 3).append("category", category)
                .append("status", "ACTIVE").append("createdAt", Date.from(Instant.parse("2026-01-01T00:00:00Z"))));
    }

    private String user(String tenantId, String gender, String dob) {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(new Document("_id", id).append("tenantId", tenantId)
                .append("email", id.toHexString() + "@shop.test").append("role", "user").append("gender", gender)
                .append("dob", dob == null ? null : Date.from(Instant.parse(dob + "T00:00:00Z"))));
        return id.toHexString();
    }

    private void order(String tenantId, String user, String at, String productId, double price) {
        mongo.getCollection("orders").insertOne(new Document("_id", new ObjectId()).append("tenantId", tenantId)
                .append("user", user).append("status", "Processing").append("total", price).append("discount", 0.0)
                .append("tax", 0.0).append("shippingCharges", 0.0)
                .append("orderItems", List.of(new Document("productId", new ObjectId(productId))
                        .append("name", productName(productId)).append("price", price).append("quantity", 1)))
                .append("payment", new Document("status", "PAID").append("method", "upi"))
                .append("createdAt", Date.from(Instant.parse(at))));
    }

    private static String productName(String id) {
        return id.equals(TEE) ? "Tee" : id.equals(MUG) ? "Mug" : id.equals(CAP) ? "Cap" : "Bag";
    }
}
