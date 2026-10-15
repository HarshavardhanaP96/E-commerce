package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonthBucketsTest {

    @Test
    void placesValuesByYearAwareDistance() {
        YearMonth now = YearMonth.of(2026, 1);
        double[] out = MonthBuckets.toArray(Map.of(
                YearMonth.of(2026, 1), 5.0,
                YearMonth.of(2025, 12), 3.0,
                YearMonth.of(2025, 1), 99.0, // same month number, a year ago: must be ignored for 6 buckets
                YearMonth.of(2025, 8), 1.0), now, 6);
        assertThat(out).containsExactly(1.0, 0, 0, 0, 3.0, 5.0);
    }

    @Test
    void percentChange() {
        assertThat(MonthBuckets.percentChange(0, 0)).isZero();
        assertThat(MonthBuckets.percentChange(5, 0)).isEqualTo(100);
        assertThat(MonthBuckets.percentChange(150, 100)).isEqualTo(50);
        assertThat(MonthBuckets.percentChange(50, 100)).isEqualTo(-50);
    }
}
