package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.Basis;
import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.OrderReads;
import com.sixpack.dashboard.domain.OrderReads.OrderFacts;
import com.sixpack.dashboard.domain.OrdersSection;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Series;
import com.sixpack.dashboard.domain.Window;
import com.sixpack.shared.tenant.StoreConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Orders tab. */
@Service
public class OrdersService {

    private final DashboardReadPort orders;
    private final OrderReads read;
    private final StoreTime time;
    private final StoreConfig store;
    private final Clock clock;

    @Autowired
    OrdersService(DashboardReadPort orders, OrderReads read, StoreTime time, StoreConfig store) {
        this(orders, read, time, store, Clock.systemUTC());
    }

    OrdersService(DashboardReadPort orders, OrderReads read, StoreTime time, StoreConfig store, Clock clock) {
        this.orders = orders;
        this.read = read;
        this.time = time;
        this.store = store;
        this.clock = clock;
    }

    /** Zone and stuck-order days: a change to either is never answered from the cache. */
    public String cacheTag() {
        return time.zone().getId() + ":" + store.effective().stuckOrderDays();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'orders:' + #root.target.cacheTag() + #q")
    public OrdersSection section(SectionQuery q) {
        ZoneId zone = time.zone();
        Period p = q.period();
        Instant from = p.start(zone);
        Instant to = p.end(zone);
        List<Series.Point> series = Series.of(orders.dailyOrders(q.scope(), from, to, zone), null, p,
                q.granularity(), Basis.GROSS);
        Period other = q.comparePeriod();
        List<Series.Point> previous = other == null ? null : Series.of(orders.dailyOrders(q.scope(),
                other.start(zone), other.end(zone), zone), null, other, q.granularity(), Basis.GROSS);

        List<OrderFacts> facts = read.orders(q.scope(), from, to, OrdersSection.FACTS_MAX + 1);
        boolean truncated = facts.size() > OrdersSection.FACTS_MAX;
        if (truncated) {
            facts = facts.subList(0, OrdersSection.FACTS_MAX);
        }
        int stuckDays = store.effective().stuckOrderDays();
        Instant now = clock.instant();
        Instant placedBefore = now.minus(Duration.ofDays(stuckDays));
        OrdersSection.Refunds refunds = q.itemScoped() ? null : OrdersSection.refunds(facts,
                orders.dailyRefunds(q.scope(), from, to, zone).values().stream().mapToDouble(Double::doubleValue)
                        .sum());
        return new OrdersSection(Window.of(q, zone), series, previous, OrdersSection.byStatus(facts),
                OrdersSection.fulfilment(facts, p, q.granularity(), zone),
                OrdersSection.stuck(read.stuck(q.scope(), placedBefore, q.limit()), now),
                read.stuckCount(q.scope(), placedBefore), stuckDays, OrdersSection.size(facts, q.limit()), refunds,
                truncated, facts);
    }
}
