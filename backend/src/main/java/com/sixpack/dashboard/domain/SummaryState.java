package com.sixpack.dashboard.domain;

import java.time.LocalDate;

/** How far a store's daily summary is complete: every day up to {@code through}, cut in {@code timeZone}. */
public record SummaryState(LocalDate through, String timeZone) {
}
