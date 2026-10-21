package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where the money comes from: the trend and what made up the takings. Revenue by category and by payment method live
 * on the Products and Payments tabs. {@code breakdown} is null when the view is narrowed to a category, since it
 * belongs to whole orders.
 *
 * @param previousSeries the comparison period's trend, null when the comparison is off
 */
public record SalesSection(@JsonUnwrapped Window window, String basis, List<Series.Point> series,
                           List<Series.Point> previousSeries, Breakdown breakdown) implements Section {

    /** @param charged what customers paid (order totals) before refunds; {@code net} = charged − refunds */
    public record Breakdown(double charged, double discount, double tax, double shipping, double refunds, double net,
                            long couponOrders, double couponOrderShare) {
    }

    public static Breakdown breakdown(SalesReads.Breakdown b, double refunds) {
        return new Breakdown(b.total(), b.discount(), b.tax(), b.shipping(), refunds, b.total() - refunds,
                b.couponOrders(), Change.share(b.couponOrders(), b.orders()));
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
