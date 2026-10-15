package com.sixpack.order.infra.mongo;

import com.sixpack.order.domain.PaymentIntent;
import com.sixpack.order.domain.PaymentIntentRepository;
import com.sixpack.order.domain.Quote;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoPaymentIntentRepository implements PaymentIntentRepository {

    private final MongoTemplate mongo;

    MongoPaymentIntentRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(PaymentIntent intent) {
        PaymentIntentDocument d = new PaymentIntentDocument();
        d.userId = intent.userId();
        d.provider = intent.provider();
        d.providerOrderId = intent.providerOrderId();
        d.items = intent.items().stream().map(MongoOrderRepository::toDoc).toList();
        d.coupon = intent.coupon();
        d.subtotal = intent.quote().subtotal();
        d.tax = intent.quote().tax();
        d.shippingCharges = intent.quote().shippingCharges();
        d.discount = intent.quote().discount();
        d.total = intent.quote().total();
        d.status = PaymentIntentDocument.CREATED;
        mongo.insert(d);
    }

    @Override
    public Optional<PaymentIntent> findByProviderOrderId(String providerOrderId) {
        PaymentIntentDocument d = mongo.findOne(byProviderOrder(providerOrderId), PaymentIntentDocument.class);
        return Optional.ofNullable(d).map(doc -> new PaymentIntent(doc.id, doc.userId, doc.provider,
                doc.providerOrderId, doc.items.stream().map(MongoOrderRepository::toDomain).toList(), doc.coupon,
                new Quote(doc.subtotal, doc.tax, doc.shippingCharges, doc.discount, doc.total)));
    }

    @Override
    public boolean markConsumed(String providerOrderId) {
        return setStatus(providerOrderId, PaymentIntentDocument.CREATED, PaymentIntentDocument.CONSUMED);
    }

    @Override
    public void markCreated(String providerOrderId) {
        setStatus(providerOrderId, PaymentIntentDocument.CONSUMED, PaymentIntentDocument.CREATED);
    }

    private boolean setStatus(String providerOrderId, String from, String to) {
        Query query = byProviderOrder(providerOrderId).addCriteria(Criteria.where("status").is(from));
        return mongo.updateFirst(query, Update.update("status", to), PaymentIntentDocument.class)
                .getModifiedCount() == 1;
    }

    private static Query byProviderOrder(String providerOrderId) {
        return Query.query(Criteria.where("providerOrderId").is(providerOrderId));
    }
}
