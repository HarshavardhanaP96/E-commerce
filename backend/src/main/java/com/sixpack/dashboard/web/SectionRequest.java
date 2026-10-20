package com.sixpack.dashboard.web;

import com.sixpack.dashboard.domain.Basis;
import com.sixpack.dashboard.domain.Compare;
import com.sixpack.dashboard.domain.Granularity;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.dashboard.domain.SectionQuery;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;

/** Query parameters every section accepts; same rules as {@code /summary}. */
record SectionRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                      @Size(max = 10) String compare, @Size(max = 10) String granularity,
                      @Size(max = 10) String basis, @Size(max = 50) String category, Integer limit,
                      @Size(max = 10) String format) {

    static final int DEFAULT_DAYS = 30;

    /** @throws IllegalArgumentException on a bad value; the controller turns it into a 400 */
    SectionQuery toQuery(LocalDate today) {
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        String cat = category == null || category.isBlank() ? null : category.trim().toLowerCase(Locale.ROOT);
        return new SectionQuery(new Scope(null, cat, null), Period.of(start, end),
                parse(Granularity.class, granularity, null), parse(Compare.class, compare, Compare.PREVIOUS),
                parse(Basis.class, basis, Basis.NET), limit == null ? SectionQuery.DEFAULT_LIMIT : limit);
    }

    boolean csv() {
        if (format == null || format.isBlank() || format.equalsIgnoreCase("json")) {
            return false;
        }
        if (format.equalsIgnoreCase("csv")) {
            return true;
        }
        throw new IllegalArgumentException("format: unknown value");
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(type.getSimpleName().toLowerCase(Locale.ROOT) + ": unknown value");
        }
    }
}
