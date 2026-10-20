package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.SalesReads.MethodTotals;
import com.sixpack.dashboard.domain.SalesReads.ProductRevenue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the money comes from. {@code breakdown} and {@code byPaymentMethod} are null when the view is narrowed to a
 * category, since they belong to whole orders.
 *
 * @param previousSeries the comparison period's trend, null when the comparison is off
 */
public record SalesSection(@JsonUnwrapped Window window, String basis, List<Series.Point> series,
                           List<Series.Point> previousSeries, List<CategoryRow> byCategory, Breakdown breakdown,
                           List<MethodRow> byPaymentMethod) implements Section {

    public static final String UNCATEGORISED = "uncategorised";

    public record CategoryRow(String category, double revenue, long units, double share, Long change) {
    }

    /** @param charged what customers paid (order totals) before refunds; {@code net} = charged − refunds */
    public record Breakdown(double charged, double discount, double tax, double shipping, double refunds, double net,
                            long couponOrders, double couponOrderShare) {
    }

    public record MethodRow(String method, long orders, double revenue, double share) {
    }

    /** @param before null when the comparison is off */
    public static List<CategoryRow> byCategory(List<ProductRevenue> now, List<ProductRevenue> before,
                                               Map<String, String> categories) {
        Map<String, double[]> current = totals(now, categories);
        Map<String, double[]> previous = before == null ? null : totals(before, categories);
        double all = current.values().stream().mapToDouble(v -> v[0]).sum();
        return current.entrySet().stream()
                .map(e -> new CategoryRow(e.getKey(), e.getValue()[0], (long) e.getValue()[1],
                        Change.share(e.getValue()[0], all), previous == null ? null
                        : Change.percent(e.getValue()[0], previous.getOrDefault(e.getKey(), new double[2])[0])))
                .sorted(Comparator.comparingDouble(CategoryRow::revenue).reversed()
                        .thenComparing(CategoryRow::category))
                .toList();
    }

    public static Breakdown breakdown(SalesReads.Breakdown b, double refunds) {
        return new Breakdown(b.total(), b.discount(), b.tax(), b.shipping(), refunds, b.total() - refunds,
                b.couponOrders(), Change.share(b.couponOrders(), b.orders()));
    }

    public static List<MethodRow> methods(List<MethodTotals> rows) {
        long all = rows.stream().mapToLong(MethodTotals::orders).sum();
        return rows.stream().map(m -> new MethodRow(m.method(), m.orders(), m.revenue(), Change.share(m.orders(), all)))
                .sorted(Comparator.comparingLong(MethodRow::orders).reversed().thenComparing(MethodRow::method))
                .toList();
    }

    private static Map<String, double[]> totals(List<ProductRevenue> rows, Map<String, String> categories) {
        Map<String, double[]> out = new HashMap<>();
        for (ProductRevenue r : rows) {
            double[] t = out.computeIfAbsent(categories.getOrDefault(r.productId(), UNCATEGORISED),
                    k -> new double[2]);
            t[0] += r.revenue();
            t[1] += r.units();
        }
        return out;
    }

    @Override
    public List<String> csvHeader() {
        return List.of("start", "revenue", "orders", "previousRevenue");
    }

    @Override
    public List<List<Object>> csvRows() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            Series.Point p = series.get(i);
            Double before = previousSeries != null && i < previousSeries.size() ? previousSeries.get(i).revenue() : null;
            rows.add(Arrays.asList(p.start(), p.revenue(), p.orders(), before));
        }
        return rows;
    }
}
