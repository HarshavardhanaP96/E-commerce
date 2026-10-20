package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Read side of the Overview tab. */
public interface OverviewReads {

    /** @param weekday ISO day of week in the store's zone, 1 = Monday */
    record WeekHourCell(int weekday, int hour, long orders, double sales) {
    }

    /** Orders placed in [from, to) by store-local weekday and hour; narrowed to items like the daily trend. */
    List<WeekHourCell> weekHour(Scope scope, Instant from, Instant to, ZoneId zone);
}
