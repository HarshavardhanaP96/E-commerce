package com.sixpack.dashboard.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Revenue and orders per bucket, every bucket present, oldest first. */
public final class Series {

    public record Point(LocalDate start, double revenue, long orders) {
    }

    private Series() {
    }

    /** Net refunds are taken off the bucket of the day they were issued. */
    public static List<Point> of(Map<LocalDate, DayTotals> days, Map<LocalDate, Double> refunds, Period period,
                                 Granularity granularity, Basis basis) {
        Map<LocalDate, double[]> buckets = new LinkedHashMap<>();
        for (LocalDate b : granularity.buckets(period)) {
            buckets.put(b, new double[2]);
        }
        days.forEach((day, t) -> {
            double[] b = buckets.get(granularity.bucketStart(day));
            if (b != null) {
                b[0] += t.sales();
                b[1] += t.orders();
            }
        });
        if (basis == Basis.NET && refunds != null) {
            refunds.forEach((day, amount) -> {
                double[] b = buckets.get(granularity.bucketStart(day));
                if (b != null) {
                    b[0] -= amount;
                }
            });
        }
        return buckets.entrySet().stream()
                .map(e -> new Point(e.getKey(), e.getValue()[0], (long) e.getValue()[1])).toList();
    }
}
