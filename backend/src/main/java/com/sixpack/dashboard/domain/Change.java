package com.sixpack.dashboard.domain;

/** Change and share figures shared by the sections. */
public final class Change {

    private Change() {
    }

    /** Whole-number percent change, or null when there is nothing to compare with. */
    public static Long percent(double current, Double previous) {
        return previous == null ? null : MonthBuckets.percentChange(current, previous);
    }

    /** {@code part} as a percent of {@code whole}, one decimal; 0 when the whole is 0. */
    public static double share(double part, double whole) {
        return whole == 0 ? 0 : Math.round(part / whole * 1000) / 10.0;
    }

    public static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
