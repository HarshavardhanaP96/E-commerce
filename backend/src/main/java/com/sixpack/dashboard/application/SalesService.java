package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.SalesReads;
import com.sixpack.dashboard.domain.SalesSection;
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

/** The Sales tab. */
@Service
public class SalesService {

    private final DashboardReadPort orders;
    private final SalesReads sales;
    private final StoreTime time;

    SalesService(DashboardReadPort orders, SalesReads sales, StoreTime time) {
        this.orders = orders;
        this.sales = sales;
        this.time = time;
    }

    public String cacheTag() {
        return time.zone().getId();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'sales:' + #root.target.cacheTag() + #q")
    public SalesSection section(SectionQuery q) {
        ZoneId zone = time.zone();
        Period p = q.period();
        Instant from = p.start(zone);
        Instant to = p.end(zone);
        Map<LocalDate, Double> refunds = q.itemScoped() ? null : orders.dailyRefunds(q.scope(), from, to, zone);
        List<Series.Point> series = Series.of(orders.dailyOrders(q.scope(), from, to, zone), refunds, p,
                q.granularity(), q.basis());

        Period other = q.comparePeriod();
        List<Series.Point> previousSeries = null;
        if (other != null) {
            Instant of = other.start(zone);
            Instant ot = other.end(zone);
            previousSeries = Series.of(orders.dailyOrders(q.scope(), of, ot, zone),
                    q.itemScoped() ? null : orders.dailyRefunds(q.scope(), of, ot, zone), other, q.granularity(),
                    q.basis());
        }
        double refunded = refunds == null ? 0 : refunds.values().stream().mapToDouble(Double::doubleValue).sum();
        return new SalesSection(Window.of(q, zone), q.basis().name().toLowerCase(Locale.ROOT), series,
                previousSeries,
                q.itemScoped() ? null : SalesSection.breakdown(sales.breakdown(q.scope(), from, to), refunded));
    }
}
