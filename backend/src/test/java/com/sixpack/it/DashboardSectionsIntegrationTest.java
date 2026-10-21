package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The shop dashboard's sections (analytics step 3): products, customers, coupons, payments and tax for a date
 * range in the store's time zone, each table downloadable as CSV.
 *
 * <p>October 2026 in this store: O1 Asha 5 Oct (2 Tee, WELCOME, UPI, Bengaluru), O2 Asha 12 Oct (1 Mug, card,
 * Mysuru, 100 refunded on 14 Oct), O3 Ravi 20 Oct (1 Tee + 1 Mug, WELCOME, UPI, Pune). Ravi also ordered in August.
 */
@TestPropertySource(properties = "app.invoice.seller-state=Karnataka")
class DashboardSectionsIntegrationTest extends IntegrationTest {

    private static final String TEE = new ObjectId().toHexString();
    private static final String MUG = new ObjectId().toHexString();
    private static final String CAP = new ObjectId().toHexString();
    private static final String DRAFT = new ObjectId().toHexString();
    /** Made last, so it sorts after the others: a product no longer in the catalogue. */
    private static final String GONE = new ObjectId().toHexString();
    private static final String LEGACY = "legacy-42";

    private String admin;
    private String tenant;

    @BeforeEach
    void seed() throws Exception {
        admin = adminToken();
        tenant = tenantRepository.findBySlug("default").orElseThrow().id();
        product(TEE, "Tee", "clothing", 500, 3, "ACTIVE");
        product(MUG, "Mug", "home", 200, 50, "ACTIVE");
        product(CAP, "Cap", "clothing", 300, 0, "ACTIVE");
        product(DRAFT, "Draft hat", "clothing", 100, 0, "DRAFT");

        order(tenant, "u1", "Asha", "asha@shop.test", "2026-10-05T06:00:00Z", 1080, 100, 180, "WELCOME", "upi",
                "Karnataka", "Bengaluru", null, List.of(item(TEE, "Tee", 500, 2, 18.0)));
        order(tenant, "u1", "Asha", "asha@shop.test", "2026-10-12T06:00:00Z", 224, 0, 24, null, "card",
                "Karnataka", "Mysuru", refund(100, "2026-10-14T06:00:00Z"), List.of(item(MUG, "Mug", 200, 1, 12.0)));
        order(tenant, "u2", "Ravi", "ravi@shop.test", "2026-10-20T06:00:00Z", 764, 50, 114, "WELCOME", "upi",
                "Maharashtra", "Pune", null, List.of(item(TEE, "Tee", 500, 1, 18.0), item(MUG, "Mug", 200, 1, 12.0)));
        order(tenant, "u2", "Ravi", "ravi@shop.test", "2026-08-01T06:00:00Z", 224, 0, 24, null, "card",
                "Maharashtra", "Pune", null, List.of(item(MUG, "Mug", 200, 1, 12.0)));
        order("another-shop", "x1", "Other", "o@other.test", "2026-10-10T06:00:00Z", 9999, 0, 0, "WELCOME", "upi",
                "Karnataka", "Bengaluru", null, List.of(item(TEE, "Tee", 9999, 1, 18.0)));
    }

    private ResultActions section(String name) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/" + name).header("Authorization", bearer(admin))
                .param("from", "2026-10-01").param("to", "2026-10-31"));
    }

    @Test
    void productsShowsTopSellersCategoryShareStockAndIdleProducts() throws Exception {
        section("products").andExpect(status().isOk())
                .andExpect(jsonPath("$.products.top.length()").value(2))
                .andExpect(jsonPath("$.products.top[0].name").value("Tee"))
                .andExpect(jsonPath("$.products.top[0].units").value(3.0))
                .andExpect(jsonPath("$.products.top[0].revenue").value(1500.0))
                .andExpect(jsonPath("$.products.top[0].orders").value(2))
                .andExpect(jsonPath("$.products.top[1].name").value("Mug"))
                .andExpect(jsonPath("$.products.categories[0].category").value("clothing"))
                .andExpect(jsonPath("$.products.categories[0].share").value(78.9))
                .andExpect(jsonPath("$.products.categories[1].category").value("home"))
                // low and out of stock, emptiest first; days left at the period's selling rate
                .andExpect(jsonPath("$.products.stock.length()").value(2))
                .andExpect(jsonPath("$.products.stock[0].name").value("Cap"))
                .andExpect(jsonPath("$.products.stock[0].daysLeft").value(0))
                .andExpect(jsonPath("$.products.stock[1].name").value("Tee"))
                .andExpect(jsonPath("$.products.stock[1].stock").value(3))
                .andExpect(jsonPath("$.products.stock[1].daysLeft").value(31))
                // on sale but not sold in the period; drafts are left out
                .andExpect(jsonPath("$.products.idle.length()").value(1))
                .andExpect(jsonPath("$.products.idle[0].name").value("Cap"));
    }

    /**
     * December 2026 against the 31 days before it (31 Oct – 30 Nov). Tee 2 → 8, Mug 6 → 5, Cap 0 → 4, the removed
     * product 0 → 3, a legacy string id 0 → 5, Draft hat 2 → 1 (too few). Tee and Mug share four orders, the
     * removed product shares three with each; Cap shares only two. One order of 21 products is skipped for pairs,
     * one order has no items at all, and gift wrap (no product id) never pairs or trends.
     */
    private void seedDecember() {
        List<Document> filler = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            filler.add(item(new ObjectId().toHexString(), "Filler " + i, 10, 1, 18.0));
        }
        List<Document> big = new ArrayList<>(List.of(item(TEE, "Tee", 500, 1, 18.0), item(MUG, "Mug XL", 200, 1, 12.0)));
        big.addAll(filler);
        // the latest order is saved first: its item names are still the ones shown
        sale("2026-12-20T06:00:00Z", big);
        sale("2026-12-02T06:00:00Z", List.of(item(TEE, "Tee", 500, 2, 18.0), item(MUG, "Mug", 200, 1, 12.0),
                unnamed(GONE, 1), giftWrap()));
        sale("2026-12-05T06:00:00Z", List.of(item(TEE, "Tee", 500, 2, 18.0), item(MUG, "Mug", 200, 1, 12.0),
                unnamed(GONE, 1), giftWrap()));
        sale("2026-12-09T06:00:00Z", List.of(item(TEE, "Tee", 500, 2, 18.0), item(MUG, "Mug", 200, 1, 12.0),
                unnamed(GONE, 1), unnamed(CAP, 2), giftWrap()));
        sale("2026-12-14T06:00:00Z", List.of(item(TEE, "Tee", 500, 1, 18.0), item(MUG, "Mug", 200, 1, 12.0),
                unnamed(CAP, 2), item(DRAFT, "Draft hat", 100, 1, 18.0)));
        sale("2026-12-15T06:00:00Z", null);
        sale("2026-12-16T06:00:00Z", List.of(new Document("productId", LEGACY).append("price", 50).append("quantity", 5)));
        sale("2026-11-10T06:00:00Z", List.of(item(TEE, "Tee", 500, 2, 18.0)));
        sale("2026-11-12T06:00:00Z", List.of(item(MUG, "Mug", 200, 6, 12.0)));
        sale("2026-11-14T06:00:00Z", List.of(item(DRAFT, "Draft hat", 100, 2, 18.0)));
    }

    private ResultActions december(String path) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/" + path).header("Authorization", bearer(admin))
                .param("from", "2026-12-01").param("to", "2026-12-31"));
    }

    @Test
    void productsShowRisingAndFallingAgainstThePreviousPeriod() throws Exception {
        seedDecember();
        december("products").andExpect(status().isOk())
                // ranked by the change in units; at least 3 units in one of the periods
                .andExpect(jsonPath("$.products.trending.rising.length()").value(4))
                .andExpect(jsonPath("$.products.trending.rising[0].productId").value(TEE))
                .andExpect(jsonPath("$.products.trending.rising[0].name").value("Tee"))
                .andExpect(jsonPath("$.products.trending.rising[0].units").value(8.0))
                .andExpect(jsonPath("$.products.trending.rising[0].previousUnits").value(2.0))
                .andExpect(jsonPath("$.products.trending.rising[0].change").value(300))
                .andExpect(jsonPath("$.products.trending.rising[0].isNew").value(false))
                .andExpect(jsonPath("$.products.trending.rising[1].productId").value(LEGACY))
                .andExpect(jsonPath("$.products.trending.rising[1].name").value("Removed product"))
                .andExpect(jsonPath("$.products.trending.rising[1].isNew").value(true))
                .andExpect(jsonPath("$.products.trending.rising[1].change").doesNotExist())
                // no name on its order lines: the product's name
                .andExpect(jsonPath("$.products.trending.rising[2].name").value("Cap"))
                .andExpect(jsonPath("$.products.trending.rising[3].productId").value(GONE))
                .andExpect(jsonPath("$.products.trending.rising[3].name").value("Removed product"))
                .andExpect(jsonPath("$.products.trending.falling.length()").value(1))
                .andExpect(jsonPath("$.products.trending.falling[0].name").value("Mug XL"))
                .andExpect(jsonPath("$.products.trending.falling[0].change").value(-17));
    }

    @Test
    void productsShowWhatIsOftenBoughtTogether() throws Exception {
        seedDecember();
        december("products").andExpect(status().isOk())
                .andExpect(jsonPath("$.products.boughtTogether.length()").value(3))
                .andExpect(jsonPath("$.products.boughtTogether[0].productA").value(TEE))
                .andExpect(jsonPath("$.products.boughtTogether[0].nameA").value("Tee"))
                .andExpect(jsonPath("$.products.boughtTogether[0].productB").value(MUG))
                .andExpect(jsonPath("$.products.boughtTogether[0].nameB").value("Mug XL"))
                .andExpect(jsonPath("$.products.boughtTogether[0].orders").value(4))
                .andExpect(jsonPath("$.products.boughtTogether[1].nameA").value("Tee"))
                .andExpect(jsonPath("$.products.boughtTogether[1].productB").value(GONE))
                .andExpect(jsonPath("$.products.boughtTogether[1].nameB").value("Removed product"))
                .andExpect(jsonPath("$.products.boughtTogether[1].orders").value(3))
                .andExpect(jsonPath("$.products.boughtTogether[2].nameA").value("Mug XL"))
                .andExpect(jsonPath("$.products.boughtTogether[2].nameB").value("Removed product"))
                .andExpect(jsonPath("$.products.boughtTogether[2].orders").value(3));
    }

    @Test
    void trendingAndBoughtTogetherDownloadAsCsv() throws Exception {
        seedDecember();
        String trending = december("export/trending").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(trending.substring(1).lines().toList()).containsExactly(
                "direction,product_id,product,units,previous_units,change_percent,new",
                "rising," + TEE + ",Tee,8,2,300,false",
                "rising," + LEGACY + ",Removed product,5,0,,true",
                "rising," + CAP + ",Cap,4,0,,true",
                "rising," + GONE + ",Removed product,3,0,,true",
                "falling," + MUG + ",Mug XL,5,6,-17,false");
        String pairs = december("export/bought-together").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(pairs.substring(1).lines().toList()).containsExactly(
                "product_a_id,product_a,product_b_id,product_b,orders",
                TEE + ",Tee," + MUG + ",Mug XL,4",
                TEE + ",Tee," + GONE + ",Removed product,3",
                MUG + ",Mug XL," + GONE + ",Removed product,3");
        assertThat(auditEntries("ANALYTICS_EXPORTED")).extracting(a -> a.getString("entityId"))
                .containsExactlyInAnyOrder("trending", "bought-together");
    }

    @Test
    void customersShowsNewVsReturningRepeatRateTopCustomersAndPlaces() throws Exception {
        section("customers").andExpect(status().isOk())
                .andExpect(jsonPath("$.customers.customers").value(2))
                .andExpect(jsonPath("$.customers.newCustomers").value(1))
                .andExpect(jsonPath("$.customers.returningCustomers").value(1))
                .andExpect(jsonPath("$.customers.repeatRate").value(50.0))
                .andExpect(jsonPath("$.customers.top[0].name").value("Asha"))
                .andExpect(jsonPath("$.customers.top[0].email").value("asha@shop.test"))
                .andExpect(jsonPath("$.customers.top[0].orders").value(2))
                .andExpect(jsonPath("$.customers.top[0].revenue").value(1304.0))
                .andExpect(jsonPath("$.customers.top[1].name").value("Ravi"))
                .andExpect(jsonPath("$.customers.places[0].city").value("Bengaluru"))
                .andExpect(jsonPath("$.customers.places[1].city").value("Pune"))
                .andExpect(jsonPath("$.customers.places[1].state").value("Maharashtra"))
                .andExpect(jsonPath("$.customers.places[2].city").value("Mysuru"));
    }

    @Test
    void placesGroupSpellingsOfAStateUnderItsOfficialName() throws Exception {
        order(tenant, "u3", "Meera", "meera@shop.test", "2026-11-03T06:00:00Z", 500, 0, 0, null, "upi",
                "KA", "Bengaluru", null, List.of(item(TEE, "Tee", 500, 1, 18.0)));
        order(tenant, "u3", "Meera", "meera@shop.test", "2026-11-04T06:00:00Z", 300, 0, 0, null, "upi",
                " karnataka", "bengaluru ", null, List.of(item(MUG, "Mug", 300, 1, 12.0)));
        order(tenant, "u3", "Meera", "meera@shop.test", "2026-11-05T06:00:00Z", 100, 0, 0, null, "upi",
                "Bangalore", "Bengaluru", null, List.of(item(MUG, "Mug", 100, 1, 12.0)));

        mvc.perform(get("/api/v1/dashboard/customers").header("Authorization", bearer(admin))
                        .param("from", "2026-11-01").param("to", "2026-11-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers.places.length()").value(2))
                .andExpect(jsonPath("$.customers.places[0].state").value("Karnataka"))
                .andExpect(jsonPath("$.customers.places[0].city").value("Bengaluru"))
                .andExpect(jsonPath("$.customers.places[0].orders").value(2))
                .andExpect(jsonPath("$.customers.places[0].revenue").value(800.0))
                // a state the list does not know stays as the shopper typed it
                .andExpect(jsonPath("$.customers.places[1].state").value("Bangalore"));
    }

    /**
     * September 2026 (age on 30 Sep). Female 25-34: five buyers, one turning 25 on 30 Sep, one with two orders.
     * Female 18-24: four buyers, one turning 25 on 1 Oct. Male, age unknown: five (no birth date, none at all, under
     * 18, born in the future, "Male" as stored). Unknown gender: one aged 35-44, a legacy non-ObjectId user, an
     * account that is gone and one that belongs to another shop. Every order is 100; another shop's sale is ignored.
     */
    private void seedSeptember() {
        String at = "2026-09-10T06:00:00Z";
        String f1 = user(tenant, "female", "2001-09-30");
        bought(tenant, f1, at, 100);
        bought(tenant, f1, "2026-09-30T12:00:00Z", 100);
        for (int i = 0; i < 4; i++) {
            bought(tenant, user(tenant, "female", "1995-05-05"), at, 100);
        }
        bought(tenant, user(tenant, "female", "2001-10-01"), at, 100);
        for (int i = 0; i < 3; i++) {
            bought(tenant, user(tenant, "female", "2004-02-02"), at, 100);
        }
        bought(tenant, user(tenant, "male", null), at, 100);
        bought(tenant, user(tenant, "male", "2010-01-01"), at, 100);
        bought(tenant, user(tenant, "male", "2030-01-01"), at, 100);
        bought(tenant, user(tenant, "Male", null), at, 100);
        String noDobField = new ObjectId().toHexString();
        mongo.getCollection("users").insertOne(new Document("_id", new ObjectId(noDobField)).append("tenantId", tenant)
                .append("email", noDobField + "@shop.test").append("gender", "male"));
        bought(tenant, noDobField, at, 100);
        bought(tenant, user(tenant, null, "1990-01-01"), at, 100);
        bought(tenant, "legacy-7", at, 100);
        bought(tenant, new ObjectId().toHexString(), at, 100);
        bought(tenant, user("another-shop", "female", "1995-05-05"), at, 100);
        bought("another-shop", user("another-shop", "female", "1995-05-05"), at, 9999);
    }

    private ResultActions september(String path) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/" + path).header("Authorization", bearer(admin))
                .param("from", "2026-09-01").param("to", "2026-09-30"));
    }

    @Test
    void customersShowTheAudienceByGenderAndAgeBand() throws Exception {
        seedSeptember();
        september("customers").andExpect(status().isOk())
                .andExpect(jsonPath("$.customers.audience.asOf").value("2026-09-30"))
                .andExpect(jsonPath("$.customers.audience.bands").value(org.hamcrest.Matchers.contains(
                        "18-24", "25-34", "35-44", "45-54", "55+", "unknown")))
                .andExpect(jsonPath("$.customers.audience.rows[*].gender").value(org.hamcrest.Matchers.contains(
                        "female", "male", "unknown", "all")))
                // female 18-24: four buyers, small, no figures
                .andExpect(jsonPath("$.customers.audience.rows[0].cells[0].small").value(true))
                .andExpect(jsonPath("$.customers.audience.rows[0].cells[0].buyers").value(nullValue()))
                .andExpect(jsonPath("$.customers.audience.rows[0].cells[0].orders").value(nullValue()))
                .andExpect(jsonPath("$.customers.audience.rows[0].cells[0].revenue").value(nullValue()))
                .andExpect(jsonPath("$.customers.audience.rows[0].cells[0].share").value(nullValue()))
                // female 25-34 has five buyers but is hidden: the female total (9) minus it would give 18-24 away
                .andExpect(jsonPath("$.customers.audience.rows[0].cells[1].small").value(true))
                .andExpect(jsonPath("$.customers.audience.rows[0].cells[1].buyers").value(nullValue()))
                .andExpect(jsonPath("$.customers.audience.rows[0].total.buyers").value(9))
                .andExpect(jsonPath("$.customers.audience.rows[0].total.orders").value(10))
                .andExpect(jsonPath("$.customers.audience.rows[0].total.share").value(52.6))
                // unknown gender: four buyers in all, small; so the male total (5) is hidden too, or the grand
                // total minus female and male would give it away, and male / age unknown with it
                .andExpect(jsonPath("$.customers.audience.rows[2].total.small").value(true))
                .andExpect(jsonPath("$.customers.audience.rows[2].total.buyers").value(nullValue()))
                .andExpect(jsonPath("$.customers.audience.rows[1].total.small").value(true))
                .andExpect(jsonPath("$.customers.audience.rows[1].cells[5].small").value(true))
                // column totals and the whole period, without the other shop's sale
                .andExpect(jsonPath("$.customers.audience.rows[3].cells[0].small").value(true))
                .andExpect(jsonPath("$.customers.audience.rows[3].cells[1].buyers").value(5))
                .andExpect(jsonPath("$.customers.audience.rows[3].cells[1].share").value(31.6))
                .andExpect(jsonPath("$.customers.audience.rows[3].cells[5].buyers").value(8))
                .andExpect(jsonPath("$.customers.audience.rows[3].total.buyers").value(18))
                .andExpect(jsonPath("$.customers.audience.rows[3].total.orders").value(19))
                .andExpect(jsonPath("$.customers.audience.rows[3].total.revenue").value(1900.0))
                .andExpect(jsonPath("$.customers.audience.rows[3].total.share").value(100.0));
    }

    @Test
    void theAudienceDownloadsAsCsvWithHiddenCellsBlank() throws Exception {
        seedSeptember();
        String csv = september("export/audience").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> expected = new ArrayList<>(List.of("gender,age_band,buyers,orders,revenue,share_percent",
                "female,18-24,,,,", "female,25-34,,,,", "female,35-44,,,,", "female,45-54,,,,",
                "female,55+,,,,", "female,unknown,,,,", "female,all,9,10,1000,52.6",
                "male,18-24,,,,", "male,25-34,,,,", "male,35-44,,,,", "male,45-54,,,,", "male,55+,,,,",
                "male,unknown,,,,", "male,all,,,,"));
        for (String band : new String[] {"18-24", "25-34", "35-44", "45-54", "55+", "unknown", "all"}) {
            expected.add("unknown," + band + ",,,,");
        }
        expected.addAll(List.of("all,18-24,,,,", "all,25-34,5,6,600,31.6", "all,35-44,,,,", "all,45-54,,,,",
                "all,55+,,,,", "all,unknown,8,8,800,42.1", "all,all,18,19,1900,100"));
        assertThat(csv.substring(1).lines().toList()).containsExactlyElementsOf(expected);
        assertThat(auditEntries("ANALYTICS_EXPORTED")).extracting(a -> a.getString("entityId"))
                .containsExactly("audience");
    }

    @Test
    void couponsShowsUsesDiscountCostAndRevenue() throws Exception {
        section("coupons").andExpect(status().isOk())
                .andExpect(jsonPath("$.coupons.ordersWithCoupon").value(2))
                .andExpect(jsonPath("$.coupons.shareOfOrders").value(66.7))
                .andExpect(jsonPath("$.coupons.discount").value(150.0))
                .andExpect(jsonPath("$.coupons.codes[0].code").value("WELCOME"))
                .andExpect(jsonPath("$.coupons.codes[0].uses").value(2))
                .andExpect(jsonPath("$.coupons.codes[0].discount").value(150.0))
                .andExpect(jsonPath("$.coupons.codes[0].revenue").value(1844.0));
    }

    @Test
    void paymentsShowsTheMethodMixAndRefundsOverTime() throws Exception {
        section("payments").andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.methods[0].method").value("upi"))
                .andExpect(jsonPath("$.payments.methods[0].orders").value(2))
                .andExpect(jsonPath("$.payments.methods[0].amount").value(1844.0))
                .andExpect(jsonPath("$.payments.methods[0].share").value(66.7))
                .andExpect(jsonPath("$.payments.methods[1].method").value("card"))
                .andExpect(jsonPath("$.payments.granularity").value("day"))
                .andExpect(jsonPath("$.payments.refunds.length()").value(31))
                .andExpect(jsonPath("$.payments.refunds[13].start").value("2026-10-14"))
                .andExpect(jsonPath("$.payments.refunds[13].amount").value(100.0))
                .andExpect(jsonPath("$.payments.refunds[13].count").value(1));
    }

    @Test
    void taxSplitsGstByMonthAndRateIntoCgstSgstAndIgst() throws Exception {
        section("tax").andExpect(status().isOk())
                .andExpect(jsonPath("$.tax.sellerState").value("Karnataka"))
                .andExpect(jsonPath("$.tax.rows.length()").value(2))
                .andExpect(jsonPath("$.tax.rows[0].month").value("2026-10"))
                .andExpect(jsonPath("$.tax.rows[0].rate").value(12.0))
                .andExpect(jsonPath("$.tax.rows[0].taxableValue").value(400.0))
                .andExpect(jsonPath("$.tax.rows[0].cgst").value(12.0))
                .andExpect(jsonPath("$.tax.rows[0].sgst").value(12.0))
                .andExpect(jsonPath("$.tax.rows[0].igst").value(24.0))
                .andExpect(jsonPath("$.tax.rows[0].tax").value(48.0))
                .andExpect(jsonPath("$.tax.rows[1].rate").value(18.0))
                .andExpect(jsonPath("$.tax.rows[1].taxableValue").value(1500.0))
                .andExpect(jsonPath("$.tax.rows[1].cgst").value(90.0))
                .andExpect(jsonPath("$.tax.rows[1].igst").value(90.0))
                .andExpect(jsonPath("$.tax.totalTax").value(318.0));
    }

    @Test
    void eachTableDownloadsAsCsvAndTheDownloadIsAudited() throws Exception {
        String csv = mvc.perform(get("/api/v1/dashboard/export/top-products").header("Authorization", bearer(admin))
                        .param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("\uFEFF"); // so Excel reads it as UTF-8, as the product export does
        assertThat(csv.substring(1).lines().toList()).containsExactly("product_id,product,units,revenue,orders",
                TEE + ",Tee,3,1500,2", MUG + ",Mug,2,400,2");

        mvc.perform(get("/api/v1/dashboard/export/tax").header("Authorization", bearer(admin))
                        .param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "\uFEFFmonth,gst_rate,taxable_value,cgst,sgst,igst,total_tax\n2026-10,12,400,12,12,24,48\n")));

        List<Document> audits = auditEntries("ANALYTICS_EXPORTED");
        assertThat(audits).extracting(a -> a.getString("entityId")).containsExactlyInAnyOrder("top-products", "tax");
    }

    @Test
    void everyTableHasAnExport() throws Exception {
        for (String table : new String[] {"top-products", "categories", "stock", "idle-products", "top-customers",
                "places", "coupons", "payment-methods", "refunds", "tax", "trending", "bought-together", "audience"}) {
            mvc.perform(get("/api/v1/dashboard/export/" + table).header("Authorization", bearer(admin))
                            .param("from", "2026-10-01").param("to", "2026-10-31"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/dashboard/export/everything").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void customersCannotReadSectionsOrExports() throws Exception {
        String user = register("shopper@test.dev");
        mvc.perform(get("/api/v1/dashboard/customers").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dashboard/export/top-customers").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    private void product(String id, String name, String category, double price, int stock, String status) {
        mongo.getCollection("products").insertOne(new Document("_id", new ObjectId(id)).append("tenantId", tenant)
                .append("name", name).append("photo", "uploads/p.png").append("price", price).append("stock", stock)
                .append("category", category).append("status", status)
                .append("createdAt", Date.from(Instant.parse("2026-01-01T00:00:00Z"))));
    }

    private void order(String tenantId, String user, String name, String email, String at, double total,
                       double discount, double tax, String coupon, String method, String state, String city,
                       Document refund, List<Document> items) {
        Document payment = new Document("status", "PAID").append("method", method);
        if (refund != null) {
            payment.append("refunds", List.of(refund));
        }
        Document order = new Document("_id", new ObjectId()).append("tenantId", tenantId).append("user", user)
                .append("status", "Processing").append("total", total).append("discount", discount).append("tax", tax)
                .append("orderItems", new ArrayList<>(items)).append("payment", payment)
                .append("customer", new Document("name", name).append("email", email))
                .append("shippingInfo", new Document("state", state).append("city", city).append("country", "India"))
                .append("createdAt", Date.from(Instant.parse(at)));
        // no coupon: stored as null, as older data may have it, rather than left out
        order.append("couponCode", coupon);
        mongo.getCollection("orders").insertOne(order);
    }

    /** A shopper in {@code tenantId}; the birth date is stored as midnight UTC, as sign-up saves it. */
    private String user(String tenantId, String gender, String dob) {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(new Document("_id", id).append("tenantId", tenantId)
                .append("email", id.toHexString() + "@shop.test").append("role", "user").append("gender", gender)
                .append("dob", dob == null ? null : Date.from(Instant.parse(dob + "T00:00:00Z"))));
        return id.toHexString();
    }

    private void bought(String tenantId, String user, String at, double total) {
        mongo.getCollection("orders").insertOne(new Document("_id", new ObjectId()).append("tenantId", tenantId)
                .append("user", user).append("status", "Processing").append("total", total)
                .append("orderItems", List.of(item(TEE, "Tee", total, 1, 18.0)))
                .append("createdAt", Date.from(Instant.parse(at))));
    }

    /** A bare order in this store; {@code items} null leaves the field out, as some old orders have it. */
    private void sale(String at, List<Document> items) {
        Document order = new Document("_id", new ObjectId()).append("tenantId", tenant).append("user", "u9")
                .append("status", "Processing").append("total", 100)
                .append("createdAt", Date.from(Instant.parse(at)));
        if (items != null) {
            order.append("orderItems", new ArrayList<>(items));
        }
        mongo.getCollection("orders").insertOne(order);
    }

    private static Document unnamed(String productId, int quantity) {
        return new Document("productId", new ObjectId(productId)).append("price", 100).append("quantity", quantity);
    }

    private static Document giftWrap() {
        return new Document("productId", null).append("name", "Gift wrap").append("price", 20).append("quantity", 1);
    }

    private static Document item(String productId, String name, double price, int quantity, Double taxRate) {
        return new Document("productId", new ObjectId(productId)).append("name", name).append("price", price)
                .append("quantity", quantity).append("taxRate", taxRate);
    }

    private static Document refund(double amount, String at) {
        return new Document("amount", amount).append("status", "processed").append("at", Date.from(Instant.parse(at)));
    }
}
