package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.LocationReads;
import com.sixpack.dashboard.domain.RegionLevel;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

@Component
class MongoLocationReads implements LocationReads {

    private static final String COUNTRY = "shippingInfo.country";
    private static final String STATE = "shippingInfo.state";
    private static final String PIN = "shippingInfo.pinCode";

    private final OrderQueries q;

    MongoLocationReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<RegionCount> count(Scope scope, Instant from, Instant to, RegionMatch match, RegionLevel level) {
        // a PIN stored as text or garbage becomes null instead of failing the whole aggregation
        Document pin = new Document("$convert", new Document("input", "$" + PIN).append("to", "long")
                .append("onError", null).append("onNull", null));
        Object key = switch (level) {
            case COUNTRY -> "$" + COUNTRY;
            case STATE -> "$" + STATE;
            case PIN_PREFIX -> new Document("$toString", new Document("$floor",
                    new Document("$divide", List.of(pin, 1000))));
            case PIN -> new Document("$toString", pin);
        };
        List<RegionCount> out = new ArrayList<>();
        for (Document d : q.mongo().aggregate(q.salesBy(scope, from, to, criteria(match), key), OrderQueries.ORDERS,
                Document.class)) {
            Object id = d.get("_id");
            out.add(new RegionCount(id == null ? null : id.toString(), OrderQueries.orders(d),
                    OrderQueries.number(d.get("sales"))));
        }
        return out;
    }

    @Override
    public List<RegionProduct> topProducts(Scope scope, Instant from, Instant to, RegionMatch match, int limit) {
        List<AggregationOperation> agg = new ArrayList<>();
        agg.add(match(q.criteria(Dataset.ORDERS, scope, from, to)));
        Criteria region = criteria(match);
        if (region != null) {
            agg.add(match(region));
        }
        agg.add(OrderQueries.stage("$sort", new Document(OrderQueries.CREATED_AT, 1)));
        agg.add(OrderQueries.stage("$unwind", "$orderItems"));
        if (scope.productId() != null || scope.category() != null) {
            agg.add(match(q.itemCriteria(scope)));
        }
        agg.add(OrderQueries.stage("$group", new Document("_id", "$orderItems.productId")
                .append("name", new Document("$last", "$orderItems.name"))
                .append("units", new Document("$sum", OrderQueries.orZero("$orderItems.quantity")))
                .append("revenue", new Document("$sum", new Document("$multiply", List.of(
                        OrderQueries.orZero("$orderItems.price"), OrderQueries.orZero("$orderItems.quantity")))))));
        agg.add(OrderQueries.stage("$sort", new Document("units", -1).append("_id", 1)));
        agg.add(OrderQueries.stage("$limit", limit));
        return q.mongo().aggregate(agg, OrderQueries.ORDERS, Document.class).getMappedResults().stream()
                .map(d -> new RegionProduct(OrderQueries.hex(d.get("_id")), d.getString("name"),
                        (long) OrderQueries.number(d.get("units")), OrderQueries.number(d.get("revenue"))))
                .toList();
    }

    /** @return null when the match has no constraint */
    private static Criteria criteria(RegionMatch m) {
        if (m.countries() == null && m.states() == null && m.pinPrefix() == null) {
            return null;
        }
        Criteria c = new Criteria();
        if (m.countries() != null) {
            c.and(COUNTRY).in(m.countries());
        }
        if (m.states() != null) {
            c.and(STATE).in(m.states());
        }
        if (m.pinPrefix() != null) {
            c.and(PIN).gte(m.pinPrefix() * 1000L).lt((m.pinPrefix() + 1) * 1000L);
        }
        return c;
    }
}
