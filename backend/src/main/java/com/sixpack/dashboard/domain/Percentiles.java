package com.sixpack.dashboard.domain;

import java.util.List;

/** Percentiles by nearest rank: always one of the measured values, never an interpolation. */
public final class Percentiles {

    private Percentiles() {
    }

    /** @param p percent in (0, 100]; @return null when there are no values */
    public static Double of(List<Double> values, double p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p / 100 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }
}
