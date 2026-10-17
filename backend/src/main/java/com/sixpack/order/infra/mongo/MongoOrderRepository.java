package com.sixpack.order.infra.mongo;

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
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoOrderRepository implements OrderRepository {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final MongoTemplate mongo;

    MongoOrderRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Order save(Order order) {
        OrderDocument doc = order.id() == null ? null : mongo.findById(order.id(), OrderDocument.class);
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
        doc.orderItems = order.items().stream().map(MongoOrderRepository::toDoc).toList();
        if (order.payment() != null) {
            doc.payment = new OrderDocument.PaymentDoc();
            doc.payment.provider = order.payment().provider();
            doc.payment.orderId = order.payment().orderId();
            doc.payment.paymentId = order.payment().paymentId();
            doc.payment.status = order.payment().status();
            doc.payment.amount = order.payment().amount();
            doc.payment.currency = order.payment().currency();
            doc.payment.paidAt = order.payment().paidAt();
        }
        return toDomain(mongo.save(doc));
    }

    @Override
    public Optional<Order> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, OrderDocument.class)).map(MongoOrderRepository::toDomain);
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
    public void anonymiseShippingForUser(String userId) {
        mongo.updateMulti(Query.query(Criteria.where("user").is(userId)),
                new Update()
                        .unset("shippingInfo.address").unset("shippingInfo.pinCode")
                        .unset("shippingInfo.name").unset("shippingInfo.phone")
                        .unset("billingInfo").unset("customer").unset("notes"),
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
        return d;
    }

    static OrderItem toDomain(OrderDocument.OrderItemDoc d) {
        return new OrderItem(d.productId, d.name, d.photo, nz(d.price), d.quantity == null ? 0 : d.quantity, d.sku,
                d.taxRate);
    }

    private static Order toDomain(OrderDocument d) {
        ShippingInfo shipping = toDomain(d.shippingInfo);
        PaymentInfo payment = d.payment == null ? null
                : new PaymentInfo(d.payment.provider, d.payment.orderId, d.payment.paymentId, d.payment.status,
                        d.payment.amount, d.payment.currency, d.payment.paidAt);
        OrderDetails details = new OrderDetails(d.orderNumber, toDomain(d.billingInfo),
                d.customer == null ? null : new Customer(d.customer.name, d.customer.email, d.customer.phone),
                d.statusHistory == null ? null : d.statusHistory.stream()
                        .map(e -> new StatusEntry(OrderStatus.fromWire(e.status), e.at, e.actor)).toList(),
                d.couponCode, d.notes);
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
}
