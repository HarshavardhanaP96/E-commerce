package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.Basis;
import com.sixpack.dashboard.domain.Compare;
import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.DayTotals;
import com.sixpack.dashboard.domain.Granularity;
import com.sixpack.dashboard.domain.Kpis;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.dashboard.domain.Series;
import com.sixpack.shared.tenant.StoreConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
    private final StoreConfig store;
    private final Clock clock;

    @Autowired
    SummaryService(DashboardReadPort read, StoreConfig store) {
        this(read, store, Clock.systemUTC());
    }

    SummaryService(DashboardReadPort read, StoreConfig store, Clock clock) {
        this.read = read;
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

        Map<LocalDate, DayTotals> days = read.dailyOrders(scope, period.start(zone), period.end(zone), zone);
        Map<LocalDate, Double> refunds = itemScoped ? null
                : read.dailyRefunds(scope, period.start(zone), period.end(zone), zone);
        Kpis current = Kpis.of(days.values(), total(refunds), basis);

        Period other = compare.apply(period);
        Kpis previous = null;
        if (other != null) {
            Map<LocalDate, Double> otherRefunds = itemScoped ? null
                    : read.dailyRefunds(scope, other.start(zone), other.end(zone), zone);
            previous = Kpis.of(read.dailyOrders(scope, other.start(zone), other.end(zone), zone).values(),
                    total(otherRefunds), basis);
        }
        return new Summary(period.from(), period.to(), zone.getId(), lower(g), lower(basis),
                other == null ? null : other.from(), other == null ? null : other.to(), current, previous,
                previous == null ? null : current.changeFrom(previous),
                Series.of(days, refunds, period, g, basis));
    }

    private static Double total(Map<LocalDate, Double> refunds) {
        return refunds == null ? null : refunds.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase();
    }
}
