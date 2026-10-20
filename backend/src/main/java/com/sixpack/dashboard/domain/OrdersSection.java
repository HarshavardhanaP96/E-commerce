package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.OrderReads.OrderFacts;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Are orders flowing and getting delivered? {@code refunds} is null when narrowed to a category. {@code truncated}
 * means the period held more than {@link #FACTS_MAX} orders and the figures cover the newest ones.
 */
public record OrdersSection(@JsonUnwrapped Window window, List<Series.Point> series, List<Series.Point> previousSeries,
                            Map<String, Long> byStatus, Fulfilment fulfilment, List<StuckOrder> stuck,
                            long stuckCount, int stuckAfterDays, Size size, Refunds refunds, boolean truncated,
                            @JsonIgnore List<OrderFacts> rows) implements Section {

    public static final int CSV_MAX_ROWS = 10_000;
    public static final int FACTS_MAX = 50_000;

    public record Leg(Double medianHours, Double p90Hours, long count) {
    }

    public record TimePoint(LocalDate start, Double medianHours) {
    }

    /** @param withoutHistory shipped or delivered orders with no status history, left out of the timings */
    public record Fulfilment(Leg placedToShipped, Leg shippedToDelivered, Leg placedToDelivered, long withoutHistory,
                             List<TimePoint> deliveryTrend) {
    }

    public record StuckOrder(String id, String orderNumber, Instant placedAt, long ageDays, double total) {
    }

    public record CouponUse(String code, long orders, double discount) {
    }

    public record Size(double averageItems, Map<String, Long> bands, List<CouponUse> topCoupons) {
    }

    /** @param rate refunded orders as a percent of orders placed */
    public record Refunds(long orders, double amount, double rate) {
    }

    public static Map<String, Long> byStatus(List<OrderFacts> facts) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("processing", 0L);
        out.put("shipped", 0L);
        out.put("delivered", 0L);
        for (OrderFacts f : facts) {
            String key = f.status() == null ? "unknown" : f.status().toLowerCase(Locale.ROOT);
            out.merge(key, 1L, Long::sum);
        }
        return out;
    }

    public static Fulfilment fulfilment(List<OrderFacts> facts, Period period, Granularity g, ZoneId zone) {
        Leg toShip = leg(facts, f -> f.shippedAt() != null, f -> hours(f.placedAt(), f.shippedAt()));
        Leg shipToDeliver = leg(facts, f -> f.shippedAt() != null && f.deliveredAt() != null,
                f -> hours(f.shippedAt(), f.deliveredAt()));
        Leg toDeliver = leg(facts, f -> f.deliveredAt() != null, f -> hours(f.placedAt(), f.deliveredAt()));
        long withoutHistory = facts.stream().filter(f -> f.shippedAt() == null && f.deliveredAt() == null
                && f.status() != null && !f.status().equalsIgnoreCase("processing")).count();
        Map<LocalDate, List<Double>> byBucket = new LinkedHashMap<>();
        for (LocalDate b : g.buckets(period)) {
            byBucket.put(b, new ArrayList<>());
        }
        for (OrderFacts f : facts) {
            if (f.deliveredAt() != null) {
                List<Double> bucket = byBucket.get(g.bucketStart(f.placedAt().atZone(zone).toLocalDate()));
                if (bucket != null) {
                    bucket.add(hours(f.placedAt(), f.deliveredAt()));
                }
            }
        }
        List<TimePoint> trend = byBucket.entrySet().stream()
                .map(e -> new TimePoint(e.getKey(), Percentiles.of(e.getValue(), 50))).toList();
        return new Fulfilment(toShip, shipToDeliver, toDeliver, withoutHistory, trend);
    }

    public static Size size(List<OrderFacts> facts, int limit) {
        Map<String, Long> bands = new LinkedHashMap<>();
        bands.put("1", 0L);
        bands.put("2-3", 0L);
        bands.put("4-5", 0L);
        bands.put("6+", 0L);
        long items = 0;
        Map<String, CouponUse> coupons = new LinkedHashMap<>();
        for (OrderFacts f : facts) {
            items += f.items();
            String band = f.items() <= 1 ? "1" : f.items() <= 3 ? "2-3" : f.items() <= 5 ? "4-5" : "6+";
            bands.merge(band, 1L, Long::sum);
            if (f.couponCode() != null && !f.couponCode().isBlank()) {
                String code = f.couponCode().trim().toUpperCase(Locale.ROOT);
                CouponUse was = coupons.getOrDefault(code, new CouponUse(code, 0, 0));
                coupons.put(code, new CouponUse(code, was.orders() + 1, was.discount() + f.discount()));
            }
        }
        List<CouponUse> top = coupons.values().stream()
                .sorted(Comparator.comparingLong(CouponUse::orders).reversed().thenComparing(CouponUse::code))
                .limit(limit).toList();
        return new Size(facts.isEmpty() ? 0 : Change.round2((double) items / facts.size()), bands, top);
    }

    public static Refunds refunds(List<OrderFacts> facts, double amount) {
        long refunded = facts.stream().filter(OrderFacts::refunded).count();
        return new Refunds(refunded, amount, Change.share(refunded, facts.size()));
    }

    public static List<StuckOrder> stuck(List<OrderFacts> facts, Instant now) {
        return facts.stream().map(f -> new StuckOrder(f.id(), f.orderNumber(), f.placedAt(),
                Duration.between(f.placedAt(), now).toDays(), f.total())).toList();
    }

    /** Hours between two instants, one decimal. */
    public static double hours(Instant from, Instant to) {
        return Math.round(Duration.between(from, to).toMinutes() / 6.0) / 10.0;
    }

    private static Leg leg(List<OrderFacts> facts, Predicate<OrderFacts> has, Function<OrderFacts, Double> hours) {
        List<Double> values = facts.stream().filter(has).map(hours).toList();
        return new Leg(Percentiles.of(values, 50), Percentiles.of(values, 90), values.size());
    }

    @Override
    public List<String> csvHeader() {
        return List.of("orderNumber", "placedAt", "status", "items", "total", "discount", "paymentMethod",
                "hoursToShip", "hoursToDeliver");
    }

    @Override
    public List<List<Object>> csvRows() {
        List<List<Object>> out = new ArrayList<>();
        for (OrderFacts f : rows.subList(0, Math.min(rows.size(), CSV_MAX_ROWS))) {
            out.add(Arrays.asList(f.orderNumber() != null ? f.orderNumber() : f.id(), f.placedAt(), f.status(),
                    f.items(), f.total(), f.discount(), f.paymentMethod(),
                    f.shippedAt() == null ? null : hours(f.placedAt(), f.shippedAt()),
                    f.deliveredAt() == null ? null : hours(f.placedAt(), f.deliveredAt())));
        }
        if (rows.size() > CSV_MAX_ROWS || truncated) {
            out.add(Arrays.asList("(first " + Math.min(rows.size(), CSV_MAX_ROWS)
                    + " orders only; narrow the date range for the rest)"));
        }
        return out;
    }
}
