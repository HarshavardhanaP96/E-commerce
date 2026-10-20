package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.SalesReads;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoSalesReads implements SalesReads {

    private final OrderQueries q;

    MongoSalesReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<ProductRevenue> byProduct(Scope scope, Instant from, Instant to) {
        List<AggregationOperation> agg = new ArrayList<>();
        agg.add(match(q.criteria(Dataset.ORDERS, scope, from, to)));
        agg.add(OrderQueries.stage("$unwind", "$orderItems"));
        if (scope.productId() != null || scope.category() != null) {
            agg.add(match(q.itemCriteria(scope)));
        }
        agg.add(OrderQueries.stage("$group", new Document("_id", "$orderItems.productId")
                .append("revenue", new Document("$sum", new Document("$multiply", List.of(
                        OrderQueries.orZero("$orderItems.price"), OrderQueries.orZero("$orderItems.quantity")))))
                .append("units", new Document("$sum", OrderQueries.orZero("$orderItems.quantity")))));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> new ProductRevenue(OrderQueries.hex(d.get("_id")), OrderQueries.number(d.get("revenue")),
                        (long) OrderQueries.number(d.get("units"))))
                .toList();
    }

    @Override
    public Map<String, String> categories(Collection<String> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where("_id").in(productIds.stream().map(OrderQueries::id).toList()));
        query.fields().include("category");
        Map<String, String> out = new HashMap<>();
        for (Document d : q.mongo().find(query, Document.class, OrderQueries.PRODUCTS)) {
            if (d.getString("category") != null) {
                out.put(OrderQueries.hex(d.get("_id")), d.getString("category"));
            }
        }
        return out;
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

    @Override
    public List<MethodTotals> byPaymentMethod(Scope scope, Instant from, Instant to) {
        List<AggregationOperation> agg = List.of(match(q.criteria(Dataset.ORDERS, scope, from, to)),
                OrderQueries.stage("$group", new Document("_id",
                        new Document("$ifNull", List.of("$payment.method", "unknown")))
                        .append("orders", new Document("$sum", 1))
                        .append("revenue", new Document("$sum", "$total"))));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> new MethodTotals(String.valueOf(d.get("_id")), (long) OrderQueries.number(d.get("orders")),
                        OrderQueries.number(d.get("revenue"))))
                .toList();
    }
}
