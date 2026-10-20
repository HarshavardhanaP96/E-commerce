package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.OverviewReads;
import com.sixpack.dashboard.domain.OverviewSection;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Window;
import java.time.ZoneId;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** The Overview tab: the summary for the period plus when in the week orders come in. */
@Service
public class OverviewService {

    private final SummaryService summaries;
    private final OverviewReads read;
    private final StoreTime time;

    OverviewService(SummaryService summaries, OverviewReads read, StoreTime time) {
        this.summaries = summaries;
        this.read = read;
        this.time = time;
    }

    /** Part of every cache key, so a changed store zone is never answered from the cache. */
    public String cacheTag() {
        return time.zone().getId();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'overview:' + #root.target.cacheTag() + #q")
    public OverviewSection section(SectionQuery q) {
        ZoneId zone = time.zone();
        SummaryService.Summary s = summaries.summary(q.scope(), q.period(), q.granularity(), q.compare(), q.basis());
        return OverviewSection.of(Window.of(q, zone), s.basis(), s.current(), s.previous(), s.changePercent(),
                s.series(), read.weekHour(q.scope(), q.period().start(zone), q.period().end(zone), zone));
    }
}
