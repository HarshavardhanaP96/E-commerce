package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
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
                "places", "coupons", "payment-methods", "refunds", "tax"}) {
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

    private static Document item(String productId, String name, double price, int quantity, Double taxRate) {
        return new Document("productId", new ObjectId(productId)).append("name", name).append("price", price)
                .append("quantity", quantity).append("taxRate", taxRate);
    }

    private static Document refund(double amount, String at) {
        return new Document("amount", amount).append("status", "processed").append("at", Date.from(Instant.parse(at)));
    }
}
