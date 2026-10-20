package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** An inclusive range of calendar days in the shop's time zone. */
public record Period(LocalDate from, LocalDate to) {

    public static final int MAX_DAYS = 731;

    public Period {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("A range can cover at most " + MAX_DAYS + " days");
        }
    }

    public static Period of(LocalDate from, LocalDate to) {
        return new Period(from, to);
    }

    /** The {@code days} days ending with {@code today}. */
    public static Period lastDays(LocalDate today, int days) {
        return new Period(today.minusDays(days - 1L), today);
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** The same number of days, ending the day before this period starts. */
    public Period previous() {
        return new Period(from.minusDays(days()), from.minusDays(1));
    }

    /** The same calendar days one year earlier (29 February becomes 28 February). */
    public Period yearBefore() {
        return new Period(from.minusYears(1), to.minusYears(1));
    }

    /** Midnight at the start of {@code from}. */
    public Instant start(ZoneId zone) {
        return from.atStartOfDay(zone).toInstant();
    }

    /** Midnight after {@code to}: the exclusive end. */
    public Instant end(ZoneId zone) {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
