package com.sixpack.dashboard.infra.mongo;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;

import com.sixpack.dashboard.domain.Sections.CouponUse;
import com.sixpack.dashboard.domain.Sections.CustomerTotals;
import com.sixpack.dashboard.domain.Sections.Place;
import com.sixpack.dashboard.domain.Sections.ProductSales;
import com.sixpack.dashboard.domain.SectionsReadPort;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/** Aggregations for the dashboard sections over the raw `orders` and `products` collections. */
@Component
class MongoSectionsReadAdapter implements SectionsReadPort {

    private static final String ORDERS = "orders";
    private static final String PRODUCTS = "products";
    private static final String REFUNDS = "payment.refunds";

    private final TenantMongo mongo;

    MongoSectionsReadAdapter(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<ProductSales> productSales(Instant from, Instant to) {
        List<ProductSales> out = new ArrayList<>();
        for (Document d : aggregate(placed(from, to), unwindItems(),
                stage("$group", new Document("_id", "$orderItems.productId")
                        .append("name", new Document("$last", "$orderItems.name"))
                        .append("units", new Document("$sum", orZero("$orderItems.quantity")))
                        .append("revenue", new Document("$sum", lineValue()))
                        .append("orders", new Document("$addToSet", "$_id"))),
                stage("$sort", new Document("revenue", -1).append("_id", 1)))) {
            out.add(new ProductSales(id(d.get("_id")), d.getString("name"), number(d.get("units")),
                    number(d.get("revenue")), d.getList("orders", Object.class, List.of()).size()));
        }
        return out;
    }

    @Override
    public Map<String, Double> unitsByLine(Instant from, Instant to) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Document d : aggregate(placed(from, to), unwindItems(),
                stage("$group", new Document("_id", new Document("p", "$orderItems.productId")
                        .append("v", "$orderItems.variantId"))
                        .append("units", new Document("$sum", orZero("$orderItems.quantity")))))) {
            Document key = d.get("_id", Document.class);
            String variant = key.getString("v");
            out.put(id(key.get("p")) + (variant == null ? "" : ":" + variant), number(d.get("units")));
        }
        return out;
    }

    @Override
    public Map<String, double[]> categorySales(Instant from, Instant to) {
        // the lookup is matched on tenantId as well: TenantMongo scopes only the collection being aggregated
        Document lookup = new Document("from", PRODUCTS)
                .append("let", new Document("pid", "$orderItems.productId").append("t", "$tenantId"))
                .append("pipeline", List.of(new Document("$match", new Document("$expr", new Document("$and", List.of(
                                new Document("$eq", List.of("$_id", "$$pid")),
                                new Document("$eq", List.of("$tenantId", "$$t")))))),
                        new Document("$project", new Document("category", 1))))
                .append("as", "product");
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Document d : aggregate(placed(from, to), unwindItems(), stage("$lookup", lookup),
                stage("$group", new Document("_id", new Document("$ifNull", List.of(
                                new Document("$first", "$product.category"), "uncategorised")))
                        .append("revenue", new Document("$sum", lineValue()))
                        .append("units", new Document("$sum", orZero("$orderItems.quantity")))),
                stage("$sort", new Document("revenue", -1).append("_id", 1)))) {
            out.put(String.valueOf(d.get("_id")), new double[] {number(d.get("revenue")), number(d.get("units"))});
        }
        return out;
    }

    @Override
    public List<OnSale> onSale(Instant createdBefore) {
        List<OnSale> out = new ArrayList<>();
        for (Document d : mongo.aggregate(List.of(
                        match(new Criteria().andOperator(
                                new Criteria().orOperator(Criteria.where("status").is("ACTIVE"),
                                        Criteria.where("status").exists(false), Criteria.where("status").is(null)),
                                Criteria.where("createdAt").lt(Date.from(createdBefore)))),
                        stage("$sort", new Document("name", 1))), PRODUCTS, Document.class)) {
            out.add(new OnSale(id(d.get("_id")), d.getString("name"), (int) number(d.get("stock"))));
        }
        return out;
    }

    @Override
    public List<CustomerTotals> customerTotals(Instant from, Instant to) {
        List<CustomerTotals> out = new ArrayList<>();
        for (Document d : aggregate(placedWithUser(from, to),
                stage("$sort", new Document("createdAt", 1)),
                stage("$group", new Document("_id", "$user")
                        .append("name", new Document("$last", "$customer.name"))
                        .append("email", new Document("$last", "$customer.email"))
                        .append("orders", new Document("$sum", 1))
                        .append("revenue", new Document("$sum", orZero("$total")))),
                stage("$sort", new Document("revenue", -1).append("_id", 1)))) {
            out.add(new CustomerTotals(String.valueOf(d.get("_id")), d.getString("name"), d.getString("email"),
                    (long) number(d.get("orders")), number(d.get("revenue"))));
        }
        return out;
    }

    @Override
    public Set<String> orderedBefore(Collection<String> userIds, Instant before) {
        Set<String> out = new HashSet<>();
        if (userIds.isEmpty()) {
            return out;
        }
        for (Document d : aggregate(match(Criteria.where("user").in(userIds).and("createdAt").lt(Date.from(before))),
                stage("$group", new Document("_id", "$user")))) {
            out.add(String.valueOf(d.get("_id")));
        }
        return out;
    }

    @Override
    public List<Place> places(Instant from, Instant to) {
        List<Place> out = new ArrayList<>();
        for (Document d : aggregate(placed(from, to),
                stage("$group", new Document("_id", new Document("state", "$shippingInfo.state")
                        .append("city", "$shippingInfo.city"))
                        .append("orders", new Document("$sum", 1))
                        .append("revenue", new Document("$sum", orZero("$total")))),
                stage("$sort", new Document("revenue", -1).append("orders", -1)))) {
            Document key = d.get("_id", Document.class);
            out.add(new Place(key.getString("state"), key.getString("city"), (long) number(d.get("orders")),
                    number(d.get("revenue"))));
        }
        return out;
    }

    @Override
    public long orders(Instant from, Instant to) {
        Document d = aggregate(placed(from, to), stage("$count", "n")).stream().findFirst().orElse(null);
        return d == null ? 0 : (long) number(d.get("n"));
    }

    @Override
    public List<CouponUse> couponUses(Instant from, Instant to) {
        List<CouponUse> out = new ArrayList<>();
        for (Document d : aggregate(placed(from, to),
                // one $nin: chained .ne() calls on one field keep only the last
                match(Criteria.where("couponCode").nin(null, "")),
                stage("$group", new Document("_id", new Document("$toUpper", "$couponCode"))
                        .append("uses", new Document("$sum", 1))
                        .append("discount", new Document("$sum", orZero("$discount")))
                        .append("revenue", new Document("$sum", orZero("$total")))),
                stage("$sort", new Document("uses", -1).append("revenue", -1).append("_id", 1)))) {
            out.add(new CouponUse(String.valueOf(d.get("_id")), (long) number(d.get("uses")),
                    number(d.get("discount")), number(d.get("revenue"))));
        }
        return out;
    }

    @Override
    public List<MethodTotals> paymentMethods(Instant from, Instant to) {
        List<MethodTotals> out = new ArrayList<>();
        for (Document d : aggregate(placed(from, to),
                stage("$group", new Document("_id", new Document("$ifNull", List.of("$payment.method", "unknown")))
                        .append("orders", new Document("$sum", 1))
                        .append("amount", new Document("$sum", orZero("$total")))),
                stage("$sort", new Document("orders", -1).append("amount", -1).append("_id", 1)))) {
            out.add(new MethodTotals(String.valueOf(d.get("_id")), (long) number(d.get("orders")),
                    number(d.get("amount"))));
        }
        return out;
    }

    @Override
    public List<RefundIssued> refunds(Instant from, Instant to, ZoneId zone) {
        Criteria issued = Criteria.where(REFUNDS + ".at").gte(Date.from(from)).lt(Date.from(to));
        List<RefundIssued> out = new ArrayList<>();
        for (Document d : aggregate(match(issued), stage("$unwind", "$" + REFUNDS),
                match(new Criteria().andOperator(issued, Criteria.where(REFUNDS + ".status").nin("failed", "FAILED"))),
                stage("$project", new Document("day", localDate("$" + REFUNDS + ".at", zone, "%Y-%m-%d"))
                        .append("amount", "$" + REFUNDS + ".amount")))) {
            out.add(new RefundIssued(LocalDate.parse(d.getString("day")), number(d.get("amount"))));
        }
        return out;
    }

    @Override
    public List<TaxGroup> taxGroups(Instant from, Instant to, ZoneId zone) {
        List<TaxGroup> out = new ArrayList<>();
        for (Document d : aggregate(placed(from, to), unwindItems(),
                stage("$group", new Document("_id", new Document("order", "$_id")
                        .append("month", localDate("$createdAt", zone, "%Y-%m"))
                        .append("rate", "$orderItems.taxRate")
                        .append("state", new Document("$ifNull", List.of("$billingInfo.state", "$shippingInfo.state"))))
                        .append("taxable", new Document("$sum", lineValue()))),
                stage("$sort", new Document("_id.month", 1)))) {
            Document key = d.get("_id", Document.class);
            Object rate = key.get("rate");
            out.add(new TaxGroup(String.valueOf(key.get("order")), key.getString("month"),
                    rate == null ? null : number(rate), key.getString("state"), number(d.get("taxable"))));
        }
        return out;
    }

    private List<Document> aggregate(AggregationOperation... stages) {
        return mongo.aggregate(List.of(stages), ORDERS, Document.class).getMappedResults();
    }

    private static AggregationOperation placed(Instant from, Instant to) {
        return match(Criteria.where("createdAt").gte(Date.from(from)).lt(Date.from(to)));
    }

    private static AggregationOperation placedWithUser(Instant from, Instant to) {
        return match(Criteria.where("createdAt").gte(Date.from(from)).lt(Date.from(to)).and("user").ne(null));
    }

    private static AggregationOperation unwindItems() {
        return stage("$unwind", "$orderItems");
    }

    private static Document lineValue() {
        return new Document("$multiply", List.of(orZero("$orderItems.price"), orZero("$orderItems.quantity")));
    }

    private static Document localDate(String field, ZoneId zone, String format) {
        return new Document("$dateToString", new Document("format", format).append("date", field)
                .append("timezone", zone.getId()));
    }

    private static Document orZero(String field) {
        return new Document("$ifNull", List.of(field, 0));
    }

    private static AggregationOperation stage(String operator, Object body) {
        return context -> new Document(operator, body);
    }

    private static String id(Object value) {
        return value == null ? null : value instanceof org.bson.types.ObjectId oid ? oid.toHexString() : value.toString();
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
