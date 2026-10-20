package com.sixpack.dashboard.application;

import com.sixpack.dashboard.domain.DailySummary;
import com.sixpack.dashboard.domain.DailySummaryStore;
import com.sixpack.dashboard.domain.DashboardReadPort;
import com.sixpack.dashboard.domain.DayTotals;
import com.sixpack.dashboard.domain.RefreshPlan;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.shared.config.CacheConfig;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.StoreDirectory;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Writes the daily summary (analytics step 2): for each store, one row per local day with orders, revenue,
 * refunds, discount, tax, units and new customers. Runs nightly and once shortly after startup. One store failing
 * is logged and does not stop the others. Assumes one backend instance runs it (ADR-008); two overlapping runs
 * only redo the same work.
 */
@Service
public class DailySummaryJob {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryJob.class);
    private static final Scope WHOLE_STORE = new Scope(null, null, null);

    private final StoreDirectory stores;
    private final DashboardReadPort read;
    private final DailySummaryStore summaries;
    private final StoreConfig store;
    private final SummaryJobProperties props;
    private final CacheManager caches;
    private final TaskScheduler scheduler;

    DailySummaryJob(StoreDirectory stores, DashboardReadPort read, DailySummaryStore summaries, StoreConfig store,
                    SummaryJobProperties props, CacheManager caches, TaskScheduler scheduler) {
        this.stores = stores;
        this.read = read;
        this.summaries = summaries;
        this.store = store;
        this.props = props;
        this.caches = caches;
        this.scheduler = scheduler;
    }

    @Scheduled(cron = "${app.analytics.summary.cron:0 30 2 * * *}")
    void nightly() {
        refreshAll(Instant.now());
    }

    @EventListener(ApplicationReadyEvent.class)
    void afterStartup() {
        if (!props.startupDelay().isNegative()) {
            scheduler.schedule(() -> refreshAll(Instant.now()), Instant.now().plus(props.startupDelay()));
        }
    }

    /** Brings every store's summary up to yesterday (in the store's own time zone) as seen at {@code now}. */
    public void refreshAll(Instant now) {
        int done = 0;
        for (TenantRef ref : stores.stores()) {
            try {
                TenantContext.runAs(ref, () -> refresh(now));
                done++;
            } catch (RuntimeException e) {
                log.error("Daily summary failed for store '{}'", ref.slug(), e);
            }
        }
        Cache dashboard = caches.getCache(CacheConfig.DASHBOARD);
        if (dashboard != null) {
            dashboard.clear();
        }
        log.info("Daily summary refreshed for {} store(s)", done);
    }

    private void refresh(Instant now) {
        String timeZone = store.effective().timeZone();
        ZoneId zone = ZoneId.of(timeZone);
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate firstDay = read.firstOrderAt().map(at -> LocalDate.ofInstant(at, zone)).orElse(null);
        RefreshPlan.of(summaries.state(), timeZone, today, props.recentDays(), Optional.ofNullable(firstDay))
                .ifPresent(plan -> summaries.write(plan, timeZone, store.currency(), rows(plan, zone)));
    }

    private List<DailySummary> rows(RefreshPlan plan, ZoneId zone) {
        Instant from = plan.from().atStartOfDay(zone).toInstant();
        Instant to = plan.to().plusDays(1).atStartOfDay(zone).toInstant();
        Map<LocalDate, DayTotals> orders = read.dailyOrders(WHOLE_STORE, from, to, zone);
        Map<LocalDate, Double> refunds = read.dailyRefunds(WHOLE_STORE, from, to, zone);
        Map<LocalDate, Long> newCustomers = read.newCustomers(from, to, zone);
        TreeSet<LocalDate> days = new TreeSet<>(orders.keySet());
        days.addAll(refunds.keySet());
        List<DailySummary> rows = new ArrayList<>();
        for (LocalDate day : days) {
            DayTotals t = orders.getOrDefault(day, new DayTotals(0, 0, 0, 0.0, 0.0));
            rows.add(new DailySummary(day, t.orders(), t.sales(), refunds.getOrDefault(day, 0.0),
                    t.discount() == null ? 0 : t.discount(), t.tax() == null ? 0 : t.tax(), t.units(),
                    newCustomers.getOrDefault(day, 0L)));
        }
        return rows;
    }
}
