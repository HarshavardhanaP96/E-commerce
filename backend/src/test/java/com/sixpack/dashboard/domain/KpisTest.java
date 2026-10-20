package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KpisTest {

    private static final DayTotals A = new DayTotals(2, 1500, 3, 100.0, 50.0);
    private static final DayTotals B = new DayTotals(1, 500, 1, 0.0, 25.0);

    @Test
    void netRevenueSubtractsRefunds() {
        Kpis k = Kpis.of(List.of(A, B), 200.0, Basis.NET);
        assertThat(k.revenue()).isEqualTo(1800);
        assertThat(k.orders()).isEqualTo(3);
        assertThat(k.averageOrderValue()).isEqualTo(600);
        assertThat(k.units()).isEqualTo(4);
        assertThat(k.refunds()).isEqualTo(200.0);
        assertThat(k.discount()).isEqualTo(100.0);
        assertThat(k.tax()).isEqualTo(75.0);
    }

    @Test
    void grossRevenueIgnoresRefunds() {
        assertThat(Kpis.of(List.of(A, B), 200.0, Basis.GROSS).revenue()).isEqualTo(2000);
    }

    @Test
    void noOrdersMeansZeroAverage() {
        Kpis k = Kpis.of(List.of(), 0.0, Basis.NET);
        assertThat(k.orders()).isZero();
        assertThat(k.averageOrderValue()).isZero();
    }

    @Test
    void itemScopedTotalsLeaveOrderLevelAmountsUnknown() {
        DayTotals item = new DayTotals(1, 900, 2, null, null);
        Kpis k = Kpis.of(List.of(item), null, Basis.NET);
        assertThat(k.revenue()).isEqualTo(900);
        assertThat(k.refunds()).isNull();
        assertThat(k.discount()).isNull();
        assertThat(k.tax()).isNull();
    }

    @Test
    void changePercentComparesEachHeadlineNumber() {
        Kpis now = Kpis.of(List.of(A), 0.0, Basis.NET);
        Kpis before = Kpis.of(List.of(B), 0.0, Basis.NET);
        Kpis.Change c = now.changeFrom(before);
        assertThat(c.revenue()).isEqualTo(200);
        assertThat(c.orders()).isEqualTo(100);
        assertThat(c.averageOrderValue()).isEqualTo(50);
        assertThat(c.units()).isEqualTo(200);
    }

    @Test
    void seriesFillsEmptyBucketsAndSubtractsRefundsOnTheirDay() {
        Period p = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        var series = Series.of(Map.of(LocalDate.of(2026, 10, 1), A, LocalDate.of(2026, 10, 3), B),
                Map.of(LocalDate.of(2026, 10, 3), 100.0), p, Granularity.DAY, Basis.NET);
        assertThat(series).containsExactly(
                new Series.Point(LocalDate.of(2026, 10, 1), 1500, 2),
                new Series.Point(LocalDate.of(2026, 10, 2), 0, 0),
                new Series.Point(LocalDate.of(2026, 10, 3), 400, 1));
    }

    @Test
    void seriesGroupsDaysIntoMonths() {
        Period p = Period.of(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 15));
        var series = Series.of(Map.of(LocalDate.of(2026, 9, 20), A, LocalDate.of(2026, 9, 30), B),
                Map.of(), p, Granularity.MONTH, Basis.GROSS);
        assertThat(series).containsExactly(
                new Series.Point(LocalDate.of(2026, 9, 1), 2000, 3),
                new Series.Point(LocalDate.of(2026, 10, 1), 0, 0));
    }
}
