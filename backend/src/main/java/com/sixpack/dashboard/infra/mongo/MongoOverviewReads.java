package com.sixpack.dashboard.infra.mongo;

import com.sixpack.dashboard.domain.OverviewReads;
import com.sixpack.dashboard.domain.Scope;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.stereotype.Component;

@Component
class MongoOverviewReads implements OverviewReads {

    private final OrderQueries q;

    MongoOverviewReads(OrderQueries q) {
        this.q = q;
    }

    @Override
    public List<WeekHourCell> weekHour(Scope scope, Instant from, Instant to, ZoneId zone) {
        Document at = new Document("date", "$createdAt").append("timezone", zone.getId());
        Document key = new Document("d", new Document("$isoDayOfWeek", at)).append("h", new Document("$hour", at));
        List<WeekHourCell> out = new ArrayList<>();
        for (Document d : q.mongo().aggregate(q.salesBy(scope, from, to, null, key), OrderQueries.ORDERS,
                Document.class)) {
            Document id = d.get("_id", Document.class);
            out.add(new WeekHourCell(id.getInteger("d"), id.getInteger("h"), OrderQueries.orders(d),
                    OrderQueries.number(d.get("sales"))));
        }
        return out;
    }
}
