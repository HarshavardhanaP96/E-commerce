package com.sixpack.dashboard.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Which days a run recomputes, up to yesterday (today is still open and read live). Recent days are redone every
 * run because orders change after the fact (refunds, cancellations). A store with no rows yet, or one whose time
 * zone changed, is rebuilt from its first order.
 *
 * @param rebuild drop the store's existing rows first
 */
public record RefreshPlan(LocalDate from, LocalDate to, boolean rebuild) {

    public static Optional<RefreshPlan> of(Optional<SummaryState> state, String timeZone, LocalDate today,
                                           int recentDays, Optional<LocalDate> firstOrderDay) {
        LocalDate yesterday = today.minusDays(1);
        Optional<LocalDate> first = firstOrderDay.filter(day -> !day.isAfter(yesterday));
        if (state.isEmpty()) {
            return first.map(day -> new RefreshPlan(day, yesterday, true));
        }
        if (!state.get().timeZone().equals(timeZone)) {
            return Optional.of(new RefreshPlan(first.orElse(yesterday), yesterday, true));
        }
        // Not clamped to the first order: days whose orders were deleted since must be redone too.
        LocalDate recent = today.minusDays(recentDays);
        LocalDate missed = state.get().through().plusDays(1); // runs skipped while the app was down
        return Optional.of(new RefreshPlan(missed.isBefore(recent) ? missed : recent, yesterday, false));
    }
}
