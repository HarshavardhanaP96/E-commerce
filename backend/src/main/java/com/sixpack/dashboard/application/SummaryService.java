package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.Basis;
import com.sixpack.dashboard.domain.Compare;
import com.sixpack.dashboard.domain.DailySummary;
import com.sixpack.dashboard.domain.DailySummaryStore;
import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.DayTotals;
import com.sixpack.dashboard.domain.Granularity;
import com.sixpack.dashboard.domain.Kpis;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.dashboard.domain.Series;
import com.sixpack.dashboard.domain.SummaryState;
import com.sixpack.shared.tenant.StoreConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Headline numbers and a trend for any date range, compared with another period. */
@Service
public class SummaryService {

    public record Summary(LocalDate from, LocalDate to, String timezone, String granularity, String basis,
                          LocalDate compareFrom, LocalDate compareTo, Kpis current, Kpis previous,
                          Kpis.Change changePercent, List<Series.Point> series) {
    }

    private final DashboardReadPort read;
    private final DailySummaryStore summaries;
    private final StoreConfig store;
    private final Clock clock;

    @Autowired
    SummaryService(DashboardReadPort read, DailySummaryStore summaries, StoreConfig store) {
        this(read, summaries, store, Clock.systemUTC());
    }

    SummaryService(DashboardReadPort read, DailySummaryStore summaries, StoreConfig store, Clock clock) {
        this.read = read;
        this.summaries = summaries;
        this.store = store;
        this.clock = clock;
    }

    /** The zone the store's days start and end in: its own setting, else the platform default. */
    public ZoneId zone() {
        return ZoneId.of(store.effective().timeZone());
    }

    /** Today in the store's time zone. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }

    /** @param granularity null picks one from the length of the period */
    @Cacheable(cacheNames = DASHBOARD,
            key = "'summary:' + #root.target.zone() + #scope + #period + #granularity + #compare + #basis")
    public Summary summary(Scope scope, Period period, Granularity granularity, Compare compare, Basis basis) {
        ZoneId zone = zone();
        Granularity g = granularity == null ? Granularity.auto(period) : granularity;
        boolean itemScoped = scope.productId() != null || scope.category() != null;
        g.buckets(period); // fails fast on too many points, before any query runs

        Days now = days(scope, period.from(), period.to(), zone, itemScoped);
        Map<LocalDate, DayTotals> days = now.orders();
        Map<LocalDate, Double> refunds = now.refunds();
        Kpis current = Kpis.of(days.values(), total(refunds), basis);

        Period other = compare.apply(period);
        Kpis previous = null;
        if (other != null) {
            Days before = days(scope, other.from(), other.to(), zone, itemScoped);
            previous = Kpis.of(before.orders().values(), total(before.refunds()), basis);
        }
        return new Summary(period.from(), period.to(), zone.getId(), lower(g), lower(basis),
                other == null ? null : other.from(), other == null ? null : other.to(), current, previous,
                previous == null ? null : current.changeFrom(previous),
                Series.of(days, refunds, period, g, basis));
    }

    /** Order totals and refunds (null when narrowed to items) per local day in [from, to]. */
    private record Days(Map<LocalDate, DayTotals> orders, Map<LocalDate, Double> refunds) {
    }

    /**
     * Whole-store views read the days the daily summary covers from it, and only the later days (today at least)
     * live; narrowed views always read live. A summary cut in another time zone is not used.
     */
    private Days days(Scope scope, LocalDate from, LocalDate to, ZoneId zone, boolean itemScoped) {
        Map<LocalDate, DayTotals> orders = new TreeMap<>();
        Map<LocalDate, Double> refunds = itemScoped ? null : new TreeMap<>();
        LocalDate liveFrom = from;
        boolean wholeStore = !itemScoped && scope.userId() == null;
        Optional<SummaryState> state = wholeStore ? summaries.state() : Optional.empty();
        if (state.isPresent() && state.get().timeZone().equals(zone.getId()) && !state.get().through().isBefore(from)) {
            LocalDate coveredTo = state.get().through().isBefore(to) ? state.get().through() : to;
            for (DailySummary row : summaries.range(from, coveredTo, store.currency())) {
                if (row.orders() > 0 || row.grossRevenue() != 0) {
                    orders.put(row.day(), row.orderTotals());
                }
                if (row.refunds() != 0) {
                    refunds.put(row.day(), row.refunds());
                }
            }
            liveFrom = coveredTo.plusDays(1);
        }
        if (!liveFrom.isAfter(to)) {
            Instant start = liveFrom.atStartOfDay(zone).toInstant();
            Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
            orders.putAll(read.dailyOrders(scope, start, end, zone));
            if (refunds != null) {
                refunds.putAll(read.dailyRefunds(scope, start, end, zone));
            }
        }
        return new Days(orders, refunds);
    }

    private static Double total(Map<LocalDate, Double> refunds) {
        return refunds == null ? null : refunds.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase();
    }
}
