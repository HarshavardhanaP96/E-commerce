package com.sixpack.dashboard.infra.mongo;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.OrderReads;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoOrderReads implements OrderReads {

    private static final String PROCESSING = "Processing";

    private final OrderQueries q;

    MongoOrderReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<OrderFacts> orders(Scope scope, Instant from, Instant to, int max) {
        Query query = Query.query(q.criteria(Dataset.ORDERS, scope, from, to))
                .with(Sort.by(Sort.Direction.DESC, OrderQueries.CREATED_AT)).limit(max);
        return find(query);
    }

    @Override
    public List<OrderFacts> stuck(Scope scope, Instant placedBefore, int limit) {
        Query query = Query.query(stuckCriteria(scope, placedBefore))
                .with(Sort.by(Sort.Direction.ASC, OrderQueries.CREATED_AT)).limit(limit);
        return find(query);
    }

    @Override
    public long stuckCount(Scope scope, Instant placedBefore) {
        return q.mongo().count(Query.query(stuckCriteria(scope, placedBefore)), OrderQueries.ORDERS);
    }

    private Criteria stuckCriteria(Scope scope, Instant placedBefore) {
        return q.criteria(Dataset.ORDERS, scope, null, placedBefore).and("status").is(PROCESSING);
    }

    private List<OrderFacts> find(Query query) {
        query.fields().include("orderNumber", "createdAt", "status", "orderItems.quantity", "total", "discount",
                "payment.method", "payment.refunds.status", "statusHistory");
        return q.mongo().find(query, Document.class, OrderQueries.ORDERS).stream().map(MongoOrderReads::facts)
                .toList();
    }

    private static OrderFacts facts(Document d) {
        int items = d.getList("orderItems", Document.class, List.of()).stream()
                .mapToInt(i -> (int) OrderQueries.number(i.get("quantity"))).sum();
        Document payment = d.get("payment", Document.class);
        boolean refunded = payment != null && payment.getList("refunds", Document.class, List.of()).stream()
                .anyMatch(r -> !"failed".equalsIgnoreCase(r.getString("status")));
        Date placed = d.getDate("createdAt");
        return new OrderFacts(OrderQueries.hex(d.get("_id")), d.getString("orderNumber"),
                placed == null ? null : placed.toInstant(), d.getString("status"), items,
                OrderQueries.number(d.get("total")), OrderQueries.number(d.get("discount")),
                payment == null ? null : payment.getString("method"),
                firstAt(d, "Shipped"), firstAt(d, "Delivered"), refunded);
    }

    private static Instant firstAt(Document d, String status) {
        for (Document e : d.getList("statusHistory", Document.class, List.of())) {
            if (status.equalsIgnoreCase(e.getString("status")) && e.getDate("at") != null) {
                return e.getDate("at").toInstant();
            }
        }
        return null;
    }
}
