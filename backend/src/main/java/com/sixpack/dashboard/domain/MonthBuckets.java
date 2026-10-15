package com.sixpack.dashboard.domain;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Year-aware month bucketing (the old code compared month numbers only, so January overlapped last January). */
public final class MonthBuckets {

    private MonthBuckets() {
    }

    /** @return {@code length} values, oldest first, the last one being {@code current} */
    public static double[] toArray(Map<YearMonth, Double> values, YearMonth current, int length) {
        double[] out = new double[length];
        values.forEach((month, value) -> {
            long ago = ChronoUnit.MONTHS.between(month, current);
            if (ago >= 0 && ago < length) {
                out[length - 1 - (int) ago] += value;
            }
        });
        return out;
    }

    /** Percentage change from {@code previous} to {@code current}, rounded; 100 when growing from zero. */
    public static long percentChange(double current, double previous) {
        if (previous == 0) {
            return current == 0 ? 0 : 100;
        }
        return Math.round((current - previous) / previous * 100);
    }
}
