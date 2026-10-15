package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.DashboardReadPort.LatestTransaction;
import com.sixpack.dashboard.domain.DashboardReadPort.OrderTotals;
import com.sixpack.dashboard.domain.Dataset;
import com.sixpack.dashboard.domain.MonthBuckets;
import com.sixpack.dashboard.domain.Scope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Builds the admin dashboard payloads. All month boundaries are UTC. */
@Service
public class DashboardService {

    public record Counts(double revenue, long product, long user, long order) {
    }

    public record ChangePercent(long revenue, long product, long user, long order) {
    }

    public record CategoryShare(String category, long percent) {
    }

    public record Stats(Counts count, ChangePercent changePercent, Map<String, double[]> chart,
                        List<CategoryShare> categoryCount, Map<String, Long> genderRatio,
                        List<LatestTransaction> latestTransaction) {
    }

    public record Pie(Map<String, Long> orderFulfillment, List<CategoryShare> productCategories,
                      Map<String, Long> stockAvailability, Map<String, Double> revenueDistribution,
                      Map<String, Long> ageGroup, Map<String, Long> userCategory) {
    }

    private final DashboardReadPort read;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    DashboardService(DashboardReadPort read) {
        this(read, Clock.systemUTC());
    }

    DashboardService(DashboardReadPort read, Clock clock) {
        this.read = read;
        this.clock = clock;
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'stats:' + #scope")
    public Stats stats(Scope scope) {
        YearMonth now = YearMonth.now(clock);
        Instant thisStart = start(now);
        Instant lastStart = start(now.minusMonths(1));

        ChangePercent change = new ChangePercent(
                MonthBuckets.percentChange(read.revenue(scope, thisStart, null), read.revenue(scope, lastStart, thisStart)),
                pct(Dataset.PRODUCTS, scope, thisStart, lastStart),
                pct(Dataset.USERS, scope, thisStart, lastStart),
                pct(Dataset.ORDERS, scope, thisStart, lastStart));
        long products = read.count(Dataset.PRODUCTS, scope, null, null);
        Counts counts = new Counts(read.revenue(scope, null, null), products,
                read.count(Dataset.USERS, scope, null, null), read.count(Dataset.ORDERS, scope, null, null));

        Instant sixMonths = start(now.minusMonths(5));
        Map<String, double[]> chart = Map.of(
                "order", MonthBuckets.toArray(read.monthly(Dataset.ORDERS, scope, sixMonths, null), now, 6),
                "revenue", MonthBuckets.toArray(read.monthly(Dataset.ORDERS, scope, sixMonths, "total"), now, 6));

        Map<String, Long> gender = read.usersByGender(scope);
        return new Stats(counts, change, chart, shares(read.productsByCategory(scope)),
                Map.of("male", gender.getOrDefault("male", 0L), "female", gender.getOrDefault("female", 0L)),
                read.latestOrders(scope, 4));
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'pie:' + #scope")
    public Pie pie(Scope scope) {
        Map<String, Long> status = read.ordersByStatus(scope);
        long products = read.count(Dataset.PRODUCTS, scope, null, null);
        long outOfStock = read.outOfStock(scope);
        OrderTotals totals = read.orderTotals(scope);
        Map<String, Long> roles = read.usersByRole(scope);

        LocalDate today = LocalDate.now(clock);
        long teen = 0;
        long adult = 0;
        long old = 0;
        for (LocalDate dob : read.userBirthDates(scope)) {
            int age = java.time.Period.between(dob, today).getYears();
            if (age < 20) {
                teen++;
            } else if (age <= 40) {
                adult++;
            } else {
                old++;
            }
        }

        return new Pie(
                Map.of("processing", status.getOrDefault("Processing", 0L),
                        "shipped", status.getOrDefault("Shipped", 0L),
                        "delivered", status.getOrDefault("Delivered", 0L)),
                shares(read.productsByCategory(scope)),
                Map.of("inStock", products - outOfStock, "outOfStock", outOfStock),
                Map.of("totalTax", totals.tax(), "totalShippingCharges", totals.shippingCharges(),
                        "totalDiscount", totals.discount(), "totalIncome", totals.total()),
                Map.of("teen", teen, "adult", adult, "old", old),
                Map.of("admin", roles.getOrDefault("admin", 0L), "customer", roles.getOrDefault("user", 0L)));
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'bar:' + #scope")
    public Map<String, double[]> bar(Scope scope) {
        YearMonth now = YearMonth.now(clock);
        Instant six = start(now.minusMonths(5));
        Instant twelve = start(now.minusMonths(11));
        return Map.of(
                "products", MonthBuckets.toArray(read.monthly(Dataset.PRODUCTS, scope, six, null), now, 6),
                "users", MonthBuckets.toArray(read.monthly(Dataset.USERS, scope, six, null), now, 6),
                "orders", MonthBuckets.toArray(read.monthly(Dataset.ORDERS, scope, twelve, null), now, 12));
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'line:' + #scope")
    public Map<String, double[]> line(Scope scope) {
        YearMonth now = YearMonth.now(clock);
        Instant twelve = start(now.minusMonths(11));
        return Map.of(
                "products", MonthBuckets.toArray(read.monthly(Dataset.PRODUCTS, scope, twelve, null), now, 12),
                "users", MonthBuckets.toArray(read.monthly(Dataset.USERS, scope, twelve, null), now, 12),
                "discount", MonthBuckets.toArray(read.monthly(Dataset.ORDERS, scope, twelve, "discount"), now, 12),
                "revenue", MonthBuckets.toArray(read.monthly(Dataset.ORDERS, scope, twelve, "total"), now, 12));
    }

    private long pct(Dataset dataset, Scope scope, Instant thisStart, Instant lastStart) {
        return MonthBuckets.percentChange(read.count(dataset, scope, thisStart, null),
                read.count(dataset, scope, lastStart, thisStart));
    }

    static List<CategoryShare> shares(Map<String, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new CategoryShare(e.getKey(), total == 0 ? 0 : Math.round(e.getValue() * 100.0 / total)))
                .toList();
    }

    private static Instant start(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
