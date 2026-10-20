package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class GranularityTest {

    private static Period days(int n) {
        LocalDate from = LocalDate.of(2026, 1, 1);
        return Period.of(from, from.plusDays(n - 1));
    }

    @Test
    void autoPicksDaysUpToAMonthWeeksUpToAQuarterThenMonths() {
        assertThat(Granularity.auto(days(1))).isEqualTo(Granularity.DAY);
        assertThat(Granularity.auto(days(31))).isEqualTo(Granularity.DAY);
        assertThat(Granularity.auto(days(32))).isEqualTo(Granularity.WEEK);
        assertThat(Granularity.auto(days(92))).isEqualTo(Granularity.WEEK);
        assertThat(Granularity.auto(days(93))).isEqualTo(Granularity.MONTH);
    }

    @Test
    void weeksStartOnMondayAndMonthsOnTheFirst() {
        LocalDate thursday = LocalDate.of(2026, 10, 22);
        assertThat(Granularity.DAY.bucketStart(thursday)).isEqualTo(thursday);
        assertThat(Granularity.WEEK.bucketStart(thursday)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(Granularity.MONTH.bucketStart(thursday)).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void bucketsCoverThePeriodOldestFirst() {
        Period p = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14)); // Thu .. Wed
        assertThat(Granularity.WEEK.buckets(p)).containsExactly(
                LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12));
    }

    @Test
    void rejectsMoreThan400Buckets() {
        assertThatThrownBy(() -> Granularity.DAY.buckets(days(401)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("granularity");
        assertThat(Granularity.DAY.buckets(days(400))).hasSize(400);
    }
}
