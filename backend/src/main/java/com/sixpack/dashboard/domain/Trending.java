package com.sixpack.dashboard.domain;

import com.sixpack.dashboard.domain.Sections.ProductSales;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * The products whose units sold rose or fell most against the previous period of the same length. Products that
 * sold fewer than {@link #MIN_UNITS} in both periods are left out, so 1 → 2 never shows as +100%, and lines with
 * no product id are left out. Ranked by the change in units, not the percent; ties by name, then id.
 */
public record Trending(List<Row> rising, List<Row> falling) {

    public static final int MIN_UNITS = 3;

    /**
     * @param change percent change in units, rounded; null when {@code isNew}
     * @param isNew  nothing sold in the previous period
     */
    public record Row(String productId, String name, double units, double previousUnits, Long change,
                      boolean isNew) {
    }

    /**
     * @param now    the period's sales; its names win over {@code before}'s
     * @param before the previous period's sales
     * @param limit  rows in each list; 0 keeps every row
     */
    public static Trending of(List<ProductSales> now, List<ProductSales> before, int limit) {
        Map<String, ProductSales> current = index(now);
        Map<String, ProductSales> previous = index(before);
        Set<String> ids = new LinkedHashSet<>(current.keySet());
        ids.addAll(previous.keySet());
        List<Row> rows = new ArrayList<>();
        for (String id : ids) {
            ProductSales a = current.get(id);
            ProductSales b = previous.get(id);
            double units = a == null ? 0 : a.units();
            double was = b == null ? 0 : b.units();
            if (Math.max(units, was) < MIN_UNITS) {
                continue;
            }
            String name = a != null && a.name() != null ? a.name() : b == null ? null : b.name();
            boolean isNew = was == 0;
            rows.add(new Row(id, name, units, was, isNew ? null : MonthBuckets.percentChange(units, was), isNew));
        }
        long max = limit > 0 ? limit : Long.MAX_VALUE;
        return new Trending(
                rows.stream().filter(r -> r.units() > r.previousUnits())
                        .sorted(biggest(r -> r.units() - r.previousUnits())).limit(max).toList(),
                rows.stream().filter(r -> r.units() < r.previousUnits())
                        .sorted(biggest(r -> r.previousUnits() - r.units())).limit(max).toList());
    }

    private static Comparator<Row> biggest(ToDoubleFunction<Row> delta) {
        return Comparator.comparingDouble(delta).reversed()
                .thenComparing(Row::name, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Row::productId);
    }

    private static Map<String, ProductSales> index(List<ProductSales> sales) {
        Map<String, ProductSales> out = new LinkedHashMap<>();
        for (ProductSales s : sales) {
            if (s.productId() != null) {
                out.put(s.productId(), s);
            }
        }
        return out;
    }
}
