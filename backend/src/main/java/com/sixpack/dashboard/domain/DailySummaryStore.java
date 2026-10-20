package com.sixpack.dashboard.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The current store's daily summary rows. */
public interface DailySummaryStore {

    Optional<SummaryState> state();

    /** Rows for days in [from, to] in {@code currency}, oldest first; days without orders or refunds have none. */
    List<DailySummary> range(LocalDate from, LocalDate to, String currency);

    /**
     * Replaces the rows of the plan's days (all of the store's rows when it is a rebuild) and marks the summary
     * complete through {@code plan.to()} in {@code timeZone}.
     */
    void write(RefreshPlan plan, String timeZone, String currency, Collection<DailySummary> rows);
}
