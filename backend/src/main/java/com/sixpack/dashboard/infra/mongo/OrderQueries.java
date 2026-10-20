package com.sixpack.dashboard.infra.mongo;

import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Building blocks the dashboard's Mongo adapters share: scope criteria, item narrowing, local-day keys and number
 * reads. Works on collection and field names only, so no other module's document classes are touched.
 */
@Component
class OrderQueries {

    static final String ORDERS = "orders";
    static final String PRODUCTS = "products";
    static final String USERS = "users";
    static final String CREATED_AT = "createdAt";

    private final TenantMongo mongo;

    OrderQueries(TenantMongo mongo) {
        this.mongo = mongo;
    }

    TenantMongo mongo() {
        return mongo;
    }

    /**
     * Orders placed in [from, to) and in scope, matched further by {@code extra} (may be null), grouped by
     * {@code key}. Each group has {@code orders} (a count, or a set of ids when narrowed to items — read it with
     * {@link #orders(Document)}), {@code sales} and {@code units}; whole-order groups also have {@code discount}
     * and {@code tax}. Narrowed to a product or category, only the matching items count.
     */
    List<AggregationOperation> salesBy(Scope scope, Instant from, Instant to, Criteria extra, Object key) {
        boolean itemScoped = scope.productId() != null || scope.category() != null;
        List<AggregationOperation> agg = new ArrayList<>();
        agg.add(Aggregation.match(criteria(Dataset.ORDERS, scope, from, to)));
        if (extra != null) {
            agg.add(Aggregation.match(extra));
        }
        if (itemScoped) {
            agg.add(stage("$unwind", "$orderItems"));
            agg.add(Aggregation.match(itemCriteria(scope)));
            agg.add(stage("$group", new Document("_id", key)
                    .append("orders", new Document("$addToSet", "$_id"))
                    .append("sales", new Document("$sum", new Document("$multiply", List.of(
                            orZero("$orderItems.price"), orZero("$orderItems.quantity")))))
                    .append("units", new Document("$sum", orZero("$orderItems.quantity")))));
        } else {
            agg.add(stage("$group", new Document("_id", key)
                    .append("orders", new Document("$sum", 1))
                    .append("sales", new Document("$sum", "$total"))
                    .append("discount", new Document("$sum", "$discount"))
                    .append("tax", new Document("$sum", "$tax"))
                    .append("units", new Document("$sum", new Document("$sum", "$orderItems.quantity")))));
        }
        return agg;
    }

    /** The {@code orders} field of a {@link #salesBy} group: a count, or the size of the set of order ids. */
    static long orders(Document group) {
        Object orders = group.get("orders");
        return orders instanceof List<?> ids ? ids.size() : (long) number(orders);
    }

    /** An id as the hex string the API uses, whatever type Mongo stored it as. */
    static String hex(Object id) {
        return id == null ? null : id.toString();
    }


    Criteria criteria(Dataset dataset, Scope scope, Instant from, Instant to) {
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

    /** After unwinding orderItems: keep only the items of the scoped product or category. */
    Criteria itemCriteria(Scope scope) {
        return scope.productId() != null
                ? Criteria.where("orderItems.productId").is(id(scope.productId()))
                : Criteria.where("orderItems.productId").in(productIdsInCategory(scope.category()));
    }

    List<Object> productIdsInCategory(String category) {
        Query query = Query.query(Criteria.where("category").is(category));
        query.fields().include("_id");
        return mongo.find(query, Document.class, collection(Dataset.PRODUCTS)).stream()
                .map(d -> d.get("_id")).toList();
    }

    static Document localDay(String dateField, ZoneId zone) {
        return new Document("$dateToString", new Document("format", "%Y-%m-%d").append("date", dateField)
                .append("timezone", zone.getId()));
    }

    static Document orZero(String field) {
        return new Document("$ifNull", List.of(field, 0));
    }

    static AggregationOperation stage(String operator, Object body) {
        return context -> new Document(operator, body);
    }

    static Object id(String value) {
        return ObjectId.isValid(value) ? new ObjectId(value) : value;
    }

    static String collection(Dataset dataset) {
        return switch (dataset) {
            case PRODUCTS -> "products";
            case USERS -> "users";
            case ORDERS -> "orders";
        };
    }

    static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
