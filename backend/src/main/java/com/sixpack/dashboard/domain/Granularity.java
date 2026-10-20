package com.sixpack.dashboard.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** How days are grouped in a trend. Weeks start on Monday. */
public enum Granularity {
    DAY, WEEK, MONTH;

    public static final int MAX_BUCKETS = 400;

    /** Days up to a month, weeks up to a quarter, months beyond. */
    public static Granularity auto(Period period) {
        long days = period.days();
        return days <= 31 ? DAY : days <= 92 ? WEEK : MONTH;
    }

    public LocalDate bucketStart(LocalDate day) {
        return switch (this) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
        };
    }

    /** The start of every bucket touching the period, oldest first. */
    public List<LocalDate> buckets(Period period) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate b = bucketStart(period.from()); !b.isAfter(period.to()); b = next(b)) {
            if (out.size() == MAX_BUCKETS) {
                throw new IllegalArgumentException(
                        "Too many points: pick a coarser granularity (at most " + MAX_BUCKETS + ")");
            }
            out.add(b);
        }
        return out;
    }

    private LocalDate next(LocalDate bucket) {
        return switch (this) {
            case DAY -> bucket.plusDays(1);
            case WEEK -> bucket.plusWeeks(1);
            case MONTH -> bucket.plusMonths(1);
        };
    }
}
