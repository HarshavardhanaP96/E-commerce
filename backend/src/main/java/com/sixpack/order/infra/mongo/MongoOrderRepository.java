package com.sixpack.order.infra.mongo;

import com.sixpack.order.OrderAttribution;
import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.order.domain.Customer;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderDetails;
import com.sixpack.order.domain.OrderItem;
import com.sixpack.order.domain.OrderRepository;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.order.domain.PaymentInfo;
import com.sixpack.order.domain.Refund;
import com.sixpack.order.domain.Quote;
import com.sixpack.order.domain.ShippingInfo;
import com.sixpack.order.domain.StatusEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoOrderRepository implements OrderRepository {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final TenantMongo mongo;

    MongoOrderRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Order save(Order order) {
        OrderDocument doc = order.id() == null ? null : mongo.findById(order.id(), OrderDocument.class).orElse(null);
        if (doc == null) {
            doc = new OrderDocument();
        }
        doc.id = order.id();
        doc.user = order.userId();
        doc.status = order.status().wireValue();
        doc.subtotal = order.quote().subtotal();
        doc.tax = order.quote().tax();
        doc.shippingCharges = order.quote().shippingCharges();
        doc.discount = order.quote().discount();
        doc.total = order.quote().total();
        doc.shippingInfo = toDoc(order.shippingInfo());
        doc.termsVersion = order.termsVersion();
        OrderDetails d = order.details();
        doc.orderNumber = d.orderNumber();
        doc.billingInfo = d.billingInfo() == null ? null : toDoc(d.billingInfo());
        doc.customer = d.customer() == null ? null : toDoc(d.customer());
        doc.statusHistory = d.statusHistory().isEmpty() ? null
                : d.statusHistory().stream().map(MongoOrderRepository::toDoc).toList();
        doc.couponCode = d.couponCode();
        doc.notes = d.notes();
        // Written once at checkout; erasure removes it with an atomic unset, never through save.
        if (d.attribution() != null) {
            doc.attribution = toDoc(d.attribution());
        }
        doc.orderItems = order.items().stream().map(MongoOrderRepository::toDoc).toList();
        if (order.payment() != null) {
            // Refund state is changed only by the atomic refund operations below, so saving an order that was read
            // earlier (for example to move it to Shipped) can't wipe out a refund made in between.
            boolean keepRefundState = doc.payment != null;
            OrderDocument.PaymentDoc payment = keepRefundState ? doc.payment : new OrderDocument.PaymentDoc();
            payment.provider = order.payment().provider();
            payment.orderId = order.payment().orderId();
            payment.paymentId = order.payment().paymentId();
            payment.amount = order.payment().amount();
            payment.currency = order.payment().currency();
            payment.paidAt = order.payment().paidAt();
            payment.method = order.payment().method();
            if (!keepRefundState) {
                payment.status = order.payment().status();
            }
            doc.payment = payment;
        }
        return toDomain(mongo.save(doc));
    }

    @Override
    public Optional<Order> findById(String id) {
        return mongo.findById(id, OrderDocument.class).map(MongoOrderRepository::toDomain);
    }

    @Override
    public List<Order> findByUser(String userId) {
        return find(Query.query(Criteria.where("user").is(userId)).with(NEWEST_FIRST));
    }

    @Override
    public List<Order> findAll(OrderStatus status) {
        Query query = new Query().with(NEWEST_FIRST);
        if (status != null) {
            query.addCriteria(Criteria.where("status").is(status.wireValue()));
        }
        return find(query);
    }

    @Override
    public void deleteById(String id) {
        mongo.remove(Query.query(Criteria.where("id").is(id)), OrderDocument.class);
    }

    @Override
    public boolean hasOpenOrders(String userId) {
        return mongo.exists(Query.query(Criteria.where("user").is(userId)
                .and("status").ne(OrderStatus.DELIVERED.wireValue())), OrderDocument.class);
    }

    @Override
    public boolean hasAnyOrder(String userId) {
        return mongo.exists(Query.query(Criteria.where("user").is(userId)), OrderDocument.class);
    }

    @Override
    public boolean reserveRefund(String orderId, long amountPaise, long totalPaise) {
        Criteria fits = new Criteria().orOperator(Criteria.where("payment.refundedPaise").exists(false),
                Criteria.where("payment.refundedPaise").lte(totalPaise - amountPaise));
        Query query = Query.query(new Criteria().andOperator(Criteria.where("id").is(orderId),
                Criteria.where("payment.status").in(PaymentInfo.PAID, PaymentInfo.PARTIALLY_REFUNDED), fits));
        return mongo.updateFirst(query, new Update().inc("payment.refundedPaise", amountPaise), OrderDocument.class)
                .getModifiedCount() == 1;
    }

    @Override
    public void releaseRefund(String orderId, long amountPaise, String failureReason) {
        mongo.updateFirst(Query.query(Criteria.where("id").is(orderId)),
                new Update().inc("payment.refundedPaise", -amountPaise).set("payment.failureReason", failureReason),
                OrderDocument.class);
    }

    @Override
    public void completeRefund(String orderId, Refund refund, long totalPaise) {
        OrderDocument.RefundDoc doc = new OrderDocument.RefundDoc();
        doc.providerRefundId = refund.providerRefundId();
        doc.amount = refund.amount();
        doc.reason = refund.reason();
        doc.status = refund.status();
        doc.actor = refund.actor();
        doc.at = refund.at();
        Query byId = Query.query(Criteria.where("id").is(orderId));
        mongo.updateFirst(byId, new Update().push("payment.refunds", doc).unset("payment.failureReason"),
                OrderDocument.class);
        // Read after the push, so the last of several concurrent refunds sees every reservation.
        OrderDocument after = mongo.findOne(byId, OrderDocument.class).orElse(null);
        long refunded = after == null || after.payment == null || after.payment.refundedPaise == null ? 0
                : after.payment.refundedPaise;
        mongo.updateFirst(byId, new Update().set("payment.status",
                refunded >= totalPaise ? PaymentInfo.REFUNDED : PaymentInfo.PARTIALLY_REFUNDED),
                OrderDocument.class);
    }

    @Override
    public List<String> findConsentedIdsBetween(Instant from, Instant to, int limit) {
        Query query = Query.query(Criteria.where("createdAt").gte(from).lt(to).and("payment.status").ne(null)
                        .orOperator(Criteria.where("attribution.consent.analytics").is(true),
                                Criteria.where("attribution.consent.marketing").is(true)))
                .with(Sort.by("createdAt")).limit(limit);
        query.fields().include("id");
        return mongo.find(query, OrderDocument.class).stream().map(d -> d.id).toList();
    }

    @Override
    public void anonymiseShippingForUser(String userId) {
        mongo.updateMulti(Query.query(Criteria.where("user").is(userId)),
                new Update()
                        .unset("shippingInfo.address").unset("shippingInfo.pinCode")
                        .unset("shippingInfo.name").unset("shippingInfo.phone")
                        .unset("billingInfo").unset("customer").unset("notes").unset("attribution"),
                OrderDocument.class);
    }

    private List<Order> find(Query query) {
        return mongo.find(query, OrderDocument.class).stream().map(MongoOrderRepository::toDomain).toList();
    }

    private static OrderDocument.ShippingInfoDoc toDoc(ShippingInfo s) {
        OrderDocument.ShippingInfoDoc d = new OrderDocument.ShippingInfoDoc();
        d.address = s.address();
        d.city = s.city();
        d.state = s.state();
        d.country = s.country();
        d.pinCode = s.pinCode() == null || s.pinCode().isBlank() ? null : Long.parseLong(s.pinCode());
        d.name = s.name();
        d.phone = s.phone();
        return d;
    }

    static OrderDocument.OrderItemDoc toDoc(OrderItem i) {
        OrderDocument.OrderItemDoc d = new OrderDocument.OrderItemDoc();
        d.productId = i.productId();
        d.name = i.name();
        d.photo = i.photo();
        d.price = i.price();
        d.quantity = i.quantity();
        d.sku = i.sku();
        d.taxRate = i.taxRate();
        d.variantId = i.variantId();
        d.variantLabel = i.variantLabel();
        d.options = i.options().isEmpty() ? null : i.options().stream().map(o -> {
            OrderDocument.OptionDoc option = new OrderDocument.OptionDoc();
            option.name = o.name();
            option.value = o.value();
            return option;
        }).toList();
        return d;
    }

    static OrderItem toDomain(OrderDocument.OrderItemDoc d) {
        return new OrderItem(d.productId, d.name, d.photo, nz(d.price), d.quantity == null ? 0 : d.quantity, d.sku,
                d.taxRate, d.variantId, d.variantLabel, d.options == null ? null
                        : d.options.stream().map(o -> new OrderItem.Option(o.name, o.value)).toList());
    }

    private static Order toDomain(OrderDocument d) {
        ShippingInfo shipping = toDomain(d.shippingInfo);
        PaymentInfo payment = d.payment == null ? null
                : new PaymentInfo(d.payment.provider, d.payment.orderId, d.payment.paymentId, d.payment.status,
                        d.payment.amount, d.payment.currency, d.payment.paidAt, d.payment.method,
                        d.payment.failureReason, d.payment.refundedPaise == null ? 0 : d.payment.refundedPaise,
                        d.payment.refunds == null ? null : d.payment.refunds.stream().map(r -> new Refund(
                                r.providerRefundId, r.amount == null ? 0 : r.amount, r.reason, r.status, r.actor,
                                r.at)).toList());
        OrderDetails details = new OrderDetails(d.orderNumber, toDomain(d.billingInfo),
                d.customer == null ? null : new Customer(d.customer.name, d.customer.email, d.customer.phone),
                d.statusHistory == null ? null : d.statusHistory.stream()
                        .map(e -> new StatusEntry(OrderStatus.fromWire(e.status), e.at, e.actor)).toList(),
                d.couponCode, d.notes, toDomain(d.attribution));
        List<OrderItem> items = d.orderItems == null ? List.of()
                : d.orderItems.stream().map(MongoOrderRepository::toDomain).toList();
        return new Order(d.id, d.user, shipping, items,
                new Quote(nz(d.subtotal), nz(d.tax), nz(d.shippingCharges), nz(d.discount), nz(d.total)),
                d.status == null ? OrderStatus.PROCESSING : OrderStatus.fromWire(d.status), payment, d.termsVersion,
                d.createdAt, details);
    }

    private static OrderDocument.CustomerDoc toDoc(Customer c) {
        OrderDocument.CustomerDoc d = new OrderDocument.CustomerDoc();
        d.name = c.name();
        d.email = c.email();
        d.phone = c.phone();
        return d;
    }

    private static OrderDocument.StatusEntryDoc toDoc(StatusEntry e) {
        OrderDocument.StatusEntryDoc d = new OrderDocument.StatusEntryDoc();
        d.status = e.status().wireValue();
        d.at = e.at();
        d.actor = e.actor();
        return d;
    }

    private static ShippingInfo toDomain(OrderDocument.ShippingInfoDoc s) {
        return s == null ? null : new ShippingInfo(s.address, s.city, s.state, s.country,
                s.pinCode == null ? null : String.valueOf(s.pinCode), s.name, s.phone);
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    static OrderDocument.AttributionDoc toDoc(OrderAttribution a) {
        if (a == null) {
            return null;
        }
        OrderDocument.AttributionDoc d = new OrderDocument.AttributionDoc();
        d.utmSource = a.utmSource();
        d.utmMedium = a.utmMedium();
        d.utmCampaign = a.utmCampaign();
        d.utmContent = a.utmContent();
        d.utmTerm = a.utmTerm();
        d.gclid = a.gclid();
        d.fbclid = a.fbclid();
        d.fbp = a.fbp();
        d.fbc = a.fbc();
        d.gaClientId = a.gaClientId();
        d.landingPath = a.landingPath();
        d.userAgent = a.userAgent();
        d.sourceUrl = a.sourceUrl();
        d.consent = new OrderDocument.ConsentDoc();
        d.consent.analytics = a.consent().analytics();
        d.consent.marketing = a.consent().marketing();
        d.consent.version = a.consent().version();
        d.consent.at = a.consent().at();
        return d;
    }

    static OrderAttribution toDomain(OrderDocument.AttributionDoc d) {
        if (d == null || d.consent == null) {
            return null;
        }
        return new OrderAttribution(d.utmSource, d.utmMedium, d.utmCampaign, d.utmContent, d.utmTerm, d.gclid,
                d.fbclid, d.fbp, d.fbc, d.gaClientId, d.landingPath, d.userAgent, d.sourceUrl,
                new OrderAttribution.Consent(Boolean.TRUE.equals(d.consent.analytics),
                        Boolean.TRUE.equals(d.consent.marketing), d.consent.version, d.consent.at));
    }
}
