package com.sixpack.dashboard.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/** The days a section covers, the days it is compared with (null when off), the zone and the bucket size. */
public record Window(LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo, String timezone,
                     String granularity) {

    public static Window of(SectionQuery q, ZoneId zone) {
        Period other = q.comparePeriod();
        return new Window(q.period().from(), q.period().to(), other == null ? null : other.from(),
                other == null ? null : other.to(), zone.getId(), q.granularity().name().toLowerCase(Locale.ROOT));
    }
}
