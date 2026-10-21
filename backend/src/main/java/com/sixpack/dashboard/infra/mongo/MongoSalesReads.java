package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.SalesReads;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Component;

@Component
class MongoSalesReads implements SalesReads {

    private final OrderQueries q;

    MongoSalesReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public Breakdown breakdown(Scope scope, Instant from, Instant to) {
        Document hasCoupon = new Document("$cond", List.of(new Document("$gt", List.of(
                new Document("$strLenCP", new Document("$ifNull", List.of("$couponCode", ""))), 0)), 1, 0));
        List<AggregationOperation> agg = List.of(match(q.criteria(Dataset.ORDERS, scope, from, to)),
                OrderQueries.stage("$group", new Document("_id", null)
                        .append("orders", new Document("$sum", 1))
                        .append("total", new Document("$sum", "$total"))
                        .append("discount", new Document("$sum", "$discount"))
                        .append("tax", new Document("$sum", "$tax"))
                        .append("shipping", new Document("$sum", "$shippingCharges"))
                        .append("coupons", new Document("$sum", hasCoupon))));
        Document d = q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getUniqueMappedResult();
        if (d == null) {
            return new Breakdown(0, 0, 0, 0, 0, 0);
        }
        return new Breakdown((long) OrderQueries.number(d.get("orders")), OrderQueries.number(d.get("total")),
                OrderQueries.number(d.get("discount")), OrderQueries.number(d.get("tax")),
                OrderQueries.number(d.get("shipping")), (long) OrderQueries.number(d.get("coupons")));
    }
}
