package com.sixpack.dashboard.infra.mongo;

import com.sixpack.dashboard.domain.DailySummary;
import com.sixpack.dashboard.domain.DailySummaryStore;
import com.sixpack.dashboard.domain.RefreshPlan;
import com.sixpack.dashboard.domain.SummaryState;
import com.sixpack.shared.tenant.BackfillTarget;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
class MongoDailySummaryStore implements DailySummaryStore {

    private final TenantMongo mongo;

    MongoDailySummaryStore(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<SummaryState> state() {
        return mongo.findOne(new Query(), DailySalesStateDocument.class)
                .filter(d -> d.through != null && d.timeZone != null)
                .map(d -> new SummaryState(LocalDate.parse(d.through), d.timeZone));
    }

    @Override
    public List<DailySummary> range(LocalDate from, LocalDate to, String currency) {
        Query query = Query.query(Criteria.where("currency").is(currency)
                .and("day").gte(from.toString()).lte(to.toString())).with(Sort.by("day"));
        return mongo.find(query, DailySalesDocument.class).stream()
                .map(d -> new DailySummary(LocalDate.parse(d.day), d.orders, d.grossRevenue, d.refunds, d.discount,
                        d.tax, d.units, d.newCustomers))
                .toList();
    }

    @Override
    public void write(RefreshPlan plan, String timeZone, String currency, Collection<DailySummary> rows) {
        Query replaced = plan.rebuild() ? new Query()
                : Query.query(Criteria.where("day").gte(plan.from().toString()).lte(plan.to().toString()));
        mongo.remove(replaced, DailySalesDocument.class);
        Instant now = Instant.now();
        for (DailySummary r : rows) {
            DailySalesDocument d = new DailySalesDocument();
            d.day = r.day().toString();
            d.currency = currency;
            d.timeZone = timeZone;
            d.orders = r.orders();
            d.grossRevenue = r.grossRevenue();
            d.refunds = r.refunds();
            d.netRevenue = r.netRevenue();
            d.discount = r.discount();
            d.tax = r.tax();
            d.units = r.units();
            d.newCustomers = r.newCustomers();
            d.computedAt = now;
            mongo.insert(d);
        }
        // one state document per store; the key makes a second one impossible even if two runs overlap
        mongo.upsert(Query.query(Criteria.where("key").is(TenantContext.require().id())),
                new Update().set("through", plan.to().toString()).set("timeZone", timeZone).set("computedAt", now),
                DailySalesStateDocument.class);
    }

    /** Offboarding exports and purges these with the rest of the store's data. */
    @Configuration
    static class Backfill {
        @Bean
        BackfillTarget dailySalesBackfill() {
            return () -> "daily_sales";
        }

        @Bean
        BackfillTarget dailySalesStateBackfill() {
            return () -> "daily_sales_state";
        }
    }
}
