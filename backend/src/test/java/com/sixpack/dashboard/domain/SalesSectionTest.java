package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalesSectionTest {

    @Test
    void breakdownNetIsChargedMinusRefunds() {
        SalesSection.Breakdown b = SalesSection.breakdown(new SalesReads.Breakdown(3, 1550, 100, 50, 100, 1), 200);
        assertThat(b.charged()).isEqualTo(1550);
        assertThat(b.net()).isEqualTo(1350);
        assertThat(b.couponOrderShare()).isEqualTo(33.3);
    }

    @Test
    void emptyPeriodHasNoDivisionByZero() {
        assertThat(SalesSection.breakdown(new SalesReads.Breakdown(0, 0, 0, 0, 0, 0), 0).couponOrderShare()).isZero();
    }

    @Test
    void csvRowsPutTheComparisonBesideEachBucket() {
        Window w = new Window(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), null, null, "UTC", "day");
        SalesSection s = new SalesSection(w, "net",
                List.of(new Series.Point(LocalDate.of(2026, 10, 1), 10, 1), new Series.Point(LocalDate.of(2026, 10, 2), 20, 2)),
                List.of(new Series.Point(LocalDate.of(2026, 9, 29), 5, 1)), null);
        assertThat(s.csvHeader()).containsExactly("start", "revenue", "orders", "previousRevenue");
        assertThat(s.csvRows()).containsExactly(Arrays.asList(LocalDate.of(2026, 10, 1), 10.0, 1L, 5.0),
                Arrays.asList(LocalDate.of(2026, 10, 2), 20.0, 2L, null));
    }
}
