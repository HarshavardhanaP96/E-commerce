package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.project;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.sort;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;

import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.DayTotals;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Aggregations over the raw collections. Uses collection/field names rather than other modules' document
 * classes so module boundaries stay intact.
 */
@Component
class MongoDashboardReadAdapter implements DashboardReadPort {

    private static final String CREATED_AT = OrderQueries.CREATED_AT;
    private static final String REFUNDS = "payment.refunds";

    private final OrderQueries q;

    MongoDashboardReadAdapter(OrderQueries q) {
        this.q = q;
    }

    @Override
    public long count(Dataset dataset, Scope scope, Instant from, Instant to) {
        return q.mongo().count(Query.query(q.criteria(dataset, scope, from, to)), OrderQueries.collection(dataset));
    }

    @Override
    public double revenue(Scope scope, Instant from, Instant to) {
        return sum(Dataset.ORDERS, q.criteria(Dataset.ORDERS, scope, from, to), "total");
    }

    @Override
    public Map<YearMonth, Double> monthly(Dataset dataset, Scope scope, Instant from, String sumField) {
        var projection = project().and(DateOperators.dateOf(CREATED_AT).toString("%Y-%m")).as("ym");
        if (sumField != null) {
            projection = projection.and(sumField).as("v");
        }
        var grouping = sumField == null ? group("ym").count().as("value") : group("ym").sum("v").as("value");
        List<AggregationOperation> agg = List.of(match(q.criteria(dataset, scope, from, null)), projection, grouping);
        Map<YearMonth, Double> out = new LinkedHashMap<>();
        for (Document d : q.mongo().aggregate(agg, OrderQueries.collection(dataset), Document.class)) {
            Object id = d.get("_id");
            if (id != null) {
                out.put(YearMonth.parse(id.toString()), OrderQueries.number(d.get("value")));
            }
        }
        return out;
    }

    @Override
    public Map<String, Long> productsByCategory(Scope scope) {
        return countBy(Dataset.PRODUCTS, "category", scope);
    }

    @Override
    public Map<String, Long> usersByGender(Scope scope) {
        return countBy(Dataset.USERS, "gender", scope);
    }

    @Override
    public Map<String, Long> usersByRole(Scope scope) {
        return countBy(Dataset.USERS, "role", scope);
    }

    @Override
    public Map<String, Long> ordersByStatus(Scope scope) {
        return countBy(Dataset.ORDERS, "status", scope);
    }

    @Override
    public long outOfStock(Scope scope) {
        Criteria c = q.criteria(Dataset.PRODUCTS, scope, null, null).and("stock").lte(0);
        return q.mongo().count(Query.query(c), OrderQueries.collection(Dataset.PRODUCTS));
    }

    @Override
    public OrderTotals orderTotals(Scope scope) {
        List<AggregationOperation> agg = List.of(match(q.criteria(Dataset.ORDERS, scope, null, null)),
                group().sum("tax").as("tax").sum("shippingCharges").as("shipping")
                        .sum("discount").as("discount").sum("total").as("total"));
        Document d = q.mongo().aggregate(agg, OrderQueries.collection(Dataset.ORDERS), Document.class)
                .getUniqueMappedResult();
        if (d == null) {
            return new OrderTotals(0, 0, 0, 0);
        }
        return new OrderTotals(OrderQueries.number(d.get("tax")), OrderQueries.number(d.get("shipping")),
                OrderQueries.number(d.get("discount")), OrderQueries.number(d.get("total")));
    }

    @Override
    public List<LocalDate> userBirthDates(Scope scope) {
        Query query = Query.query(q.criteria(Dataset.USERS, scope, null, null));
        query.fields().include("dob");
        List<LocalDate> out = new ArrayList<>();
        for (Document d : q.mongo().find(query, Document.class, OrderQueries.collection(Dataset.USERS))) {
            if (d.get("dob") instanceof Date dob) {
                out.add(dob.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
            }
        }
        return out;
    }

    @Override
    public List<LatestTransaction> latestOrders(Scope scope, int limit) {
        Query query = Query.query(q.criteria(Dataset.ORDERS, scope, null, null))
                .with(Sort.by(Sort.Direction.DESC, CREATED_AT)).limit(limit);
        query.fields().include("orderItems", "discount", "total", "status");
        List<LatestTransaction> out = new ArrayList<>();
        for (Document d : q.mongo().find(query, Document.class, OrderQueries.collection(Dataset.ORDERS))) {
            List<?> items = d.getList("orderItems", Object.class, List.of());
            out.add(new LatestTransaction(d.get("_id").toString(), OrderQueries.number(d.get("discount")),
                    OrderQueries.number(d.get("total")), items.size(), d.getString("status")));
        }
        return out;
    }

    @Override
    public Map<LocalDate, DayTotals> dailyOrders(Scope scope, Instant from, Instant to, ZoneId zone) {
        boolean itemScoped = scope.productId() != null || scope.category() != null;
        Map<LocalDate, DayTotals> out = new LinkedHashMap<>();
        for (Document d : q.mongo().aggregate(q.salesBy(scope, from, to, null,
                OrderQueries.localDay("$createdAt", zone)), OrderQueries.ORDERS, Document.class)) {
            out.put(LocalDate.parse(d.getString("_id")), new DayTotals(OrderQueries.orders(d),
                    OrderQueries.number(d.get("sales")), OrderQueries.number(d.get("units")),
                    itemScoped ? null : OrderQueries.number(d.get("discount")),
                    itemScoped ? null : OrderQueries.number(d.get("tax"))));
        }
        return out;
    }

    @Override
    public Map<LocalDate, Double> dailyRefunds(Scope scope, Instant from, Instant to, ZoneId zone) {
        Criteria issued = Criteria.where(REFUNDS + ".at").gte(Date.from(from)).lt(Date.from(to));
        List<AggregationOperation> agg = List.of(
                match(q.criteria(Dataset.ORDERS, scope, null, null).andOperator(issued)),
                OrderQueries.stage("$unwind", "$" + REFUNDS),
                match(new Criteria().andOperator(issued,
                        Criteria.where(REFUNDS + ".status").nin("failed", "FAILED"))),
                OrderQueries.stage("$group", new Document("_id", OrderQueries.localDay("$" + REFUNDS + ".at", zone))
                        .append("amount", new Document("$sum", "$" + REFUNDS + ".amount"))));
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        for (Document d : q.mongo().aggregate(agg, OrderQueries.collection(Dataset.ORDERS), Document.class)) {
            out.put(LocalDate.parse(d.getString("_id")), OrderQueries.number(d.get("amount")));
        }
        return out;
    }

    @Override
    public Optional<Instant> firstOrderAt() {
        Query query = new Query().with(Sort.by(Sort.Direction.ASC, CREATED_AT)).limit(1);
        query.fields().include(CREATED_AT);
        return q.mongo().find(query, Document.class, OrderQueries.collection(Dataset.ORDERS)).stream()
                .map(d -> d.getDate(CREATED_AT)).filter(Objects::nonNull).map(Date::toInstant).findFirst();
    }

    @Override
    public Map<LocalDate, Long> newCustomers(Instant from, Instant to, ZoneId zone) {
        List<AggregationOperation> agg = List.of(
                match(Criteria.where("user").ne(null)),
                OrderQueries.stage("$group", new Document("_id", "$user")
                        .append("first", new Document("$min", "$" + CREATED_AT))),
                match(Criteria.where("first").gte(Date.from(from)).lt(Date.from(to))),
                OrderQueries.stage("$group", new Document("_id", OrderQueries.localDay("$first", zone))
                        .append("count", new Document("$sum", 1))));
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        for (Document d : q.mongo().aggregate(agg, OrderQueries.collection(Dataset.ORDERS), Document.class)) {
            out.put(LocalDate.parse(d.getString("_id")), (long) OrderQueries.number(d.get("count")));
        }
        return out;
    }

    private Map<String, Long> countBy(Dataset dataset, String field, Scope scope) {
        List<AggregationOperation> agg = List.of(match(q.criteria(dataset, scope, null, null)),
                group(field).count().as("count"));
        Map<String, Long> out = new LinkedHashMap<>();
        for (Document d : q.mongo().aggregate(agg, OrderQueries.collection(dataset), Document.class)) {
            Object key = d.get("_id");
            if (key != null) {
                out.put(key.toString(), (long) OrderQueries.number(d.get("count")));
            }
        }
        return out;
    }

    private double sum(Dataset dataset, Criteria criteria, String field) {
        List<AggregationOperation> agg = List.of(match(criteria), group().sum(field).as("sum"));
        Document d = q.mongo().aggregate(agg, OrderQueries.collection(dataset), Document.class).getUniqueMappedResult();
        return d == null ? 0 : OrderQueries.number(d.get("sum"));
    }

    @Override
    public List<VariantSales> variantSales(String productId) {
        Criteria ofProduct = Criteria.where("orderItems.productId").is(OrderQueries.id(productId));
        List<AggregationOperation> agg = List.of(
                match(ofProduct),
                sort(Sort.by(CREATED_AT)),
                unwind("orderItems"),
                match(ofProduct),
                group("orderItems.variantId")
                        .last("orderItems.variantLabel").as("label")
                        .last("orderItems.sku").as("sku")
                        .sum("orderItems.quantity").as("units")
                        .sum(ArithmeticOperators.Multiply.valueOf("orderItems.price")
                                .multiplyBy("orderItems.quantity")).as("revenue")
                        .count().as("orders"),
                sort(Sort.by(Sort.Direction.DESC, "units").and(Sort.by("label"))));
        return q.mongo().aggregate(agg, OrderQueries.collection(Dataset.ORDERS), Document.class).getMappedResults()
                .stream()
                .map(d -> new VariantSales(d.getString("_id"), d.getString("label"), d.getString("sku"),
                        (long) OrderQueries.number(d.get("units")), OrderQueries.number(d.get("revenue")),
                        (long) OrderQueries.number(d.get("orders"))))
                .toList();
    }
}
