package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SectionQueryTest {

    private static final Scope ALL = new Scope(null, null, null);
    private static final Period OCT = Period.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

    @Test
    void picksAGranularityFromTheLengthWhenNoneIsGiven() {
        SectionQuery q = new SectionQuery(ALL, OCT, null, Compare.PREVIOUS, Basis.NET, 10);
        assertThat(q.granularity()).isEqualTo(Granularity.DAY);
    }

    @Test
    void limitMustBeBetweenOneAndFifty() {
        assertThatThrownBy(() -> new SectionQuery(ALL, OCT, null, Compare.NONE, Basis.NET, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("limit: must be between 1 and 50");
        assertThatThrownBy(() -> new SectionQuery(ALL, OCT, null, Compare.NONE, Basis.NET, 51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooManyPointsFailsBeforeAnyQuery() {
        Period twoYears = Period.of(LocalDate.of(2024, 11, 1), LocalDate.of(2026, 10, 31));
        assertThatThrownBy(() -> new SectionQuery(ALL, twoYears, Granularity.DAY, Compare.NONE, Basis.NET, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Too many points");
    }

    @Test
    void itemScopedWhenNarrowedToAProductOrCategory() {
        assertThat(new SectionQuery(ALL, OCT, null, Compare.NONE, Basis.NET, 10).itemScoped()).isFalse();
        assertThat(new SectionQuery(new Scope(null, "shoes", null), OCT, null, Compare.NONE, Basis.NET, 10)
                .itemScoped()).isTrue();
    }

    @Test
    void windowCarriesTheComparisonPeriodAndZone() {
        Window w = Window.of(new SectionQuery(ALL, OCT, null, Compare.PREVIOUS, Basis.NET, 10),
                ZoneId.of("Asia/Kolkata"));
        assertThat(w.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(w.compareFrom()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(w.compareTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(w.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(w.granularity()).isEqualTo("day");
    }

    @Test
    void windowWithoutComparison() {
        Window w = Window.of(new SectionQuery(ALL, OCT, null, Compare.NONE, Basis.NET, 10), ZoneId.of("UTC"));
        assertThat(w.compareFrom()).isNull();
        assertThat(w.compareTo()).isNull();
    }

    @Test
    void changeIsNullWithoutAComparisonAndShareNeverDividesByZero() {
        assertThat(Change.percent(150, null)).isNull();
        assertThat(Change.percent(150, 100.0)).isEqualTo(50L);
        assertThat(Change.share(1, 3)).isEqualTo(33.3);
        assertThat(Change.share(5, 0)).isZero();
        assertThat(Change.round2(1.0 / 3)).isEqualTo(0.33);
    }
}
