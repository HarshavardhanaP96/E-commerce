package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Each store owner sets their own shop rules (shipping, default GST, coupon cap, minimum age, page size, low-stock
 * level, time zone, invoice seller); the platform values only apply where a store has not set its own.
 */
@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123",
        "app.invoice.seller-name=Platform Seller", "app.invoice.seller-gstin=29ABCDE1234F1Z5",
        "app.invoice.seller-state=Karnataka", "app.invoice.seller-address=1 Platform Road"})
class StoreSettingsIntegrationTest extends TenantIntegrationTest {

    Tenant acme;
    Tenant globex;
    String adminA;
    String adminB;

    @BeforeEach
    void twoStores() throws Exception {
        acme = withFakePayments(createTenant("acme", "Acme", "shop.acme.test"));
        globex = withFakePayments(createTenant("globex", "Globex", "shop.globex.test"));
        adminA = adminTokenFor(acme);
        adminB = adminTokenFor(globex);
    }

    private ResultActions getSettings(Tenant t, String token) throws Exception {
        return mvc.perform(get("/api/v1/store/settings").with(host(hostOf(t))).header("Authorization", bearer(token)));
    }

    private ResultActions saveSettings(Tenant t, String token, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/store/settings").with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private String productOn(Tenant t, String admin, String name, String price, String stock) throws Exception {
        var req = multipart("/api/v1/product/new")
                .file(new MockMultipartFile("photo", "p.png", "image/png", com.sixpack.storage.TestImages.png()));
        req.with(host(hostOf(t)));
        req.param("name", name).param("price", price).param("stock", stock).param("category", "misc")
                .header("Authorization", bearer(admin));
        return body(mvc.perform(req).andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
    }

    private JsonNode quoteOn(Tenant t, String productId) throws Exception {
        Map<String, Object> req = Map.of("items", List.of(Map.of("productId", productId, "quantity", 1)));
        return body(mvc.perform(post("/api/v1/order/quote").with(host(hostOf(t)))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
                .andExpect(status().isOk()).andReturn()).get("quote");
    }

    private String placeOrderOn(Tenant t, String token, String productId) throws Exception {
        Map<String, Object> req = Map.of("items", List.of(Map.of("productId", productId, "quantity", 1)));
        String intent = body(mvc.perform(post("/api/v1/order/checkout").with(host(hostOf(t)))
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        Map<String, Object> confirm = new HashMap<>(Map.of("providerOrderId", intent, "paymentId", "fake_pay_1",
                "signature", "fake", "shippingInfo", OrderCheckoutDetailsIntegrationTest.shipping()));
        return body(mvc.perform(post("/api/v1/order/new").with(host(hostOf(t))).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(confirm)))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    private JsonNode invoiceOn(Tenant t, String token, String orderId) throws Exception {
        return body(mvc.perform(get("/api/v1/order/" + orderId + "/invoice").with(host(hostOf(t)))
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn()).get("invoice");
    }

    @Test
    void aNewStoreSeesThePlatformDefaultsAndNothingOfItsOwn() throws Exception {
        getSettings(acme, adminA).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.commerce.shippingFee").doesNotExist())
                .andExpect(jsonPath("$.effective.shippingFee").value(200.0))
                .andExpect(jsonPath("$.effective.freeShippingAbove").value(1000.0))
                .andExpect(jsonPath("$.effective.defaultTaxRate").value(18.0))
                .andExpect(jsonPath("$.effective.maxCouponAmount").value(100000.0))
                .andExpect(jsonPath("$.effective.minimumAge").value(18))
                .andExpect(jsonPath("$.effective.pageSize").value(10))
                .andExpect(jsonPath("$.effective.lowStockThreshold").value(5))
                .andExpect(jsonPath("$.effective.timeZone").value("Asia/Kolkata"));
    }

    @Test
    void aStoreOwnerSavesTheirOwnRulesAndUnsetOnesKeepThePlatformDefault() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("shippingFee", 49, "freeShippingAbove", 499)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.commerce.shippingFee").value(49.0))
                .andExpect(jsonPath("$.effective.shippingFee").value(49.0))
                .andExpect(jsonPath("$.effective.freeShippingAbove").value(499.0))
                .andExpect(jsonPath("$.effective.defaultTaxRate").value(18.0))
                // what an emptied field would return to, for the form's hints
                .andExpect(jsonPath("$.defaults.shippingFee").value(200.0))
                .andExpect(jsonPath("$.defaults.timeZone").value("Asia/Kolkata"));

        getSettings(globex, adminB).andExpect(jsonPath("$.effective.shippingFee").value(200.0));
    }

    @Test
    void groupsLeftOutOfTheRequestAreKept() throws Exception {
        saveSettings(acme, adminA, Map.of("catalog", Map.of("pageSize", 24))).andExpect(status().isOk());
        saveSettings(acme, adminA, Map.of("regional", Map.of("timeZone", "Europe/London"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.pageSize").value(24))
                .andExpect(jsonPath("$.effective.timeZone").value("Europe/London"));
    }

    @Test
    void onlyTheStoresAdminCanSeeOrChangeItsSettings() throws Exception {
        String customer = registerOn(acme, "buyer@acme.test");
        getSettings(acme, customer).andExpect(status().isForbidden());
        saveSettings(acme, customer, Map.of("commerce", Map.of("shippingFee", 0))).andExpect(status().isForbidden());
        // another store's admin token is not valid on this store's host
        getSettings(acme, adminB).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidValuesAreRejected() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("shippingFee", -1))).andExpect(status().isBadRequest());
        saveSettings(acme, adminA, Map.of("commerce", Map.of("defaultTaxRate", 150))).andExpect(status().isBadRequest());
        saveSettings(acme, adminA, Map.of("commerce", Map.of("minimumAge", 16))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("commerce.minimumAge: must be between 18 and 100"));
        saveSettings(acme, adminA, Map.of("commerce", Map.of("maxCouponAmount", 0))).andExpect(status().isBadRequest());
        saveSettings(acme, adminA, Map.of("catalog", Map.of("pageSize", 500))).andExpect(status().isBadRequest());
        saveSettings(acme, adminA, Map.of("catalog", Map.of("lowStockThreshold", -3))).andExpect(status().isBadRequest());
        saveSettings(acme, adminA, Map.of("regional", Map.of("timeZone", "Mars/Olympus"))).andExpect(status().isBadRequest());
        saveSettings(acme, adminA, Map.of("invoice", Map.of("gstin", "NOT-A-GSTIN"))).andExpect(status().isBadRequest());
        saveSettings(acme, adminA, Map.of("storefront", Map.of("email", "nope"))).andExpect(status().isBadRequest());
        getSettings(acme, adminA).andExpect(jsonPath("$.effective.shippingFee").value(200.0));
    }

    @Test
    void aChangeIsRecordedInTheStoresOwnAuditLog() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("shippingFee", 49))).andExpect(status().isOk());
        List<Document> entries = mongo.getCollection("audit_log")
                .find(new Document("action", "STORE_SETTINGS_UPDATED")).into(new java.util.ArrayList<>());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getString("tenantId")).isEqualTo(acme.id());
        Document change = changeOf(entries.get(0), "commerce.shippingFee");
        assertThat(change.get("before")).isNull();
        assertThat(change.get("after")).isEqualTo(49.0);
    }

    @Test
    void checkoutChargesTheStoresOwnShippingAndDefaultTax() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("shippingFee", 49, "freeShippingAbove", 2000,
                "defaultTaxRate", 5))).andExpect(status().isOk());
        JsonNode acmeQuote = quoteOn(acme, productOn(acme, adminA, "Tee", "500", "10"));
        assertThat(acmeQuote.get("shippingCharges").asDouble()).isEqualTo(49.0);
        assertThat(acmeQuote.get("tax").asDouble()).isEqualTo(25.0);
        assertThat(acmeQuote.get("total").asDouble()).isEqualTo(574.0);

        JsonNode globexQuote = quoteOn(globex, productOn(globex, adminB, "Tee", "500", "10"));
        assertThat(globexQuote.get("shippingCharges").asDouble()).isEqualTo(200.0);
        assertThat(globexQuote.get("tax").asDouble()).isEqualTo(90.0);
    }

    @Test
    void anOrderKeepsTheTaxRateItWasChargedAtWhenTheDefaultChangesLater() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("defaultTaxRate", 5))).andExpect(status().isOk());
        String buyer = registerOn(acme, "buyer@acme.test");
        String orderId = placeOrderOn(acme, buyer, productOn(acme, adminA, "Tee", "500", "10"));

        saveSettings(acme, adminA, Map.of("commerce", Map.of("defaultTaxRate", 12))).andExpect(status().isOk());
        JsonNode invoice = invoiceOn(acme, buyer, orderId);
        assertThat(invoice.get("lines").get(0).get("taxRate").asDouble()).isEqualTo(5.0);
        assertThat(invoice.get("tax").asDouble()).isEqualTo(25.0);
    }

    @Test
    void anInvoiceNamesTheStoreThatSoldTheGoodsNeverThePlatformsSeller() throws Exception {
        saveSettings(acme, adminA, Map.of(
                "storefront", Map.of("legalName", "Acme Traders LLP", "address", "7 Residency Road, Bengaluru",
                        "email", "billing@acme.test"),
                "invoice", Map.of("gstin", "29AAACA1234B1Z9", "state", "Karnataka"))).andExpect(status().isOk());
        String buyerA = registerOn(acme, "buyer@acme.test");
        JsonNode seller = invoiceOn(acme, buyerA, placeOrderOn(acme, buyerA, productOn(acme, adminA, "Tee", "500", "10")))
                .get("seller");
        assertThat(seller.get("name").asText()).isEqualTo("Acme Traders LLP");
        assertThat(seller.get("address").asText()).isEqualTo("7 Residency Road, Bengaluru");
        assertThat(seller.get("gstin").asText()).isEqualTo("29AAACA1234B1Z9");
        assertThat(seller.get("state").asText()).isEqualTo("Karnataka");

        String buyerB = registerOn(globex, "buyer@globex.test");
        JsonNode bare = invoiceOn(globex, buyerB,
                placeOrderOn(globex, buyerB, productOn(globex, adminB, "Tee", "500", "10"))).get("seller");
        assertThat(bare.get("name").asText()).isEqualTo("Globex");
        assertThat(bare.get("gstin").isNull()).isTrue();
        assertThat(bare.get("address").isNull()).isTrue();
    }

    @Test
    void theStorefrontConfigCarriesTheRulesTheCartNeeds() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("shippingFee", 49, "defaultTaxRate", 5)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(acme)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.commerce.shippingFee").value(49.0))
                .andExpect(jsonPath("$.tenant.commerce.freeShippingAbove").value(1000.0))
                .andExpect(jsonPath("$.tenant.commerce.defaultTaxRate").value(5.0))
                .andExpect(jsonPath("$.tenant.commerce.minimumAge").value(18))
                .andExpect(jsonPath("$.tenant.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.tenant.commerce.maxCouponAmount").doesNotExist());
    }

    @Test
    void theCouponCapFollowsTheStore() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("maxCouponAmount", 500))).andExpect(status().isOk());
        Map<String, Object> coupon = Map.of("coupon", "BIG600", "amount", 600);
        mvc.perform(post("/api/v1/payment/coupon/new").with(host(hostOf(acme))).header("Authorization", bearer(adminA))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(coupon)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("amount: must be at most 500"));
        mvc.perform(post("/api/v1/payment/coupon/new").with(host(hostOf(globex))).header("Authorization", bearer(adminB))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(coupon)))
                .andExpect(status().isCreated());
    }

    @Test
    void theMinimumAgeFollowsTheStore() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("minimumAge", 21))).andExpect(status().isOk());
        String nineteen = LocalDate.now().minusYears(19).minusDays(1).toString();
        for (Tenant t : List.of(acme, globex)) {
            var request = multipart("/api/v1/user/register");
            request.with(host(hostOf(t)));
            request.param("name", "Young User").param("email", "young@" + t.slug() + ".test")
                    .param("password", "password123").param("gender", "female").param("dob", nineteen)
                    .param("acceptTerms", "true");
            ResultActions result = mvc.perform(request);
            if (t == acme) {
                result.andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("You must be 21 or older to create an account"));
            } else {
                result.andExpect(status().isCreated());
            }
        }
    }

    @Test
    void pageSizeAndLowStockLevelFollowTheStore() throws Exception {
        saveSettings(acme, adminA, Map.of("catalog", Map.of("pageSize", 2, "lowStockThreshold", 20)))
                .andExpect(status().isOk());
        for (String name : new String[] {"One", "Two", "Three"}) {
            productOn(acme, adminA, name, "100", "10");
            productOn(globex, adminB, name, "100", "10");
        }
        mvc.perform(get("/api/v1/product/all").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.totalPage").value(2)).andExpect(jsonPath("$.products.length()").value(2));
        mvc.perform(get("/api/v1/product/all").with(host(hostOf(globex))))
                .andExpect(jsonPath("$.totalPage").value(1)).andExpect(jsonPath("$.products.length()").value(3));

        mvc.perform(get("/api/v1/product/low-stock").with(host(hostOf(acme))).header("Authorization", bearer(adminA)))
                .andExpect(jsonPath("$.alerts.length()").value(3)).andExpect(jsonPath("$.alerts[0].threshold").value(20));
        mvc.perform(get("/api/v1/product/low-stock").with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(jsonPath("$.alerts.length()").value(0));
    }

    @Test
    void platformChangesToTheStoreDoNotWipeTheOwnersSettings() throws Exception {
        saveSettings(acme, adminA, Map.of("commerce", Map.of("shippingFee", 49))).andExpect(status().isOk());
        String platform = body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
        mvc.perform(put("/api/v1/platform/tenants/" + acme.id()).header("Authorization", bearer(platform))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "Acme 2"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/platform/tenants/" + acme.id() + "/suspend").header("Authorization", bearer(platform)));
        assertThat(tenantRepository.findById(acme.id()).orElseThrow().settings().commerce().shippingFee())
                .isEqualTo(49.0);
    }
}
