package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.order.domain.Customer;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderDetails;
import com.sixpack.order.domain.OrderItem;
import com.sixpack.order.domain.OrderRepository;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.order.domain.PaymentInfo;
import com.sixpack.order.domain.Quote;
import com.sixpack.order.domain.ShippingInfo;
import com.sixpack.order.domain.StatusEntry;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

class OrderDetailsPersistenceIntegrationTest extends IntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    @Autowired
    OrderRepository orders;

    private static Order order(String number) {
        String productId = new ObjectId().toHexString();
        return new Order(null, new ObjectId().toHexString(),
                new ShippingInfo("1 MG Road", "Pune", "MH", "India", "411001", "Asha", "9876543210"),
                List.of(new OrderItem(productId, "Tee", "uploads/a.png", 250.0, 2, "TEE-1", 5.0)),
                new Quote(500, 25, 200, 0, 725), OrderStatus.PROCESSING,
                new PaymentInfo("fake", "po1", "pay1", "PAID", 725.0, "INR", T0), "2026-10-16", null,
                new OrderDetails(number,
                        new ShippingInfo("9 Park St", "Mumbai", "MH", "India", "400001", "Asha K", "9123456780"),
                        new Customer("Asha", "asha@test.dev", "9876543210"),
                        List.of(new StatusEntry(OrderStatus.PROCESSING, T0, "u1")), "SAVE100", "Leave at door"));
    }

    @Test
    void roundTripsEveryNewField() {
        Order saved = orders.save(order("ORD-20261017-ABCDEF"));

        Order loaded = orders.findById(saved.id()).orElseThrow();

        assertThat(loaded.details()).isEqualTo(saved.details());
        assertThat(loaded.details().billingInfo().city()).isEqualTo("Mumbai");
        assertThat(loaded.shippingInfo().name()).isEqualTo("Asha");
        assertThat(loaded.shippingInfo().phone()).isEqualTo("9876543210");
        assertThat(loaded.payment()).isEqualTo(saved.payment());
        assertThat(loaded.items().get(0).sku()).isEqualTo("TEE-1");
        assertThat(loaded.items().get(0).taxRate()).isEqualTo(5.0);
    }

    @Test
    void legacyOrderWithoutNewFieldsLoadsWithDefaults() {
        ObjectId id = new ObjectId();
        mongo.getCollection("orders").insertOne(new Document("_id", id).append("user", "u1")
                .append("status", "Processing").append("subtotal", 500.0).append("tax", 90.0)
                .append("shippingCharges", 200.0).append("discount", 0.0).append("total", 790.0)
                .append("shippingInfo", new Document("address", "1 MG Road").append("city", "Pune")
                        .append("state", "MH").append("country", "India").append("pinCode", 411001L))
                .append("orderItems", List.of(new Document("name", "Tee").append("photo", "").append("price", 250.0)
                        .append("quantity", 2).append("productId", new ObjectId())))
                .append("payment", new Document("provider", "razorpay").append("orderId", "o").append("paymentId", "p"))
                .append("createdAt", new Date()));
        backfillLegacy();

        Order o = orders.findById(id.toHexString()).orElseThrow();

        assertThat(o.details()).isEqualTo(OrderDetails.EMPTY);
        assertThat(o.payment().status()).isNull();
        assertThat(o.payment().provider()).isEqualTo("razorpay");
        assertThat(o.items().get(0).sku()).isNull();
        assertThat(o.items().get(0).taxRate()).isNull();
        assertThat(o.shippingInfo().name()).isNull();
    }

    @Test
    void orderNumbersAreUniqueButOptional() {
        orders.save(order("ORD-20261017-AAAAAA"));
        assertThatThrownBy(() -> orders.save(order("ORD-20261017-AAAAAA")))
                .isInstanceOf(DuplicateKeyException.class);

        orders.save(order(null));
        orders.save(order(null)); // legacy-style orders without a number never collide
    }
}
