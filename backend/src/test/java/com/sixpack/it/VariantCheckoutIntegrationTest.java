package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.product.domain.ProductRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class VariantCheckoutIntegrationTest extends IntegrationTest {

    private static final Map<String, String> SHIPPING = Map.of("address", "1 MG Road", "city", "Bengaluru",
            "state", "KA", "country", "India", "pinCode", "560001");

    @Autowired
    ProductRepository products;

    private static Map<String, Object> axis(String name, String type, String... labels) {
        return Map.of("name", name, "type", type, "values",
                java.util.Arrays.stream(labels).map(l -> Map.of("label", l)).toList());
    }

    private static Map<String, Object> variant(String sku, double price, int stock, String size, String colour) {
        var m = new HashMap<String, Object>();
        m.put("sku", sku);
        m.put("price", price);
        m.put("stock", stock);
        m.put("options", List.of(Map.of("name", "Size", "value", size), Map.of("name", "Colour", "value", colour)));
        return m;
    }

    /** A shirt priced 1 at product level, with S/Black (₹500 x3), M/Black (₹600 x1), S/White (₹520 x2). */
    private String shirt(String admin) throws Exception {
        String id = createProduct(admin, "T-Shirt", 1, 1, "clothing");
        mvc.perform(put("/api/v1/product/" + id + "/variants").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(admin))
                .content(json.writeValueAsString(Map.of(
                        "axes", List.of(axis("Size", "text", "S", "M"), axis("Colour", "colour", "Black", "White")),
                        "variants", List.of(variant("TS-S-BLK", 500, 3, "S", "Black"),
                                variant("TS-M-BLK", 600, 1, "M", "Black"),
                                variant("TS-S-WHT", 520, 2, "S", "White"))))))
                .andExpect(status().isOk());
        return id;
    }

    private JsonNode productJson(String id) throws Exception {
        return body(mvc.perform(get("/api/v1/product/" + id)).andExpect(status().isOk()).andReturn()).get("product");
    }

    private String variantId(String productId, String sku) throws Exception {
        for (JsonNode v : productJson(productId).get("variants").get("items")) {
            if (v.get("sku").asText().equals(sku)) {
                return v.get("id").asText();
            }
        }
        throw new AssertionError("no variant " + sku);
    }

    private int variantStock(String productId, String sku) throws Exception {
        for (JsonNode v : productJson(productId).get("variants").get("items")) {
            if (v.get("sku").asText().equals(sku)) {
                return v.get("stock").asInt();
            }
        }
        throw new AssertionError("no variant " + sku);
    }

    private static Map<String, Object> line(String productId, String variantId, int qty) {
        var m = new HashMap<String, Object>();
        m.put("productId", productId);
        m.put("variantId", variantId);
        m.put("quantity", qty);
        return m;
    }

    private ResultActions checkoutRequest(String token, Map<String, Object>... lines) throws Exception {
        return mvc.perform(post("/api/v1/order/checkout").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(lines)))));
    }

    @SafeVarargs
    private JsonNode checkout(String token, Map<String, Object>... lines) throws Exception {
        return body(checkoutRequest(token, lines).andExpect(status().isCreated()).andReturn()).get("checkout");
    }

    private ResultActions confirm(String token, String providerOrderId) throws Exception {
        return mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("providerOrderId", providerOrderId,
                        "paymentId", "fake_pay_1", "signature", "fake", "shippingInfo", SHIPPING))));
    }

    @Test
    void buyingAVariantUsesItsPriceSnapshotsItAndDecrementsOnlyThatVariant() throws Exception {
        String admin = adminToken();
        String product = shirt(admin);
        String user = register("buyer@test.dev");
        String sBlack = variantId(product, "TS-S-BLK");

        JsonNode quote = checkout(user, line(product, sBlack, 2));
        // 2 x 500 = 1000, 18% tax = 180, shipping free above 1000? (1000 is not above) = 200
        assertThat(quote.get("subtotal").asDouble()).isEqualTo(1000.0);
        assertThat(quote.get("tax").asDouble()).isEqualTo(180.0);

        String orderId = body(confirm(user, quote.get("providerOrderId").asText())
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();

        mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.order.orderItems[0].variantId").value(sBlack))
                .andExpect(jsonPath("$.order.orderItems[0].variantLabel").value("S / Black"))
                .andExpect(jsonPath("$.order.orderItems[0].sku").value("TS-S-BLK"))
                .andExpect(jsonPath("$.order.orderItems[0].price").value(500.0))
                .andExpect(jsonPath("$.order.orderItems[0].options[0].name").value("Size"))
                .andExpect(jsonPath("$.order.orderItems[0].options[1].value").value("Black"));
        assertThat(variantStock(product, "TS-S-BLK")).isEqualTo(1);
        assertThat(variantStock(product, "TS-M-BLK")).isEqualTo(1);
        assertThat(variantStock(product, "TS-S-WHT")).isEqualTo(2);
        assertThat(productJson(product).get("stock").asInt()).isEqualTo(4);

        // the snapshot is stored on the order document itself
        Document stored = mongo.getCollection("orders").find(new Document("_id", new ObjectId(orderId))).first();
        Document item = ((List<Document>) stored.get("orderItems")).get(0);
        assertThat(item.getString("variantId")).isEqualTo(sBlack);
    }

    @Test
    void twoVariantsOfOneProductAreTwoOrderLines() throws Exception {
        String product = shirt(adminToken());
        String user = register("two@test.dev");

        JsonNode quote = checkout(user, line(product, variantId(product, "TS-S-BLK"), 1),
                line(product, variantId(product, "TS-S-WHT"), 2));
        assertThat(quote.get("subtotal").asDouble()).isEqualTo(500.0 + 2 * 520.0);
        String orderId = body(confirm(user, quote.get("providerOrderId").asText())
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();

        mvc.perform(get("/api/v1/order/" + orderId).header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.order.orderItems.length()").value(2));
        assertThat(variantStock(product, "TS-S-BLK")).isEqualTo(2);
        assertThat(variantStock(product, "TS-S-WHT")).isZero();
    }

    @Test
    void theSameVariantTwiceInOneCartIsMerged() throws Exception {
        String product = shirt(adminToken());
        String user = register("merge@test.dev");
        String sBlack = variantId(product, "TS-S-BLK");

        // 2 + 2 = 4 > 3 in stock
        checkoutRequest(user, line(product, sBlack, 2), line(product, sBlack, 2)).andExpect(status().isConflict());
    }

    @Test
    void lineMustMatchTheProductsVariants() throws Exception {
        String admin = adminToken();
        String product = shirt(admin);
        String simple = createProduct(admin, "Mug", 300, 5, "home");
        String user = register("lines@test.dev");

        checkoutRequest(user, line(product, null, 1)).andExpect(status().isBadRequest());          // no variant chosen
        checkoutRequest(user, line(product, "doesnotexist", 1)).andExpect(status().isBadRequest());
        checkoutRequest(user, line(simple, variantId(product, "TS-S-BLK"), 1)).andExpect(status().isBadRequest());
        checkoutRequest(user, line(simple, null, 1)).andExpect(status().isCreated());
    }

    @Test
    void disabledVariantsCannotBeBought() throws Exception {
        String admin = adminToken();
        String product = shirt(admin);
        String user = register("disabled@test.dev");
        JsonNode current = productJson(product);
        var items = new java.util.ArrayList<Map<String, Object>>();
        for (JsonNode v : current.get("variants").get("items")) {
            var m = variant(v.get("sku").asText(), v.get("price").asDouble(), 0,
                    v.get("options").get(0).get("value").asText(), v.get("options").get(1).get("value").asText());
            m.put("id", v.get("id").asText());
            if (v.get("sku").asText().equals("TS-S-BLK")) {
                m.put("status", "DISABLED");
            }
            items.add(m);
        }
        mvc.perform(put("/api/v1/product/" + product + "/variants").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(admin))
                .content(json.writeValueAsString(Map.of("axes", List.of(axis("Size", "text", "S", "M"),
                        axis("Colour", "colour", "Black", "White")), "variants", items))))
                .andExpect(status().isOk());

        checkoutRequest(user, line(product, variantId(product, "TS-S-BLK"), 1)).andExpect(status().isBadRequest());
        checkoutRequest(user, line(product, variantId(product, "TS-S-WHT"), 1)).andExpect(status().isCreated());
    }

    @Test
    void theLastUnitOfAVariantIsNeverOversold() throws Exception {
        String product = shirt(adminToken());
        String m = variantId(product, "TS-M-BLK");   // stock 1
        String a = register("a@test.dev");
        String b = register("b@test.dev");
        String intentA = checkout(a, line(product, m, 1)).get("providerOrderId").asText();
        String intentB = checkout(b, line(product, m, 1)).get("providerOrderId").asText();

        confirm(a, intentA).andExpect(status().isCreated());
        confirm(b, intentB).andExpect(status().isConflict());

        assertThat(variantStock(product, "TS-M-BLK")).isZero();
        assertThat(productJson(product).get("stock").asInt()).isEqualTo(5);
        checkoutRequest(b, line(product, m, 1)).andExpect(status().isConflict());
    }

    @Test
    void aFailedReservationPutsBackEveryOtherLine() throws Exception {
        String admin = adminToken();
        String product = shirt(admin);
        String user = register("rollback@test.dev");
        String sBlack = variantId(product, "TS-S-BLK");
        String sWhite = variantId(product, "TS-S-WHT");
        String intent = checkout(user, line(product, sBlack, 1), line(product, sWhite, 2))
                .get("providerOrderId").asText();

        // stock of the second line vanishes after the quote
        mvc.perform(patch("/api/v1/product/" + product + "/variants/" + sWhite + "/stock")
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"set\":0}")).andExpect(status().isOk());

        confirm(user, intent).andExpect(status().isConflict());

        assertThat(variantStock(product, "TS-S-BLK")).isEqualTo(3);   // restored
        assertThat(productJson(product).get("stock").asInt()).isEqualTo(4);   // 3 + 1 + 0
    }

    @Test
    void aSimpleSaleNeverTouchesAProductThatHasVariants() throws Exception {
        String product = shirt(adminToken());

        assertThat(products.decrementStockIfAvailable(product, 1)).isFalse();
        assertThat(productJson(product).get("stock").asInt()).isEqualTo(6);
    }

    @Test
    void ordersWithoutVariantFieldsStillLoad() throws Exception {
        String user = register("legacy@test.dev");
        String userId = userId(user);
        ObjectId productId = new ObjectId();
        ObjectId orderId = new ObjectId();
        mongo.getCollection("orders").insertOne(new Document("_id", orderId).append("user", userId)
                .append("subtotal", 100.0).append("tax", 18.0).append("shippingCharges", 0.0)
                .append("discount", 0.0).append("total", 118.0).append("status", "Processing")
                .append("shippingInfo", new Document("address", "x").append("city", "y").append("state", "z")
                        .append("country", "India").append("pinCode", 560001L))
                .append("orderItems", List.of(new Document("name", "Old").append("photo", "p.png")
                        .append("price", 100.0).append("quantity", 1).append("productId", productId))));

        mvc.perform(get("/api/v1/order/" + orderId.toHexString()).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderItems[0].name").value("Old"))
                .andExpect(jsonPath("$.order.orderItems[0].variantId").doesNotExist())
                .andExpect(jsonPath("$.order.orderItems[0].options.length()").value(0));
    }
}
