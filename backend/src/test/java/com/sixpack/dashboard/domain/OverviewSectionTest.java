package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.OverviewReads.WeekHourCell;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverviewSectionTest {

    private static final Window W = new Window(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null,
            "Asia/Kolkata", "day");
    private static final Kpis NONE = new Kpis(0, 0, 0, 0, 0.0, 0.0, 0.0);

    @Test
    void foldsCellsIntoWeekdaysHoursAndAHeatGrid() {
        OverviewSection s = OverviewSection.of(W, "net", NONE, null, null, List.of(), List.of(
                new WeekHourCell(1, 15, 2, 800), new WeekHourCell(1, 9, 1, 100), new WeekHourCell(7, 15, 3, 300)));
        assertThat(s.byWeekday()).hasSize(7);
        assertThat(s.byWeekday().get(0).orders()).isEqualTo(3);
        assertThat(s.byWeekday().get(0).revenue()).isEqualTo(900.0);
        assertThat(s.byWeekday().get(6).weekday()).isEqualTo(7);
        assertThat(s.byHour()[15]).isEqualTo(5);
        assertThat(s.heat()[0][9]).isEqualTo(1);
        assertThat(s.heat()[6][15]).isEqualTo(3);
    }

    @Test
    void emptyStoreHasSevenZeroDaysAndTwentyFourZeroHours() {
        OverviewSection s = OverviewSection.of(W, "net", NONE, null, null, List.of(), List.of());
        assertThat(s.byWeekday()).allMatch(d -> d.orders() == 0 && d.revenue() == 0);
        assertThat(s.byHour()).hasSize(24).containsOnly(0L);
        assertThat(s.heat()).hasNumberOfRows(7);
    }

    @Test
    void csvIsTheTrend() {
        OverviewSection s = OverviewSection.of(W, "net", NONE, null, null,
                List.of(new Series.Point(LocalDate.of(2026, 10, 1), 1500, 2)), List.of());
        assertThat(s.csvHeader()).containsExactly("start", "revenue", "orders");
        assertThat(s.csvRows()).containsExactly(List.of(LocalDate.of(2026, 10, 1), 1500.0, 2L));
    }
}
