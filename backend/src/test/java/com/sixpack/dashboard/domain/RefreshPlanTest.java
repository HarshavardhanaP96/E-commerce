package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RefreshPlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate FIRST_ORDER = LocalDate.of(2025, 3, 1);

    @Test
    void aStoreWithoutRowsIsBuiltFromItsFirstOrderToYesterday() {
        RefreshPlan plan = RefreshPlan.of(Optional.empty(), "Asia/Kolkata", TODAY, 30, Optional.of(FIRST_ORDER)).orElseThrow();
        assertThat(plan.from()).isEqualTo(FIRST_ORDER);
        assertThat(plan.to()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(plan.rebuild()).isTrue();
    }

    @Test
    void anUpToDateStoreRecomputesOnlyTheRecentWindow() {
        var state = new SummaryState(LocalDate.of(2026, 10, 18), "Asia/Kolkata");
        RefreshPlan plan = RefreshPlan.of(Optional.of(state), "Asia/Kolkata", TODAY, 30, Optional.of(FIRST_ORDER)).orElseThrow();
        assertThat(plan.from()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(plan.to()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(plan.rebuild()).isFalse();
    }

    @Test
    void aStoreThatMissedRunsCatchesUpFromWhereItStopped() {
        var state = new SummaryState(LocalDate.of(2026, 8, 1), "Asia/Kolkata");
        RefreshPlan plan = RefreshPlan.of(Optional.of(state), "Asia/Kolkata", TODAY, 30, Optional.of(FIRST_ORDER)).orElseThrow();
        assertThat(plan.from()).isEqualTo(LocalDate.of(2026, 8, 2));
    }

    @Test
    void aChangedTimeZoneRebuildsEverything() {
        var state = new SummaryState(LocalDate.of(2026, 10, 19), "Asia/Kolkata");
        RefreshPlan plan = RefreshPlan.of(Optional.of(state), "UTC", TODAY, 30, Optional.of(FIRST_ORDER)).orElseThrow();
        assertThat(plan.rebuild()).isTrue();
        assertThat(plan.from()).isEqualTo(FIRST_ORDER);
    }

    @Test
    void aStoreWithNoOrdersYetHasNothingToDo() {
        assertThat(RefreshPlan.of(Optional.empty(), "UTC", TODAY, 30, Optional.empty())).isEmpty();
    }

    @Test
    void ordersOnlyTodayLeaveNothingToSummariseYet() {
        assertThat(RefreshPlan.of(Optional.empty(), "UTC", TODAY, 30, Optional.of(TODAY))).isEmpty();
    }

    @Test
    void theWholeWindowIsRedoneEvenWhenTheFirstOrderNowFallsInsideIt() {
        // older orders deleted since the last run: their days must still be recomputed (to nothing)
        var state = new SummaryState(LocalDate.of(2026, 10, 19), "Asia/Kolkata");
        RefreshPlan plan = RefreshPlan.of(Optional.of(state), "Asia/Kolkata", TODAY, 30,
                Optional.of(LocalDate.of(2026, 10, 6))).orElseThrow();
        assertThat(plan.from()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void aStoreWhoseOrdersAreAllGoneStillHasItsWindowCleared() {
        var state = new SummaryState(LocalDate.of(2026, 10, 19), "Asia/Kolkata");
        assertThat(RefreshPlan.of(Optional.of(state), "Asia/Kolkata", TODAY, 30, Optional.empty())).isPresent();
    }
}
