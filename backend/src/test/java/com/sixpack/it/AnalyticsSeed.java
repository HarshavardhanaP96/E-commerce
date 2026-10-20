package com.sixpack.it;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Raw documents for analytics tests, written straight to Mongo in the stored shape the dashboard reads. Builders
 * return a {@link Document}; change any field with {@code append} (it replaces) before saving.
 */
final class AnalyticsSeed {

    private final MongoTemplate mongo;
    private final String tenant;

    AnalyticsSeed(MongoTemplate mongo, String tenant) {
        this.mongo = mongo;
        this.tenant = tenant;
    }

    static String newId() {
        return new ObjectId().toHexString();
    }

    static Date date(String iso) {
        return Date.from(Instant.parse(iso));
    }

    /** A paid, Processing order by user "u1" with no tax, discount or shipping. */
    Document order(String at, double total, Document... items) {
        return new Document("_id", new ObjectId()).append("tenantId", tenant).append("user", "u1")
                .append("status", "Processing").append("total", total).append("discount", 0.0).append("tax", 0.0)
                .append("shippingCharges", 0.0).append("orderItems", List.of(items))
                .append("payment", new Document("status", "PAID")).append("createdAt", date(at));
    }

    ObjectId save(Document order) {
        mongo.getCollection("orders").insertOne(order);
        return order.getObjectId("_id");
    }

    static Document item(String productId, double price, int quantity) {
        return new Document("productId", new ObjectId(productId)).append("name", "Product " + productId.substring(20))
                .append("price", price).append("quantity", quantity);
    }

    static Document item(String productId, String variantId, String label, double price, int quantity) {
        return item(productId, price, quantity).append("variantId", variantId).append("variantLabel", label);
    }

    static Document history(String status, String at) {
        return new Document("status", status).append("at", date(at));
    }

    static Document shipTo(String state, String country, Long pinCode) {
        return new Document("address", "1 Main Road").append("city", "City").append("state", state)
                .append("country", country).append("pinCode", pinCode);
    }

    static Document refund(double amount, String status, String at) {
        return new Document("amount", amount).append("status", status).append("at", date(at));
    }

    /** An ACTIVE product without variants. */
    Document product(String id, String name, String category, double price, int stock) {
        return new Document("_id", new ObjectId(id)).append("tenantId", tenant).append("name", name)
                .append("category", category).append("price", price).append("stock", stock)
                .append("status", "ACTIVE").append("createdAt", new Date());
    }

    static Document variant(String variantId, String sku, String value, double price, int stock) {
        return new Document("variantId", variantId).append("sku", sku)
                .append("options", List.of(new Document("name", "Size").append("value", value)))
                .append("price", price).append("stock", stock).append("status", "ACTIVE");
    }

    void saveProduct(Document product) {
        mongo.getCollection("products").insertOne(product);
    }

    /** A shopper account; returns its id as the hex string orders store in {@code user}. */
    String customer(String name, String createdAt) {
        ObjectId id = new ObjectId();
        mongo.getCollection("users").insertOne(new Document("_id", id).append("tenantId", tenant)
                .append("name", name).append("email", name.toLowerCase().replace(' ', '.') + "@shop.test")
                .append("role", "user").append("createdAt", date(createdAt)));
        return id.toHexString();
    }
}
