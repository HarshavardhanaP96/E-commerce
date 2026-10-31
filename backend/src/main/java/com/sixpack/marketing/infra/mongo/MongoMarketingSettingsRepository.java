package com.sixpack.marketing.infra.mongo;

import com.sixpack.marketing.domain.MarketingSettings;
import com.sixpack.marketing.domain.MarketingSettingsRepository;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantMongo;
import java.time.Instant;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoMarketingSettingsRepository implements MarketingSettingsRepository {

    private final TenantMongo mongo;

    MongoMarketingSettingsRepository(TenantMongo mongo) {
        this.mongo = mongo;
    }

    @Override
    public MarketingSettings find() {
        return mongo.findOne(new Query(), MarketingSettingsDocument.class)
                .map(d -> new MarketingSettings(d.feedEnabled, d.feedToken)).orElse(MarketingSettings.NONE);
    }

    /** One atomic upsert: two admins enabling at once still leave one token. */
    @Override
    public MarketingSettings enableFeed(String newToken) {
        mongo.upsert(mine(), new Update().set("feedEnabled", true).setOnInsert("feedToken", newToken)
                .set("updatedAt", Instant.now()), MarketingSettingsDocument.class);
        return find();
    }

    @Override
    public void disableFeed() {
        mongo.updateFirst(mine(), new Update().set("feedEnabled", false).set("updatedAt", Instant.now()),
                MarketingSettingsDocument.class);
    }

    @Override
    public boolean rotateFeedToken(String newToken) {
        return mongo.updateFirst(mine().addCriteria(Criteria.where("feedEnabled").is(true)),
                new Update().set("feedToken", newToken).set("updatedAt", Instant.now()),
                MarketingSettingsDocument.class).getMatchedCount() > 0;
    }

    /** The store's row; TenantMongo adds the tenant and copies both onto a row an upsert creates. */
    private static Query mine() {
        return Query.query(Criteria.where("id").is(TenantContext.require().id()));
    }
}
