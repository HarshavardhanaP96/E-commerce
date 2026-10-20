package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.OverviewReads.WeekHourCell;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The period's headline numbers and trend (the same as {@code /summary}) plus when orders come in.
 *
 * @param byHour orders per store-local hour, index 0..23
 * @param heat   orders per weekday (row 0 = Monday) and hour
 */
public record OverviewSection(@JsonUnwrapped Window window, String basis, Kpis current, Kpis previous,
                              Kpis.Change change, List<Series.Point> series, List<WeekdayTotals> byWeekday,
                              long[] byHour, long[][] heat) implements Section {

    /** @param revenue sales before refunds */
    public record WeekdayTotals(int weekday, long orders, double revenue) {
    }

    public static OverviewSection of(Window window, String basis, Kpis current, Kpis previous, Kpis.Change change,
                                     List<Series.Point> series, List<WeekHourCell> cells) {
        long[] orders = new long[7];
        double[] revenue = new double[7];
        long[] byHour = new long[24];
        long[][] heat = new long[7][24];
        for (WeekHourCell c : cells) {
            int day = c.weekday() - 1;
            orders[day] += c.orders();
            revenue[day] += c.sales();
            byHour[c.hour()] += c.orders();
            heat[day][c.hour()] += c.orders();
        }
        List<WeekdayTotals> byWeekday = IntStream.range(0, 7)
                .mapToObj(i -> new WeekdayTotals(i + 1, orders[i], revenue[i])).toList();
        return new OverviewSection(window, basis, current, previous, change, series, byWeekday, byHour, heat);
    }

    @Override
    public List<String> csvHeader() {
        return List.of("start", "revenue", "orders");
    }

    @Override
    public List<List<Object>> csvRows() {
        return series.stream().map(p -> List.<Object>of(p.start(), p.revenue(), p.orders())).toList();
    }
}
