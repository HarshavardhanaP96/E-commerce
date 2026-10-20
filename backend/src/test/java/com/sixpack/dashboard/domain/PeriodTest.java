package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class PeriodTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void boundsAreLocalMidnightsInTheShopZoneWithAnExclusiveEnd() {
        Period p = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(p.start(IST)).isEqualTo(Instant.parse("2026-09-30T18:30:00Z"));
        assertThat(p.end(IST)).isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        assertThat(p.days()).isEqualTo(31);
    }

    @Test
    void previousIsTheSameLengthEndingTheDayBefore() {
        Period p = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(p.previous()).isEqualTo(Period.of(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30)));
    }

    @Test
    void yearBeforeShiftsBothEndsByOneYear() {
        Period p = Period.of(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29));
        assertThat(p.yearBefore()).isEqualTo(Period.of(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28)));
    }

    @Test
    void lastDaysEndsToday() {
        assertThat(Period.lastDays(LocalDate.of(2026, 10, 20), 30))
                .isEqualTo(Period.of(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 20)));
    }

    @Test
    void rejectsAnEndBeforeTheStart() {
        assertThatThrownBy(() -> Period.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from");
    }

    @Test
    void rejectsRangesLongerThanTwoYears() {
        assertThatThrownBy(() -> Period.of(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("731");
    }

    @Test
    void comparisonPicksThePeriodToCompareWith() {
        Period p = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(Compare.PREVIOUS.apply(p)).isEqualTo(p.previous());
        assertThat(Compare.YEAR.apply(p)).isEqualTo(p.yearBefore());
        assertThat(Compare.NONE.apply(p)).isNull();
    }
}
