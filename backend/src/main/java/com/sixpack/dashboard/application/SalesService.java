package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.AudienceSegment;
import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.SalesReads;
import com.sixpack.dashboard.domain.SalesSection;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Series;
import com.sixpack.dashboard.domain.Window;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Sales tab, optionally narrowed to an audience segment (always read live). */
@Service
public class SalesService {

    private final DashboardReadPort orders;
    private final SalesReads sales;
    private final StoreTime time;
    private final AudienceSegments segments;

    SalesService(DashboardReadPort orders, SalesReads sales, StoreTime time, AudienceSegments segments) {
        this.orders = orders;
        this.sales = sales;
        this.time = time;
        this.segments = segments;
    }

    public String cacheTag() {
        return time.zone().getId();
    }

    /**
     * @param segment {@link AudienceSegment#ALL} for every order; otherwise only the segment's buyers count, and the
     *                section is hidden when the period's audience table hides the segment. The comparison uses the
     *                same segment with ages on its own last day, and is dropped when its table hides it.
     */
    @Cacheable(cacheNames = DASHBOARD, key = "'sales:' + #root.target.cacheTag() + #q + #segment")
    public SalesSection section(SectionQuery q, AudienceSegment segment) {
        ZoneId zone = time.zone();
        Period p = q.period();
        String basis = q.basis().name().toLowerCase(Locale.ROOT);
        Scope scope = narrowed(q.scope(), segment, p, zone);
        if (scope == null) {
            return SalesSection.hidden(Window.of(q, zone), basis);
        }
        Instant from = p.start(zone);
        Instant to = p.end(zone);
        Map<LocalDate, Double> refunds = q.itemScoped() ? null : orders.dailyRefunds(scope, from, to, zone);
        List<Series.Point> series = Series.of(orders.dailyOrders(scope, from, to, zone), refunds, p,
                q.granularity(), q.basis());

        Period other = q.comparePeriod();
        List<Series.Point> previousSeries = null;
        boolean previousSmall = false;
        if (other != null) {
            Scope before = narrowed(q.scope(), segment, other, zone);
            if (before == null) {
                previousSmall = true;
            } else {
                Instant of = other.start(zone);
                Instant ot = other.end(zone);
                previousSeries = Series.of(orders.dailyOrders(before, of, ot, zone),
                        q.itemScoped() ? null : orders.dailyRefunds(before, of, ot, zone), other, q.granularity(),
                        q.basis());
            }
        }
        double refunded = refunds == null ? 0 : refunds.values().stream().mapToDouble(Double::doubleValue).sum();
        return new SalesSection(Window.of(q, zone), basis, series, previousSeries,
                q.itemScoped() ? null : SalesSection.breakdown(sales.breakdown(scope, from, to), refunded), false,
                previousSmall);
    }

    /** The scope narrowed to the segment's buyers in {@code period}; null when the audience table hides it. */
    private Scope narrowed(Scope scope, AudienceSegment segment, Period period, ZoneId zone) {
        if (segment.isAll()) {
            return scope;
        }
        AudienceSegment.Match m = segments.match(segment, period, zone);
        return m.hidden() ? null : scope.withBuyers(m.buyers());
    }
}
