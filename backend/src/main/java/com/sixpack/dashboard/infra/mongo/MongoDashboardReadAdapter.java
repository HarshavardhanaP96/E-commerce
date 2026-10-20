package com.sixpack.dashboard.infra.mongo;

import com.sixpack.shared.tenant.TenantMongo;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.project;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.sort;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;

import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
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

    private static final String CREATED_AT = "createdAt";

    private final TenantMongo mongo;

    MongoDashboardReadAdapter(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public long count(Dataset dataset, Scope scope, Instant from, Instant to) {
        return mongo.count(Query.query(criteria(dataset, scope, from, to)), collection(dataset));
    }

    @Override
    public double revenue(Scope scope, Instant from, Instant to) {
        return sum(Dataset.ORDERS, criteria(Dataset.ORDERS, scope, from, to), "total");
    }

    @Override
    public Map<YearMonth, Double> monthly(Dataset dataset, Scope scope, Instant from, String sumField) {
        var projection = project().and(DateOperators.dateOf(CREATED_AT).toString("%Y-%m")).as("ym");
        if (sumField != null) {
            projection = projection.and(sumField).as("v");
        }
        var grouping = sumField == null ? group("ym").count().as("value") : group("ym").sum("v").as("value");
        List<AggregationOperation> agg = List.of(match(criteria(dataset, scope, from, null)), projection, grouping);
        Map<YearMonth, Double> out = new LinkedHashMap<>();
        for (Document d : mongo.aggregate(agg, collection(dataset), Document.class)) {
            Object id = d.get("_id");
            if (id != null) {
                out.put(YearMonth.parse(id.toString()), number(d.get("value")));
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
        Criteria c = criteria(Dataset.PRODUCTS, scope, null, null).and("stock").lte(0);
        return mongo.count(Query.query(c), collection(Dataset.PRODUCTS));
    }

    @Override
    public OrderTotals orderTotals(Scope scope) {
        List<AggregationOperation> agg = List.of(match(criteria(Dataset.ORDERS, scope, null, null)),
                group().sum("tax").as("tax").sum("shippingCharges").as("shipping")
                        .sum("discount").as("discount").sum("total").as("total"));
        Document d = mongo.aggregate(agg, collection(Dataset.ORDERS), Document.class).getUniqueMappedResult();
        if (d == null) {
            return new OrderTotals(0, 0, 0, 0);
        }
        return new OrderTotals(number(d.get("tax")), number(d.get("shipping")), number(d.get("discount")),
                number(d.get("total")));
    }

    @Override
    public List<LocalDate> userBirthDates(Scope scope) {
        Query query = Query.query(criteria(Dataset.USERS, scope, null, null));
        query.fields().include("dob");
        List<LocalDate> out = new ArrayList<>();
        for (Document d : mongo.find(query, Document.class, collection(Dataset.USERS))) {
            if (d.get("dob") instanceof Date dob) {
                out.add(dob.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
            }
        }
        return out;
    }

    @Override
    public List<LatestTransaction> latestOrders(Scope scope, int limit) {
        Query query = Query.query(criteria(Dataset.ORDERS, scope, null, null))
                .with(Sort.by(Sort.Direction.DESC, CREATED_AT)).limit(limit);
        query.fields().include("orderItems", "discount", "total", "status");
        List<LatestTransaction> out = new ArrayList<>();
        for (Document d : mongo.find(query, Document.class, collection(Dataset.ORDERS))) {
            List<?> items = d.getList("orderItems", Object.class, List.of());
            out.add(new LatestTransaction(d.get("_id").toString(), number(d.get("discount")),
                    number(d.get("total")), items.size(), d.getString("status")));
        }
        return out;
    }

    private Map<String, Long> countBy(Dataset dataset, String field, Scope scope) {
        List<AggregationOperation> agg = List.of(match(criteria(dataset, scope, null, null)),
                group(field).count().as("count"));
        Map<String, Long> out = new LinkedHashMap<>();
        for (Document d : mongo.aggregate(agg, collection(dataset), Document.class)) {
            Object key = d.get("_id");
            if (key != null) {
                out.put(key.toString(), (long) number(d.get("count")));
            }
        }
        return out;
    }

    private double sum(Dataset dataset, Criteria criteria, String field) {
        List<AggregationOperation> agg = List.of(match(criteria), group().sum(field).as("sum"));
        Document d = mongo.aggregate(agg, collection(dataset), Document.class).getUniqueMappedResult();
        return d == null ? 0 : number(d.get("sum"));
    }

    private Criteria criteria(Dataset dataset, Scope scope, Instant from, Instant to) {
        Criteria c = new Criteria();
        if (from != null || to != null) {
            Criteria date = c.and(CREATED_AT);
            if (from != null) {
                date.gte(Date.from(from));
            }
            if (to != null) {
                date.lt(Date.from(to));
            }
        }
        switch (dataset) {
            case PRODUCTS -> {
                if (scope.productId() != null) {
                    c.and("_id").is(id(scope.productId()));
                }
                if (scope.category() != null) {
                    c.and("category").is(scope.category());
                }
            }
            case USERS -> {
                if (scope.userId() != null) {
                    c.and("_id").is(id(scope.userId()));
                }
            }
            case ORDERS -> {
                if (scope.userId() != null) {
                    c.and("user").is(scope.userId());
                }
                if (scope.productId() != null) {
                    c.and("orderItems.productId").is(id(scope.productId()));
                } else if (scope.category() != null) {
                    c.and("orderItems.productId").in(productIdsInCategory(scope.category()));
                }
            }
        }
        return c;
    }

    private List<Object> productIdsInCategory(String category) {
        Query query = Query.query(Criteria.where("category").is(category));
        query.fields().include("_id");
        return mongo.find(query, Document.class, collection(Dataset.PRODUCTS)).stream()
                .map(d -> d.get("_id")).toList();
    }

    @Override
    public List<VariantSales> variantSales(String productId) {
        Criteria ofProduct = Criteria.where("orderItems.productId").is(id(productId));
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
        return mongo.aggregate(agg, collection(Dataset.ORDERS), Document.class).getMappedResults().stream()
                .map(d -> new VariantSales(d.getString("_id"), d.getString("label"), d.getString("sku"),
                        (long) number(d.get("units")), number(d.get("revenue")), (long) number(d.get("orders"))))
                .toList();
    }

    private static Object id(String value) {
        return ObjectId.isValid(value) ? new ObjectId(value) : value;
    }

    private static String collection(Dataset dataset) {
        return switch (dataset) {
            case PRODUCTS -> "products";
            case USERS -> "users";
            case ORDERS -> "orders";
        };
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
